/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.durabletask;

import com.google.common.io.Files;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.DaemonThreadFactory;
import hudson.remoting.NamingThreadFactory;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.MasterToSlaveFileCallable;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.io.output.WriterOutputStream;

/**
 * A task which forks some external command and then waits for log and status files to be updated/created.
 */
public abstract class FileMonitoringTask extends DurableTask {

    private static final Logger LOGGER = Logger.getLogger(FileMonitoringTask.class.getName());

    private static final String COOKIE = "JENKINS_SERVER_COOKIE";

    /** Value of {@link #charset} used to mean the node’s system default. */
    private static final String SYSTEM_DEFAULT_CHARSET = "SYSTEM_DEFAULT";

    /**
     * Charset name to use for transcoding, or {@link #SYSTEM_DEFAULT_CHARSET}, or null for no transcoding.
     */
    private @CheckForNull String charset;

    private static String cookieFor(FilePath workspace) {
        return "durable-" + Util.getDigestOf(workspace.getRemote());
    }

    @Override public final Controller launch(EnvVars env, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
        FileMonitoringController controller = launchWithCookie(workspace, launcher, listener, env, COOKIE, cookieFor(workspace));
        controller.charset = charset;
        return controller;
    }

    protected FileMonitoringController launchWithCookie(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        envVars.put(cookieVariable, cookieValue); // ensure getCharacteristicEnvVars does not match, so Launcher.killAll will leave it alone
        return doLaunch(workspace, launcher, listener, envVars);
    }

    @Override public final void charset(Charset cs) {
        charset = cs.name();
    }

    @Override public final void defaultCharset() {
        charset = SYSTEM_DEFAULT_CHARSET;
    }

    /**
     * Should start a process which sends output to {@linkplain FileMonitoringController#getLogFile(FilePath) log file}
     * in the workspace and finally writes its exit code to {@linkplain FileMonitoringController#getResultFile(FilePath) result file}.
     * @param workspace the workspace to use
     * @param launcher a way to launch processes
     * @param listener build console log
     * @param envVars recommended environment for the subprocess
     * @return a specialized controller
     */
    protected FileMonitoringController doLaunch(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        throw new AbstractMethodError("override either doLaunch or launchWithCookie");
    }

