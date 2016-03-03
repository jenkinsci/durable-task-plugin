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

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.RemoteOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.WorkspaceList;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.MasterToSlaveFileCallable;

/**
 * A task which forks some external command and then waits for log and status files to be updated/created.
 */
public abstract class FileMonitoringTask extends DurableTask {

    private static final Logger LOGGER = Logger.getLogger(FileMonitoringTask.class.getName());

    private static final String COOKIE = "JENKINS_SERVER_COOKIE";

    private static String cookieFor(FilePath workspace) {
        return "durable-" + Util.getDigestOf(workspace.getRemote());
    }

    @Override public final Controller launch(EnvVars env, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
        return launchWithCookie(workspace, launcher, listener, env, COOKIE, cookieFor(workspace));
    }

    protected FileMonitoringController launchWithCookie(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        envVars.put(cookieVariable, cookieValue); // ensure getCharacteristicEnvVars does not match, so Launcher.killAll will leave it alone
        return doLaunch(workspace, launcher, listener, envVars);
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

    protected static class FileMonitoringController extends Controller {

        /** Absolute path of {@link #controlDir()}. */
        private String controlDir;

        /**
         * @deprecated used only in pre-1.8
         */
        private String id;

        /**
         * Byte offset in the file that has been reported thus far.
         */
        private long lastLocation;

        protected FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            ws.mkdirs();
            FilePath cd = tempDir(ws).child("durable-" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8));
            cd.mkdirs();
            controlDir = cd.getRemote();
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            FilePath log = getLogFile(workspace);
            Long newLocation = log.act(new WriteLog(lastLocation, new RemoteOutputStream(sink)));
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
            WriteLog(long lastLocation, OutputStream sink) {
                this.lastLocation = lastLocation;
                this.sink = sink;
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
                        sink.write(buf);
                    } finally {
                        raf.close();
                    }
                    return len;
                } else {
                    return null;
                }
            }
        }

        // TODO would be more efficient to allow API to consolidate writeLog with exitStatus (save an RPC call)
        @Override public Integer exitStatus(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            FilePath status = getResultFile(workspace);
            if (status.exists()) {
                try {
                    return Integer.parseInt(status.readToString().trim());
                } catch (NumberFormatException x) {
                    throw new IOException("corrupted content in " + status + ": " + x, x);
                }
            } else {
                return null;
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

        // TODO https://github.com/jenkinsci/jenkins/pull/2066
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
         * File in which the stdout/stderr
         */
        public FilePath getLogFile(FilePath workspace) throws IOException, InterruptedException {
            return controlDir(workspace).child("jenkins-log.txt");
        }

        private static final long serialVersionUID = 1L;
    }

}
