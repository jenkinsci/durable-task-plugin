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

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.NonNull;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import hudson.util.LineEndingConversion;

/**
 * Runs a Windows batch script.
 */
public final class WindowsBatchScript extends FileMonitoringTask {
    @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to control usage of binary or shell wrapper
    @Restricted(NoExternalUse.class)
    public static boolean USE_BINARY_WRAPPER = Boolean.getBoolean(WindowsBatchScript.class.getName() + ".USE_BINARY_WRAPPER");

    private final String script;
    private boolean capturingOutput;
    private static final Logger LOGGER = Logger.getLogger(WindowsBatchScript.class.getName());
    private static final String LAUNCH_DIAGNOSTICS_PROP = WindowsBatchScript.class.getName() + ".LAUNCH_DIAGNOSTICS";

    /**
     * Seconds between heartbeat checks, where we check to see if
     * {@code jenkins-log.txt} is still being modified.
     */
    static int HEARTBEAT_CHECK_INTERVAL = Integer.getInteger(BourneShellScript.class.getName() + ".HEARTBEAT_CHECK_INTERVAL", 300);

    /**
     * Minimum timestamp difference on {@code jenkins-log.txt} that is
     * considered an actual modification. Theoretically could be zero (if
     * {@code <} became {@code <=}, else infinitesimal positive) but on some
     * platforms file timestamps are not that precise.
     */
    @SuppressWarnings("FieldMayBeFinal")
    private static int HEARTBEAT_MINIMUM_DELTA = Integer.getInteger(BourneShellScript.class.getName() + ".HEARTBEAT_MINIMUM_DELTA", 2);


    /**
     * Used by the binary wrapper, this enables the debug flag.
     */
    @SuppressWarnings("FieldMayBeFinal")
    // TODO use SystemProperties if and when unrestricted
    private static boolean LAUNCH_DIAGNOSTICS = Boolean.getBoolean(LAUNCH_DIAGNOSTICS_PROP);

