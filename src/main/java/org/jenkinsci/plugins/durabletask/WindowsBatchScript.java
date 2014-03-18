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

    private static final String BATCH_SCRIPT_FILE_1 = ".jenkins-wrap.bat";
    private static final String BATCH_SCRIPT_FILE_2 = ".jenkins-main.bat";

    private final String script;

    @DataBoundConstructor public WindowsBatchScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @Override protected FileMonitoringController doLaunch(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (launcher.isUnix()) {
            throw new IOException("Batch scripts can only be run on Windows nodes");
        }
        workspace.child(BATCH_SCRIPT_FILE_1).write("call " + BATCH_SCRIPT_FILE_2 + " >" + LOG_FILE + " 2>&1\r\necho %ERRORLEVEL% >" + RESULT_FILE + "\r\n", "UTF-8");
        workspace.child(BATCH_SCRIPT_FILE_2).write(script, "UTF-8");
        launcher.launch().cmds("cmd", "/c", workspace.child(BATCH_SCRIPT_FILE_1).getRemote()).envs(envVars).pwd(workspace).start();
        return new BatchController();
    }

    private static final class BatchController extends FileMonitoringController {

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            super.cleanup(workspace);
            workspace.child(BATCH_SCRIPT_FILE_1).delete();
            workspace.child(BATCH_SCRIPT_FILE_2).delete();
        }

    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.WindowsBatchScript_windows_batch();
        }

    }

}
