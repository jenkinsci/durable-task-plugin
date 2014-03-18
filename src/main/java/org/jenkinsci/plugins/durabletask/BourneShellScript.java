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
 * Runs a Bourne shell script on a Unix node using {@code nohup}.
 */
public final class BourneShellScript extends FileMonitoringTask {

    private static final String BOURNE_SCRIPT_FILE = ".jenkins-script.sh";

    private final String script;

    @DataBoundConstructor public BourneShellScript(String script) {
        this.script = script;
    }
    
    public String getScript() {
        return script;
    }

    @Override protected FileMonitoringController doLaunch(FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (!launcher.isUnix()) {
            throw new IOException("Bourne shell scripts can only be run on Unix nodes");
        }
        workspace.child(BOURNE_SCRIPT_FILE).write(script, "UTF-8");
        StringBuilder shell = new StringBuilder("sh '").append(workspace).append('/').append(BOURNE_SCRIPT_FILE).append("' >'").append(workspace).append('/').append(LOG_FILE).append("' 2>&1; ");
        shell.append("echo $? >'").append(workspace).append('/').append(RESULT_FILE).append('\'');
        launcher.launch().cmds("nohup", "sh", "-c", shell.toString()).envs(envVars).pwd(workspace).start();
        return new ShellController();
    }

    private static final class ShellController extends FileMonitoringController {

        @Override public void cleanup(FilePath workspace) throws IOException, InterruptedException {
            super.cleanup(workspace);
            workspace.child(BOURNE_SCRIPT_FILE).delete();
        }

    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.BourneShellScript_bourne_shell();
        }

    }

}
