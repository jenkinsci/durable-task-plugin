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
import hudson.Util;
import hudson.model.TaskListener;
import java.io.IOException;

import hudson.tasks.Shell;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Bourne shell script on a Unix node using {@code nohup}.
 */
public final class BourneShellScript extends FileMonitoringTask {

    private final @Nonnull String script;

    @DataBoundConstructor public BourneShellScript(String script) {
        this.script = Util.fixNull(script);
    }
    
    public String getScript() {
        return script;
    }

    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        if (script.isEmpty()) {
            listener.getLogger().println("Warning: was asked to run an empty script");
        }

        ShellController c = new ShellController(ws);

        FilePath shf = c.getScriptFile(ws);

        String s = script;
        if (!s.startsWith("#!")) {
            String defaultShell = Jenkins.getInstance().getInjector().getInstance(Shell.DescriptorImpl.class).getShellOrDefault(ws.getChannel());
            s = "#!"+defaultShell+" -xe\n" + s;
        }
        shf.write(s, "UTF-8");
        shf.chmod(0755);

        String cmd = String.format("echo $$ > '%s'; '%s' > '%s' 2>&1; echo $? > '%s'",
                c.pidFile(ws),
                shf,
                c.getLogFile(ws),
                c.getResultFile(ws)
                )./* escape against EnvVars jobEnv in LocalLauncher.launch */replace("$", "$$");

        Launcher.ProcStarter ps = launcher.launch().cmds("nohup", "sh", "-c", cmd).envs(envVars).pwd(ws);
        try {
            Launcher.ProcStarter.class.getMethod("quiet", boolean.class).invoke(ps, true); // TODO 1.576+ remove reflection
            listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+/", "") + "] Running shell script"); // -x will give details
        } catch (NoSuchMethodException x) {
            // older Jenkins, OK
        } catch (Exception x) { // ?
            x.printStackTrace(listener.getLogger());
        }
        /* Uncomment for diagnosis (and comment following line) in case wrapper script fails. Otherwise skip since it consumes a thread:
        ps.stdout(listener);
        */
        ps.readStdout().readStderr(); // TODO RemoteLauncher.launch fails to check ps.stdout == NULL_OUTPUT_STREAM, so it creates a useless thread even if you never called stdout(…)
        ps.start();
        return c;
    }

    /*package*/ static final class ShellController extends FileMonitoringController {

        private int pid;

        private ShellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getScriptFile(FilePath ws) {
            return controlDir(ws).child("script.sh");
        }

        FilePath pidFile(FilePath ws) {
            return controlDir(ws).child("pid");
        }

        private synchronized int pid(FilePath ws) throws IOException, InterruptedException {
            if (pid == 0) {
                FilePath pidFile = pidFile(ws);
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

        @Override public Integer exitStatus(FilePath workspace) throws IOException, InterruptedException {
            Integer status = super.exitStatus(workspace);
            if (status != null) {
                return status;
            }
            int _pid = pid(workspace);
            if (_pid > 0 && !ProcessLiveness.isAlive(workspace.getChannel(), _pid)) {
                // it looks like the process has disappeared. one last check to make sure it's not a result of a race condition,
                // then if we still don't have the exit code, use fake exit code to distinguish from 0 (success) and 1+ (observed failure)
                status = super.exitStatus(workspace);
                if (status == null) {
                    status = -1;
                }
                return status;
            }
            return null;
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.BourneShellScript_bourne_shell();
        }

    }

}
