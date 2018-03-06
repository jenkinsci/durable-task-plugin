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
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import hudson.util.StreamTaskListener;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

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

    protected static class FileMonitoringController extends Controller {

        /** Absolute path of {@link #controlDir(FilePath)}. */
        String controlDir;

        /**
         * @deprecated used only in pre-1.8
         */
        private String id;

        /**
         * Byte offset in the file that has been reported thus far.
         */
        private long lastLocation;

        /** @see FileMonitoringTask#charset */
        private @CheckForNull String charset;

        protected FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            ws.mkdirs();
            FilePath cd = tempDir(ws).child("durable-" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8));
            cd.mkdirs();
            controlDir = cd.getRemote();
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            FilePath log = getLogFile(workspace);
            Long newLocation = log.act(new WriteLog(lastLocation, new RemoteOutputStream(sink), charset));
            if (newLocation != null) {
                LOGGER.log(Level.FINE, "copied {0} bytes from {1}", new Object[] {newLocation - lastLocation, log});
                lastLocation = newLocation;
                return true;
            } else {
                return false;
            }
        }
        private static class WriteLog extends MasterToSlaveFileCallable<Long> {
            private final long lastLocation;
            private final OutputStream sink;
            private final @CheckForNull String charset;
            WriteLog(long lastLocation, OutputStream sink, String charset) {
                this.lastLocation = lastLocation;
                this.sink = sink;
                this.charset = charset;
            }
            @Override public Long invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
                long len = f.length();
                if (len > lastLocation) {
                    RandomAccessFile raf = new RandomAccessFile(f, "r");
                    try {
                        raf.seek(lastLocation);
                        long toRead = len - lastLocation;
                        if (toRead > Integer.MAX_VALUE) { // >2Gb of output at once is unlikely
                            throw new IOException("large reads not yet implemented");
                        }
                        // TODO is this efficient for large amounts of output? Would it be better to stream data, or return a byte[] from the callable?
                        byte[] buf = new byte[(int) toRead];
                        raf.readFully(buf);
                        ByteBuffer transcoded = maybeTranscode(buf, charset);
                        if (transcoded == null) {
                            sink.write(buf);
                        } else {
                            Channels.newChannel(sink).write(transcoded);
                        }
                    } finally {
                        raf.close();
                    }
                    return len;
                } else {
                    return null;
                }
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
            FilePath status = getResultFile(workspace);
            return status.act(STATUS_CHECK_INSTANCE);
        }

        /**
         * Transcode process output to UTF-8 if necessary.
         * @param data output presumed to be in local encoding
         * @param charset a particular encoding name, or the empty string for the system default encoding, or null to skip transcoding
         * @return a buffer of UTF-8 encoded data ({@link CodingErrorAction#REPLACE} is used),
         *         or null if not performing transcoding because it was not requested or the data was already thought to be in UTF-8
         */
        private static @CheckForNull ByteBuffer maybeTranscode(@Nonnull byte[] data, @CheckForNull String charset) {
            if (charset == null) { // no transcoding requested, do raw copy and YMMV
                return null;
            } else {
                Charset cs = charset.equals(SYSTEM_DEFAULT_CHARSET) ? Charset.defaultCharset() : Charset.forName(charset);
                if (cs.equals(StandardCharsets.UTF_8)) { // transcoding unnecessary as output was already UTF-8
                    return null;
                } else { // decode output in specified charset and reëncode in UTF-8
                    return StandardCharsets.UTF_8.encode(cs.decode(ByteBuffer.wrap(data)));
                }
            }
        }

        @Override public byte[] getOutput(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
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

        private static final long serialVersionUID = 1L;
    }

}