    @DataBoundConstructor public WindowsBatchScript(String script) {
        this.script = LineEndingConversion.convertEOL(script, LineEndingConversion.EOLType.Windows);
    }
    
    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            throw new IOException("Batch scripts can only be run on Windows nodes");
        }

        BatchController c = new BatchController(ws, envVars.get(COOKIE));

        List<String> launcherCmd = null;
        FilePath binary;
        if (USE_BINARY_WRAPPER && (binary = requestBinary(ws, c)) != null) {
            launcherCmd = binaryLauncherCmd(c, ws, binary.getRemote(), c.getBatchFile2(ws).getRemote());
            c.getBatchFile2(ws).write(script, "UTF-8");
        }
        if (launcherCmd == null) {
            launcherCmd = scriptLauncherCmd(c, ws);
        }

        LOGGER.log(Level.FINE, "launching {0}", launcherCmd);
        Launcher.ProcStarter ps = launcher.launch().cmds(launcherCmd).envs(escape(envVars)).pwd(ws).quiet(true);

        /* Too noisy, and consumes a thread:
        ps.stdout(listener);
        */
        ps.readStdout().readStderr(); // TODO see BourneShellScript
        Proc p = ps.start();
        c.registerForCleanup(p.getStdout());
        c.registerForCleanup(p.getStderr());

        return c;
    }

    @NonNull
    private List<String> binaryLauncherCmd(BatchController c, FilePath ws, String binaryPath, String scriptPath) throws IOException, InterruptedException {
        String logFile = c.getLogFile(ws).getRemote();
        String resultFile = c.getResultFile(ws).getRemote();
        String outputFile = c.getOutputFile(ws).getRemote();
        String controlDirPath = c.controlDir(ws).getRemote();

        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("-daemon");
        cmd.add("-executable=cmd");
        cmd.add("-args=/C call \\\"" + scriptPath + "\\\"");
        cmd.add("-controldir=" + controlDirPath);
        cmd.add("-result=" + resultFile);
        cmd.add("-log=" + logFile);
        if (capturingOutput) {
            cmd.add("-output=" + outputFile);
        }
        if (LAUNCH_DIAGNOSTICS) {
            cmd.add("-debug");
        }
        return cmd;
    }

    @NonNull
    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    private List<String> scriptLauncherCmd(BatchController c, FilePath ws) throws IOException, InterruptedException {

        String cmdString;
        if (capturingOutput) {
            cmdString = String.format("@echo off \r\ncmd /c call \"%s\" > \"%s\" 2> \"%s\"\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmdString = String.format("@echo off \r\ncmd /c call \"%s\" > \"%s\" 2>&1\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
        c.getBatchFile1(ws).write(cmdString, "UTF-8");
        c.getBatchFile2(ws).write(script, "UTF-8");

        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList("cmd", "/c", "\"\"" + c.getBatchFile1(ws).getRemote() + "\"\""));
        return cmd;
    }

    private static String quote(FilePath f) {
        return f.getRemote().replace("%", "%%");
    }

    private static final class BatchController extends FileMonitoringController {

        /** Last time we checked the timestamp, in nanoseconds on the master. */
        private transient long lastCheck;
        /** Last-observed modification time of {@link FileMonitoringTask.FileMonitoringController#getLogFile(FilePath)} on remote computer, in milliseconds. */
        private transient long checkedTimestamp;

        private BatchController(FilePath ws, @NonNull String cookieValue) throws IOException, InterruptedException {
            super(ws, cookieValue);
        }

        public FilePath getBatchFile1(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-wrap.bat");
        }

        public FilePath getBatchFile2(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-main.bat");
        }

        /** Only here for compatibility. */
        private FilePath pidFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("pid");
        }


        @Override protected Integer exitStatus(FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
            Integer status;

                status = super.exitStatus(workspace, listener);

            if (status != null) {
                LOGGER.log(Level.FINE, "found exit code {0} in {1}", new Object[] {status, controlDir});
                return status;
            }
            long now = System.nanoTime();
            if (lastCheck == 0) {
                LOGGER.log(Level.FINE, "starting check in {0}", controlDir);
                lastCheck = now;
            } else if (now > lastCheck + TimeUnit.SECONDS.toNanos(HEARTBEAT_CHECK_INTERVAL)) {
                lastCheck = now;
                long currentTimestamp = getLogFile(workspace).lastModified();
                if (currentTimestamp == 0) {
                    listener.getLogger().println("process apparently never started in " + controlDir);
                    if (!LAUNCH_DIAGNOSTICS) {
                        listener.getLogger().println("(running Jenkins temporarily with -D" + LAUNCH_DIAGNOSTICS_PROP + "=true might make the problem clearer)");
                    }
                    return recordExitStatus(workspace, -2);
                } else if (checkedTimestamp > 0) {
                    if (currentTimestamp < checkedTimestamp) {
                        listener.getLogger().println("apparent clock skew in " + controlDir);
                    } else if (currentTimestamp < checkedTimestamp + TimeUnit.SECONDS.toMillis(HEARTBEAT_MINIMUM_DELTA)) {
                            //FilePath pidFile = pidFile(workspace);
                          //  if (pidFile.exists()) {
                          //      listener.getLogger().println("still have " + pidFile + " so heartbeat checks unreliable; process may or may not be alive");
                          //  } else {
                                listener.getLogger().println("wrapper script does not seem to be touching the log file in " + controlDir);
                                listener.getLogger().println("(JENKINS-48300: if on an extremely laggy filesystem, consider -Dorg.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL=86400)");
                                return recordExitStatus(workspace, -1);
                           // }

                    }
                } else {
                    LOGGER.log(Level.FINE, "seeing recent log file modifications in {0}", controlDir);
                }
                checkedTimestamp = currentTimestamp;
            }
            return null;
        }

        private int recordExitStatus(FilePath workspace, int code) throws IOException, InterruptedException {
            getResultFile(workspace).write(Integer.toString(code), null);
            return code;
        }

        private static final long serialVersionUID = 1L;
    }


    private static final long serialVersionUID = 1L;
    

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.WindowsBatchScript_windows_batch();
        }

    }

}
