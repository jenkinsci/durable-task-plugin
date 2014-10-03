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
import hudson.util.IOUtils;
import hudson.util.LogTaskListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A task which forks some external command and then waits for log and status files to be updated/created.
 */
public abstract class FileMonitoringTask extends DurableTask {

    private static final Logger LOGGER = Logger.getLogger(FileMonitoringTask.class.getName());

    private static String id(FilePath workspace) {
        return Util.getDigestOf(workspace.getRemote());
    }

    @Override public final Controller launch(EnvVars env, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException {
        env.put("JENKINS_SERVER_COOKIE", "durable-" + id(workspace)); // ensure getCharacteristicEnvVars does not match, so Launcher.killAll will leave it alone
        return doLaunch(workspace, launcher, listener, env);
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
    protected abstract FileMonitoringController doLaunch(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException;

    protected static class FileMonitoringController extends Controller {
        /**
         * Unique ID among all the {@link Controller}s that share the same workspace.
         */
        private final String id = Util.getDigestOf(UUID.randomUUID().toString()).substring(0,8);

        /**
         * Byte offset in the file that has been reported thus far.
         */
        private long lastLocation;

        public FileMonitoringController(FilePath ws) throws IOException, InterruptedException {
            // can't keep ws reference because Controller is expected to be serializable
            controlDir(ws).mkdirs();
        }

        @Override public final boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException {
            FilePath log = getLogFile(workspace);
            long len = log.length();
            if (len > lastLocation) {
                // TODO more efficient to use RandomAccessFile or similar in a FileCallable.
                // Also more robust to do the writing from inside the callable (wrap sink in RemoteOutputStream).
                InputStream is = log.read();
                try {
                    IOUtils.skip(is, lastLocation);
                    Util.copyStream(is, sink);
                } finally {
                    is.close();
                }
                lastLocation = len;
                return true;
            } else {
                return false;
            }
        }

        @Override public final Integer exitStatus(FilePath workspace) throws IOException, InterruptedException {
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

        @Override public final void stop(FilePath workspace) throws IOException, InterruptedException {
            workspace.createLauncher(new LogTaskListener(LOGGER, Level.FINE)).kill(Collections.singletonMap("JENKINS_SERVER_COOKIE", "durable-" + id(workspace)));
        }

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            controlDir(workspace).deleteRecursive();
        }

        /**
         * Directory in which this controller can place files.
         */
        public FilePath controlDir(FilePath ws) {
            return ws.child("."+id);
        }

        /**
         * File in which the exit code of the process should be reported.
         */
        public FilePath getResultFile(FilePath workspace) {
            return controlDir(workspace).child("jenkins-result.txt");
        }

        /**
         * File in which the stdout/stderr
         */
        public FilePath getLogFile(FilePath workspace) {
            return controlDir(workspace).child("jenkins-log.txt");
        }

        private static final long serialVersionUID = 1L;
    }

}
