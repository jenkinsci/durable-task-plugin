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
    private boolean capturingOutput;

    @DataBoundConstructor public WindowsBatchScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            throw new IOException("Batch scripts can only be run on Windows nodes");
        }
        BatchController c = new BatchController(ws);

        String cmd;
        if (capturingOutput) {
            cmd = String.format("@echo off \r\ncmd /c \"\"%s\"\" > \"%s\" 2> \"%s\"\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmd = String.format("@echo off \r\ncmd /c \"\"%s\"\" > \"%s\" 2>&1\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
        c.getBatchFile1(ws).write(cmd, "UTF-8");
        c.getBatchFile2(ws).write(script, "UTF-8");

        Launcher.ProcStarter ps = launcher.launch().cmds("cmd", "/c", "\"\"" + c.getBatchFile1(ws) + "\"\"").envs(escape(envVars)).pwd(ws).quiet(true);
        /* Too noisy, and consumes a thread:
        ps.stdout(listener);
        */
        ps.readStdout().readStderr(); // TODO see BourneShellScript
        ps.start();
        return c;
    }

    private static String quote(FilePath f) {
        return f.getRemote().replace("%", "%%");
    }

    private static final class BatchController extends FileMonitoringController {
        private BatchController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getBatchFile1(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-wrap.bat");
        }

        public FilePath getBatchFile2(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("jenkins-main.bat");
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.WindowsBatchScript_windows_batch();
        }

    }

}