    /**
     * JENKINS-40734: blocks the substitutions of {@link EnvVars#overrideExpandingAll} done by {@link Launcher}.
     */
    protected static Map<String, String> escape(EnvVars envVars) {
        Map<String, String> m = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            m.put(entry.getKey(), entry.getValue().replace("$", "$$"));
        }
        return m;
    }

    /**
     * Tails a log file and watches for an exit status file.
     * Must be remotable so that {@link #watch} can transfer the implementation.
     */
    protected static class FileMonitoringController extends Controller { // TODO implements Remotable when available (*not* SerializableOnlyOverRemoting)

        /** Absolute path of {@link #controlDir(FilePath)}. */
        String controlDir;

        /**
         * @deprecated used only in pre-1.8
         */
        private String id;

        /**
         * Byte offset in the file that has been reported thus far.
         * Only used if {@link #writeLog(FilePath, OutputStream)} is used; not used for {@link #watch}.
         */
        private long lastLocation;

        /** @see FileMonitoringTask#charset */
        private @CheckForNull String charset;

        /**
         * {@link #transcodingCharset} on the remote side when using {@link #writeLog}.
         * May be a wrapper for null; initialized on demand.
         */
        private transient volatile AtomicReference<Charset> writeLogCs;

        protected FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            ws.mkdirs();
            FilePath cd = tempDir(ws).child("durable-" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8));
            cd.mkdirs();
            controlDir = cd.getRemote();
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            if (writeLogCs == null) {
                if (SYSTEM_DEFAULT_CHARSET.equals(charset)) {
                    String cs = workspace.act(new TranscodingCharsetForSystemDefault());
                    writeLogCs = new AtomicReference<>(cs == null ? null : Charset.forName(cs));
                } else {
                    // Does not matter what system default charset on the remote side is, so save the Remoting call.
                    writeLogCs = new AtomicReference<>(transcodingCharset(charset));
                }
                LOGGER.log(Level.FINE, "remote transcoding charset: {0}", writeLogCs);
            }
            FilePath log = getLogFile(workspace);
            OutputStream transcodedSink;
            if (writeLogCs.get() == null) {
                transcodedSink = sink;
            } else {
                // WriterOutputStream constructor taking Charset calls .replaceWith("?") which we do not want:
                CharsetDecoder decoder = writeLogCs.get().newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
                transcodedSink = new WriterOutputStream(new OutputStreamWriter(sink, StandardCharsets.UTF_8), decoder, 1024, true);
            }
            CountingOutputStream cos = new CountingOutputStream(transcodedSink);
            try {
                log.act(new WriteLog(lastLocation, new RemoteOutputStream(cos)));
                return cos.getByteCount() > 0;
            } finally { // even if RemoteOutputStream write was interrupted, record what we actually received
                transcodedSink.flush(); // writeImmediately flag does not seem to work
                long written = cos.getByteCount();
                if (written > 0) {
                    LOGGER.log(Level.FINE, "copied {0} bytes from {1}", new Object[] {written, log});
                    lastLocation += written;
                }
            }
        }
        private static class WriteLog extends MasterToSlaveFileCallable<Void> {
            private final long lastLocation;
            private final OutputStream sink;
            WriteLog(long lastLocation, OutputStream sink) {
                this.lastLocation = lastLocation;
                this.sink = sink;
            }
            @Override public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                long len = f.length();
                if (len > lastLocation) {
                    try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                        raf.seek(lastLocation);
                        long toRead = len - lastLocation;
                        if (toRead > Integer.MAX_VALUE) { // >2Gb of output at once is unlikely
                            throw new IOException("large reads not yet implemented");
                        }
                        byte[] buf = new byte[(int) toRead];
                        raf.readFully(buf);
                        sink.write(buf);
                    }
                }
                return null;
            }
        }

        private static class TranscodingCharsetForSystemDefault extends MasterToSlaveCallable<String, RuntimeException> {
            @Override public String call() throws RuntimeException {
                Charset cs = transcodingCharset(SYSTEM_DEFAULT_CHARSET);
                return cs != null ? cs.name() : null;
            }
        }

        /** Avoids excess round-tripping when reading status file. */
        static class StatusCheck extends MasterToSlaveFileCallable<Integer> {
            @Override
            @CheckForNull
            public Integer invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                if (f.exists() && f.length() > 0) {
                    try {
                        String fileString = Files.readFirstLine(f, Charset.defaultCharset());
                        if (fileString == null || fileString.isEmpty()) {
                            return null;
                        } else {
                            fileString = fileString.trim();
                            if (fileString.isEmpty()) {
                                return null;
                            } else {
                                return Integer.parseInt(fileString);
                            }
                        }
                    } catch (NumberFormatException x) {
                        throw new IOException("corrupted content in " + f + ": " + x, x);
                    }
                }
                return null;
            }
        }

        static final StatusCheck STATUS_CHECK_INSTANCE = new StatusCheck();

        @Override public Integer exitStatus(FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
            return exitStatus(workspace, listener);
        }

        /**
         * Like {@link #exitStatus(FilePath, Launcher, TaskListener)} but not requesting a {@link Launcher}, which would not be available in {@link #watch} mode anyway.
         */
        protected @CheckForNull Integer exitStatus(FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
            FilePath status = getResultFile(workspace);
            return status.act(STATUS_CHECK_INSTANCE);
        }

        @Override public byte[] getOutput(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return getOutput(workspace);
        }

        /**
         * Like {@link #getOutput(FilePath, Launcher)} but not requesting a {@link Launcher}, which would not be available in {@link #watch} mode anyway.
         */
        protected byte[] getOutput(FilePath workspace) throws IOException, InterruptedException {
            return getOutputFile(workspace).act(new MasterToSlaveFileCallable<byte[]>() {
                @Override public byte[] invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                    byte[] buf = FileUtils.readFileToByteArray(f);
                    ByteBuffer transcoded = maybeTranscode(buf, charset);
                    if (transcoded == null) {
                        return buf;
                    } else {
                        byte[] buf2 = new byte[transcoded.remaining()];
                        transcoded.get(buf2);
                        return buf2;
                    }
                }
            });
        }

        /**
         * Transcode process output to UTF-8 if necessary.
         * @param data output presumed to be in local encoding
         * @param charset a particular encoding name, or the empty string for the system default encoding, or null to skip transcoding
         * @return a buffer of UTF-8 encoded data ({@link CodingErrorAction#REPLACE} is used),
         *         or null if not performing transcoding because it was not requested or the data was already thought to be in UTF-8
         */
        private static @CheckForNull ByteBuffer maybeTranscode(@Nonnull byte[] data, @CheckForNull String charset) {
            Charset cs = transcodingCharset(charset);
            if (cs == null) {
                return null;
            } else {
                return StandardCharsets.UTF_8.encode(cs.decode(ByteBuffer.wrap(data)));
            }
        }

        private static @CheckForNull Charset transcodingCharset(@CheckForNull String charset) {
            if (charset == null) {
                return null;
            } else {
                Charset cs = charset.equals(SYSTEM_DEFAULT_CHARSET) ? Charset.defaultCharset() : Charset.forName(charset);
                if (cs.equals(StandardCharsets.UTF_8)) { // transcoding unnecessary as output was already UTF-8
                    return null;
                } else { // decode output in specified charset and reëncode in UTF-8
                    return cs;
                }
            }
        }

        @Override public final void stop(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            launcher.kill(Collections.singletonMap(COOKIE, cookieFor(workspace)));
        }

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            controlDir(workspace).deleteRecursive();
        }

        /**
         * Directory in which this controller can place files.
         * Unique among all the controllers sharing the same workspace.
         */
        public FilePath controlDir(FilePath ws) throws IOException, InterruptedException {
            if (controlDir != null) { // normal case
                return ws.child(controlDir); // despite the name, this is an absolute path
            }
            assert id != null;
            FilePath cd = ws.child("." + id); // compatibility with 1.6
            if (!cd.isDirectory()) {
                cd = ws.child(".jenkins-" + id); // compatibility with 1.7
            }
            controlDir = cd.getRemote();
            id = null;
            LOGGER.info("using migrated control directory " + controlDir + " for remainder of this task");
            return cd;
        }

        // TODO 1.652 use WorkspaceList.tempDir
        private static FilePath tempDir(FilePath ws) {
            return ws.sibling(ws.getName() + System.getProperty(WorkspaceList.class.getName(), "@") + "tmp");
        }

        /**
         * File in which the exit code of the process should be reported.
         */
        public FilePath getResultFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("jenkins-result.txt");
        }

        /**
         * File in which the stdout/stderr (or, if {@link #captureOutput} is called, just stderr) is written.
         */
        public FilePath getLogFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("jenkins-log.txt");
        }

        /**
         * File in which the stdout is written, if {@link #captureOutput} is called.
         */
        public FilePath getOutputFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("output.txt");
        }

        @Override public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            FilePath cd = controlDir(workspace);
            VirtualChannel channel = cd.getChannel();
            String node = (channel instanceof Channel) ? ((Channel) channel).getName() : null;
            String location = node != null ? cd.getRemote() + " on " + node : cd.getRemote();
            StringWriter w = new StringWriter();
            Integer code = exitStatus(workspace, launcher, new StreamTaskListener(w));
            if (code != null) {
                return w + "completed process (code " + code + ") in " + location;
            } else {
                return w + "awaiting process completion in " + location;
            }
        }

        @Override public void watch(FilePath workspace, Handler handler, TaskListener listener) throws IOException, InterruptedException, ClassCastException {
            workspace.actAsync(new StartWatching(this, handler, listener));
            LOGGER.log(Level.FINE, "started asynchronous watch in {0}", controlDir);
        }

        /**
         * File in which a last-read position is stored if {@link #watch} is used.
         */
        public FilePath getLastLocationFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("last-location.txt");
        }

        private static final long serialVersionUID = 1L;
    }

    private static ScheduledExecutorService watchService;
    private synchronized static ScheduledExecutorService watchService() {
        if (watchService == null) {
            // TODO 2.105+ use ClassLoaderSanityThreadFactory
            watchService = new /*ErrorLogging*/ScheduledThreadPoolExecutor(5, new NamingThreadFactory(new DaemonThreadFactory(), "FileMonitoringTask watcher"));
        }
        return watchService;
    }

    private static class StartWatching extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final FileMonitoringController controller;
        private final Handler handler;
        private final TaskListener listener;

        StartWatching(FileMonitoringController controller, Handler handler, TaskListener listener) {
            this.controller = controller;
            this.handler = handler;
            this.listener = listener;
        }

        @Override public Void invoke(File workspace, VirtualChannel channel) throws IOException, InterruptedException {
            watchService().submit(new Watcher(controller, new FilePath(workspace), handler, listener));
            return null;
        }

    }

    private static class Watcher implements Runnable {

        private final FileMonitoringController controller;
        private final FilePath workspace;
        private final Handler handler;
        private final TaskListener listener;
        private final @CheckForNull Charset cs;

        Watcher(FileMonitoringController controller, FilePath workspace, Handler handler, TaskListener listener) {
            this.controller = controller;
            this.workspace = workspace;
            this.handler = handler;
            this.listener = listener;
            cs = FileMonitoringController.transcodingCharset(controller.charset);
        }

        @Override public void run() {
            try {
                Integer exitStatus = controller.exitStatus(workspace, listener); // check before collecting output, in case the process is just now finishing
                long lastLocation = 0;
                FilePath lastLocationFile = controller.getLastLocationFile(workspace);
                if (lastLocationFile.exists()) {
                    lastLocation = Long.parseLong(lastLocationFile.readToString());
                }
                FilePath logFile = controller.getLogFile(workspace);
                long len = logFile.length();
                if (len > lastLocation) {
                    assert !logFile.isRemote();
                    try (FileChannel ch = FileChannel.open(Paths.get(logFile.getRemote()), StandardOpenOption.READ)) {
                        InputStream locallyEncodedStream = Channels.newInputStream(ch.position(lastLocation));
                        CountingInputStream cis = new CountingInputStream(locallyEncodedStream);
                        InputStream utf8EncodedStream = cs == null ? cis : new ReaderInputStream(new InputStreamReader(cis, cs), StandardCharsets.UTF_8);
                        handler.output(utf8EncodedStream);
                        lastLocationFile.write(Long.toString(lastLocation + cis.getByteCount()), null);
                    }
                }
                if (exitStatus != null) {
                    byte[] output;
                    if (controller.getOutputFile(workspace).exists()) {
                        output = controller.getOutput(workspace);
                    } else {
                        output = null;
                    }
                    handler.exited(exitStatus, output);
                    controller.cleanup(workspace);
                } else {
                    if (!controller.controlDir(workspace).isDirectory()) {
                        LOGGER.log(Level.WARNING, "giving up on watching nonexistent {0}", controller.controlDir);
                        return;
                    }
                    // Could use an adaptive timeout as in DurableTaskStep.Execution in polling mode,
                    // though less relevant here since there is no network overhead to the check.
                    watchService().schedule(this, 100, TimeUnit.MILLISECONDS);
                }
            } catch (Exception x) {
                // note that LOGGER here is going to the agent log, not master log
                LOGGER.log(Level.WARNING, "giving up on watching " + controller.controlDir, x);
            }
        }

    }

}
