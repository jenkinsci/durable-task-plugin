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
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Windows batch script.
 */
public final class WindowsBatchScript extends FileMonitoringTask {
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
        c.getWrapperBatchFile(ws).write(
                "CHCP 65001 > NUL" + nl +
                "SETLOCAL" + nl +
                "SET theRealScript=" + escapeForBatch(c.getMainBatchFile(ws)) + nl +
                "SET resultFile=" + escapeForBatch(c.getResultFile(ws)) + nl +
                "SET logFile=" + escapeForBatch(c.getLogFile(ws)) + nl +
                "SET tempResultFile=" + escapeForBatch(c.getTemporaryResultFile(ws)) + nl +
                "TYPE NUL > \"%tempResultFile%\"" + nl +
                "CMD /C \"%theRealScript%\" > \"%logFile%\" 2>&1" + nl +
                "ECHO %ERRORLEVEL% > \"%tempResultFile%\"" + nl +
                "MOVE \"%tempResultFile%\" \"%resultFile%\" > NUL" + nl +
                "ENDLOCAL" + nl +
                "EXIT 0", "UTF-8");
        c.getMainBatchFile(ws).write(script, "UTF-8");

        Launcher.ProcStarter ps = launcher.launch()
                                          .cmds("cmd", "/C", "START \"\" /MIN /WAIT \"" + escapeForBatch(c.getWrapperBatchFile(ws)) + '"')
                                          .envs(envVars)
                                          .pwd(ws)
                                          .quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+\\\\", "") + "] Running batch script"); // details printed by cmd
        /* Too noisy, and consumes a thread:
        ps.stdout(listener);
        */
        ps.writeStdin().readStdout().readStderr(); // TODO see BourneShellScript
        ps.start();
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
        private BatchController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        FilePath getWrapperBatchFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-wrap.bat");
        }

        FilePath getMainBatchFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-main.bat");
        }

        FilePath getTemporaryResultFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).createTempFile("jenkins-result", null);
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.WindowsBatchScript_windows_batch();
        }

    }

}
