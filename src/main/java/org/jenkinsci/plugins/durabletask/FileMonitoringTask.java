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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.Channel;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.StreamTaskListener;

import java.io.Closeable;
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.remoting.ChannelClosedException;
import java.io.EOFException;
import java.nio.channels.ClosedChannelException;
import java.util.stream.Stream;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import jenkins.util.Timer;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.input.ReaderInputStream;
import org.apache.commons.io.output.CountingOutputStream;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.remoting.util.IOUtils;

/**
 * A task which forks some external command and then waits for log and status files to be updated/created.
 */
public abstract class FileMonitoringTask extends DurableTask {

    private static final Logger LOGGER = Logger.getLogger(FileMonitoringTask.class.getName());

    protected static final String COOKIE = "JENKINS_SERVER_COOKIE";

    protected static final String BINARY_RESOURCE_PREFIX = "/io/jenkins/plugins/lib-durable-task/durable_task_monitor_";

    /** Value of {@link #charset} used to mean the node’s system default. */
    private static final String SYSTEM_DEFAULT_CHARSET = "SYSTEM_DEFAULT";

    /**
     * Charset name to use for transcoding, or {@link #SYSTEM_DEFAULT_CHARSET}, or null for no transcoding.
     */
    private @CheckForNull String charset;

    /**
     * Provides the cookie value for a given {@link FilePath} workspace. It also uses
     * a flag to identify if we want this cookie hash based on MD5 (former/old format) or SHA-256 algorithm.
     * This method is only used to maintain backward compatibility on stopping tasks that were
     * launched before the new format was applied.
     * @param workspace path used to setup the digest
     * @param boolean to select if we want to use former/old hash algorithm to maintain backward compatibility
     * @return the cookie value
     */
    private static String cookieFor(FilePath workspace, boolean old) {
        return String.format("durable-%s", old ? Util.getDigestOf(workspace.getRemote()) : digest(workspace.getRemote()));
    }
    
    private static String cookieFor(FilePath workspace) {
        return cookieFor(workspace, false);
    }

