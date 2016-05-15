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
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Windows batch script.
 */
public final class WindowsBatchScript extends FileMonitoringTask {

    /** Number of seconds we will wait for a controller script to be launched before assuming the launch failed. */
    private static /* not final */ int LAUNCH_FAILURE_TIMEOUT = Integer.getInteger(WindowsBatchScript.class.getName() + ".LAUNCH_FAILURE_TIMEOUT", 15);
    private final String script;

    @DataBoundConstructor public WindowsBatchScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            throw new IOException("Batch scripts can only be run on Windows nodes");
        }
        BatchController c = new BatchController(ws);

        /*
          This wrapper script basically only calls the actual script that is supposed to be executed. It redirects all
          the output of the target script to the log file and stores the exit code in a result file.
          The script uses a additional temporary to store the exit code that is moved after writing to is is done.
          Writing the output file directly may cause problems because the check for the exit code may locate the result
          file after it was created but before the code was written in and issue a error because of this.
         */
        final String nl = "\r\n";
        final String identifier = c.getIdentifier();

        c.getWrapperBatchFile(ws).write(
                "CHCP 65001 > NUL" + nl +
                "SETLOCAL" + nl +
                "SET theRealScript=" + escapeForBatch(c.getMainBatchFile(ws)) + nl +
                "SET resultFile=" + escapeForBatch(c.getResultFile(ws)) + nl +
                "SET logFile=" + escapeForBatch(c.getLogFile(ws)) + nl +
                "SET tempResultFile=" + escapeForBatch(c.getTemporaryResultFile(ws)) + nl +
                "SET pidFile=" + escapeForBatch(c.getPidFile(ws)) + nl +
                "TITLE=" + identifier + nl + /* This works regardless if the command windows is actually visible. */
                "FOR /F \"tokens=*\" %%1 IN ('tasklist /NH /FI \"IMAGENAME eq cmd.exe\" /V /FO CSV') DO (" + nl +
                "  ECHO %%1 | FINDSTR /C:" + identifier + " >NUL 2>&1 && (" + nl +
                "    FOR /F \"tokens=2 delims=,\" %%P IN (\"%%1\") DO ( " + nl +
                "      ECHO %%~P > \"%pidFile%\"" + nl +
                "    )" + nl +
                "  )" + nl +
                ")" + nl +
                "TYPE NUL > \"%tempResultFile%\"" + nl +
                "CMD /C \"\"%theRealScript%\"\" > \"%logFile%\" 2>&1" + nl +
                "ECHO %ERRORLEVEL% > \"%tempResultFile%\"" + nl +
                "MOVE \"%tempResultFile%\" \"%resultFile%\" > NUL" + nl +
                "DEL /Q \"%pidFile%\" > NUL 2>&1" + nl +
                "ENDLOCAL" + nl +
                "EXIT 0", "UTF-8");
        c.getMainBatchFile(ws).write(script, "UTF-8");

        /*
         The execution of the wrapper script is done by the START command. This is done so the process runs in a
         additional process that won't be terminated in case the Jenkins master shuts down and the script is executed
         by the master. The command line executed by START is:
            CALL "<wrapper script>"
         The additional CALL is used to avoid the problem of the START command that causes it to fail in case the path
         to the wrapper script contains a space.
         */
        Launcher.ProcStarter ps = launcher.launch()
                                          .cmds("cmd", "/C", "START \"\" /MIN CALL \"" + c.getWrapperBatchFile(ws) + "\"")
                                          .envs(envVars)
                                          .pwd(ws)
                                          .quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+\\\\", "") + "] Running batch script"); // details printed by cmd
        /* Too noisy, and consumes a thread:
        ps.stdout(listener);
        */
        ps.writeStdin().readStdout().readStderr(); // TODO see BourneShellScript
        ps.start();
        c.markStart();

        return c;
    }

    /**
     * Escape the file path for the usage in a batch script.
     *
     * @param file the file path
     * @return the remote address of the file path escaped for the usage in a batch
     */
    private static String escapeForBatch(FilePath file) {
        return file.getRemote().replace("%", "%%");
    }

    private static final class BatchController extends FileMonitoringController {

        private final String identifier;
        private long startTime;
        private int pid;

        private BatchController(FilePath ws) throws IOException, InterruptedException {
            super(ws);

            identifier = UUID.randomUUID().toString();
        }

        FilePath getWrapperBatchFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-wrap.bat");
        }

        FilePath getMainBatchFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-main.bat");
        }

        FilePath getTemporaryResultFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-result.tmp");
        }

        FilePath getPidFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-wrap.pid");
        }

        String getIdentifier() {
            return identifier;
        }

        void markStart() {
            startTime = System.currentTimeMillis();
        }

        private synchronized int getPid(FilePath ws) throws IOException, InterruptedException {
            if (pid == 0) {
                FilePath pidFile = getPidFile(ws);
                if (pidFile.exists()) {
                    try {
                        pid = Integer.parseInt(pidFile.readToString().trim());
                    } catch (NumberFormatException x) {
                        throw new IOException("corrupted content in " + pidFile + ": " + x, x);
                    }
                }
            }
            return pid;
        }

        @Override public Integer exitStatus(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            if (startTime == 0) {
                return null;
            }

            Integer status = super.exitStatus(workspace, launcher);
            if (status != null) {
                return status;
            }
            int pid = getPid(workspace);

            if (pid == 0) {
                if (System.currentTimeMillis() - startTime > 1000 * LAUNCH_FAILURE_TIMEOUT) {
                    /* No PID and the launch time was exceeded. Something went wrong. */
                    return -2;
                }

                /* PID is not known yet. The batch may not have created the file yet. Just wait for it. */
                return null;
            }

            /* Check if the process is still alive. */
            Launcher.ProcStarter ps = launcher.launch()
                                              .cmds("TASKLIST","/NH", "/FI", String.format("PID eq %1$d", pid), "/V", "/FO", "CSV")
                                              .quiet(true);
            ps.writeStdin();
            OutputStream stream = null;
            String resultString = null;
            try {
                stream = new ByteArrayOutputStream();
                ps.stdout(stream);
                ps.join();
                resultString = stream.toString();
            } finally {
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (IOException ignored) {}
            }

            if (resultString.contains(getIdentifier())) {
                /* Found the process. Still running. All good. */
                return null;
            }

            status = super.exitStatus(workspace, launcher);
            if (status == null) {
                /* The process is dead, but there is no result. */
                return -1;
            }
            return status;
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.WindowsBatchScript_windows_batch();
        }

    }

}