    private static String digest(String text) {
        try {
            return Util.toHexString(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
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

    @CheckForNull final String getCharset()
    {
        return charset;
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

    protected static FilePath getNodeRoot(FilePath workspace) throws IOException {
        Computer computer = workspace.toComputer();
        if (computer == null) {
            throw new IOException("Unable to retrieve computer for workspace");
        }
        Node node = computer.getNode();
        if (node == null) {
            throw new IOException("Unable to retrieve node for workspace");
        }
        FilePath nodeRoot = node.getRootPath();
        if (nodeRoot == null) {
            throw new IOException("Unable to retrieve root path of node");
        }
        return nodeRoot;
    }

    protected static AgentInfo getAgentInfo(FilePath nodeRoot) throws IOException, InterruptedException {
        final Jenkins jenkins = Jenkins.get();
        PluginWrapper durablePlugin = jenkins.getPluginManager().getPlugin("durable-task");
        if (durablePlugin == null) {
            throw new IOException("Unable to find durable task plugin");
        }
        String pluginVersion = StringUtils.substringBefore(durablePlugin.getVersion(), "-");
        AgentInfo agentInfo = nodeRoot.act(new AgentInfo.GetAgentInfo(pluginVersion));

        return agentInfo;
    }

    /**
     * Returns path of binary on agent. Copies binary to agent if it does not exist
     */
    @CheckForNull
    protected static FilePath requestBinary(FilePath ws, FileMonitoringController c) throws IOException, InterruptedException {
        FilePath nodeRoot = getNodeRoot(ws);
        AgentInfo agentInfo = getAgentInfo(nodeRoot);
        return requestBinary(nodeRoot, agentInfo, ws, c);
    }

    /**
     * Returns path of binary on agent. Copies binary to agent if it does not exist
     */
    @CheckForNull
    @SuppressFBWarnings(value = {"NP_LOAD_OF_KNOWN_NULL_VALUE", "RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE"}, justification = "TODO needs triage")
    protected static FilePath requestBinary(FilePath nodeRoot, AgentInfo agentInfo, FilePath ws, FileMonitoringController c) throws IOException, InterruptedException {
        FilePath binary = null;
        if (agentInfo.isBinaryCompatible()) {
            FilePath controlDir = c.controlDir(ws);
            if (agentInfo.isCachingAvailable()) {
                binary = nodeRoot.child(agentInfo.getBinaryPath());
            } else {
                binary = controlDir.child(agentInfo.getBinaryPath());
            }
            String resourcePath = BINARY_RESOURCE_PREFIX + agentInfo.getOs().getNameForBinary() + "_" + agentInfo.getArchitecture();
            if (agentInfo.getOs() == AgentInfo.OsType.WINDOWS) {
                resourcePath += ".exe";
            }
            try (InputStream binaryStream = BourneShellScript.class.getResourceAsStream(resourcePath)) {
                if (binaryStream != null) {
                    if (!agentInfo.isCachingAvailable() || !agentInfo.isBinaryCached()) {
                        binary.copyFrom(binaryStream);
                        binary.chmod(0755);
                    }
                } else {
                	return null;
                }
            }
        }
        return binary;
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
        
        /** Store the value for {@link #COOKIE} */
        private String cookieValue;

        /** @see FileMonitoringTask#charset */
        private @CheckForNull String charset;

        String getCharset() {
            return charset;
        }

        private transient List<Closeable> cleanupList;

        void registerForCleanup(Closeable c) {
            if (cleanupList == null) {
                cleanupList = new LinkedList<>();
            }
            cleanupList.add(c);
        }

        /**
         * {@link #transcodingCharset} on the remote side when using {@link #writeLog}.
         * May be null; initialized on demand.
         */
        private transient volatile Charset writeLogCs;

        protected FileMonitoringController(FilePath ws, @NonNull String cookieValue) throws IOException, InterruptedException {
            setupControlDir(ws);
            this.cookieValue = cookieValue;
        }
        
        @Deprecated
        protected FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            setupControlDir(ws);
            this.cookieValue = cookieFor(ws, true);
        }
        
        private void setupControlDir(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            ws.mkdirs();
            FilePath tmpDir = /* TODO pending JENKINS-61197 fix in baseline */ ws.getParent() != null ? WorkspaceList.tempDir(ws) : null;
            if (tmpDir != null) {
                FilePath cd = tmpDir.child("durable-" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8));
                cd.mkdirs();
                controlDir = cd.getRemote();
            } else {
                controlDir = null;
            }
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            if (writeLogCs == null) {
                if (SYSTEM_DEFAULT_CHARSET.equals(charset)) {
                    String cs = workspace.act(new TranscodingCharsetForSystemDefault());
                    writeLogCs = cs == null ? null : Charset.forName(cs);
                } else {
                    // Does not matter what system default charset on the remote side is, so save the Remoting call.
                    writeLogCs = transcodingCharset(charset);
                }
                LOGGER.log(Level.FINER, "remote transcoding charset: {0}", writeLogCs);
            }
            FilePath log = getLogFile(workspace);
            OutputStream transcodedSink;
            if (writeLogCs == null) {
                transcodedSink = sink;
            } else {
                // WriterOutputStream constructor taking Charset calls .replaceWith("?") which we do not want:
                CharsetDecoder decoder = writeLogCs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
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
                    LOGGER.log(Level.FINER, "copied {0} bytes from {1}", new Object[] {written, log});
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
                    String text = Files.readString(f.toPath(), Charset.defaultCharset()).trim();
                    if (text.isEmpty()) {
                        return null;
                    } else {
                        try {
                            long value = Long.parseLong(text);
                            if (value > Integer.MAX_VALUE) {
                                LOGGER.warning("ErrorCode greater than max integer detected, limited to max value");
                                value = Integer.MAX_VALUE;
                            }
                            return (Integer) (int) value;
                        } catch (NumberFormatException x) {
                            throw new IOException("corrupted content in " + f + ": " + x, x);
                        }
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
            return getOutputFile(workspace).act(new GetOutput(charset));
        }

        private static class GetOutput extends MasterToSlaveFileCallable<byte[]> {
            private final String charset;
            GetOutput(String charset) {
                this.charset = charset;
            }
            @Override
            public byte[] invoke(File file, VirtualChannel vc) throws IOException, InterruptedException {
                byte[] buf = FileUtils.readFileToByteArray(file);
                ByteBuffer transcoded = maybeTranscode(buf, charset);
                if (transcoded == null) {
                    return buf;
                } else {
                    byte[] buf2 = new byte[transcoded.remaining()];
                    transcoded.get(buf2);
                    return buf2;
                }
            }
        }

        /**
         * Transcode process output to UTF-8 if necessary.
         * @param data output presumed to be in local encoding
         * @param charset a particular encoding name, or the empty string for the system default encoding, or null to skip transcoding
         * @return a buffer of UTF-8 encoded data ({@link CodingErrorAction#REPLACE} is used),
         *         or null if not performing transcoding because it was not requested or the data was already thought to be in UTF-8
         */
        private static @CheckForNull ByteBuffer maybeTranscode(@NonNull byte[] data, @CheckForNull String charset) {
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
            if (cookieValue == null) {
                cookieValue = cookieFor(workspace, true); // To maintain backward compatibility
            }
            launcher.kill(Collections.singletonMap(COOKIE, cookieValue));
        }

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            controlDir(workspace).deleteRecursive();
            if (cleanupList != null) {
                cleanupList.stream().forEach(IOUtils::closeQuietly);
            }
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
            LOGGER.log(Level.FINE, "started asynchronous watch in " + controlDir, new Throwable());
        }

        /**
         * File in which a last-read position is stored if {@link #watch} is used.
         */
        public FilePath getLastLocationFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("last-location.txt");
        }

        private static final long serialVersionUID = 1L;
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
            Timer.get().submit(new Watcher(controller, new FilePath(workspace), handler, listener));
            return null;
        }

    }

    // TODO https://github.com/jenkinsci/remoting/pull/657
    private static boolean isClosedChannelException(Throwable t) {
        if (t instanceof ClosedChannelException) {
            return true;
        } else if (t instanceof ChannelClosedException) {
            return true;
        } else if (t instanceof EOFException) {
            return true;
        } else if (t == null) {
            return false;
        } else {
            return isClosedChannelException(t.getCause()) || Stream.of(t.getSuppressed()).anyMatch(FileMonitoringTask::isClosedChannelException);
        }
    }

    private static class Watcher implements Runnable {

        private final FileMonitoringController controller;
        private final FilePath workspace;
        private final Handler handler;
        private final TaskListener listener;
        private final @CheckForNull Charset cs;
        private long lastLocation = -1;

        Watcher(FileMonitoringController controller, FilePath workspace, Handler handler, TaskListener listener) {
            LOGGER.log(Level.FINE, "starting " + this, new Throwable());
            this.controller = controller;
            this.workspace = workspace;
            this.handler = handler;
            this.listener = listener;
            cs = FileMonitoringController.transcodingCharset(controller.charset);
            LOGGER.log(Level.FINE, "remote transcoding charset: {0}", cs);
        }

        @Override public void run() {
            try {
                Integer exitStatus = controller.exitStatus(workspace, listener); // check before collecting output, in case the process is just now finishing
                if (lastLocation == -1) {
                    FilePath lastLocationFile = controller.getLastLocationFile(workspace);
                    if (lastLocationFile.exists()) {
                        lastLocation = Long.parseLong(lastLocationFile.readToString());
                        LOGGER.finest(() -> "Loaded lastLocation=" + lastLocation);
                    } else {
                        lastLocation = 0;
                        LOGGER.finest("New watch, lastLocation=0");
                    }
                } else {
                    LOGGER.finest(() -> "Using cached lastLocation=" + lastLocation);
                }
                FilePath logFile = controller.getLogFile(workspace);
                long len = logFile.length();
                if (len > lastLocation) {
                    assert !logFile.isRemote();
                    try (FileChannel ch = FileChannel.open(Paths.get(logFile.getRemote()), StandardOpenOption.READ)) {
                        InputStream locallyEncodedStream = Channels.newInputStream(ch.position(lastLocation));
                        InputStream utf8EncodedStream = cs == null ? locallyEncodedStream : new ReaderInputStream(new InputStreamReader(locallyEncodedStream, cs), StandardCharsets.UTF_8);
                        handler.output(utf8EncodedStream);
                        long newLocation = ch.position();
                        // TODO use AtomicFileWriter or equivalent?
                        controller.getLastLocationFile(workspace).write(Long.toString(newLocation), null);
                        long delta = newLocation - lastLocation;
                        LOGGER.finer(() -> this + " copied " + delta + " bytes from " + logFile);
                        lastLocation = newLocation;
                    }
                }
                if (exitStatus != null) {
                    byte[] output;
                    if (controller.getOutputFile(workspace).exists()) {
                        output = controller.getOutput(workspace);
                    } else {
                        output = null;
                    }
                    LOGGER.fine(() -> this + " exiting with code " + exitStatus);
                    handler.exited(exitStatus, output);
                    controller.cleanup(workspace);
                } else {
                    if (!controller.controlDir(workspace).isDirectory()) {
                        LOGGER.log(Level.WARNING, "giving up on watching nonexistent {0}", controller.controlDir);
                        controller.cleanup(workspace);
                        return;
                    }
                    // Could use an adaptive timeout as in DurableTaskStep.Execution in polling mode,
                    // though less relevant here since there is no network overhead to the check.
                    Timer.get().schedule(this, 100, TimeUnit.MILLISECONDS);
                }
            } catch (Exception x) {
                // note that LOGGER here is going to the agent log, not master log
                if (isClosedChannelException(x)) {
                    LOGGER.warning(() -> this + " giving up on watching " + controller.controlDir);
                } else {
                    LOGGER.log(Level.WARNING, this + " giving up on watching " + controller.controlDir, x);
                }
                // Typically this will have been inside Handler.output, e.g.:
                // hudson.remoting.ChannelClosedException: channel is already closed
                //         at hudson.remoting.Channel.send(Channel.java:667)
                //         at hudson.remoting.ProxyOutputStream.write(ProxyOutputStream.java:143)
                //         at hudson.remoting.RemoteOutputStream.write(RemoteOutputStream.java:110)
                //         at org.apache.commons.io.IOUtils.copyLarge(IOUtils.java:1793)
                //         at org.apache.commons.io.IOUtils.copyLarge(IOUtils.java:1769)
                //         at org.apache.commons.io.IOUtils.copy(IOUtils.java:1744)
                //         at org.jenkinsci.plugins.workflow.steps.durable_task.DurableTaskStep$HandlerImpl.output(DurableTaskStep.java:503)
                //         at org.jenkinsci.plugins.durabletask.FileMonitoringTask$Watcher.run(FileMonitoringTask.java:477)
                // Thus we assume the log sink is hopeless and the Watcher task dies.
                // If and when the agent is reconnected, a new watch call will be made and we will resume streaming.
                // last-location.txt will record the last successfully written block of output;
                // we cannot know reliably how much of the problematic block was actually received by the sink,
                // so we err on the side of possibly duplicating text rather than losing text.
            }
        }

    }

}
