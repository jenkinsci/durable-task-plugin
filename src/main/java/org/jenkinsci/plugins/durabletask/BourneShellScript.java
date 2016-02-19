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
import hudson.Platform;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.tasks.Shell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
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

    @Override protected FileMonitoringController launchWithCookie(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        if (script.isEmpty()) {
            listener.getLogger().println("Warning: was asked to run an empty script");
        }

        ShellController c = new ShellController(ws);

        FilePath shf = c.getScriptFile(ws);

        String s = script;
        final Jenkins jenkins = Jenkins.getInstance();
        if (!s.startsWith("#!") && jenkins != null) {
            String defaultShell = jenkins.getInjector().getInstance(Shell.DescriptorImpl.class).getShellOrDefault(ws.getChannel());
            s = "#!"+defaultShell+" -xe\n" + s;
        }
        shf.write(s, "UTF-8");
        shf.chmod(0755);

        envVars.put(cookieVariable, "please-do-not-kill-me");
        // The temporary variable is to ensure JENKINS_SERVER_COOKIE=durable-… does not appear even in argv[], lest it be confused with the environment.
        String cmd = String.format("echo $$ > '%s'; jsc=%s; %s=$jsc '%s' > '%s' 2>&1; echo $? > '%s'",
                c.pidFile(ws),
                cookieValue,
                cookieVariable,
                shf,
                c.getLogFile(ws),
                c.getResultFile(ws)
                )./* escape against EnvVars jobEnv in LocalLauncher.launch */replace("$", "$$");

        List<String> args = new ArrayList<String>();
        if (!ws.act(new DarwinCheck())) { // JENKINS-25848
            args.add("nohup");
        }
        args.addAll(Arrays.asList("sh", "-c", cmd));
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(envVars).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+/", "") + "] Running shell script"); // -x will give details
        /* Uncomment for diagnosis (and comment following line) in case wrapper script fails. Otherwise skip since it consumes a thread:
        ps.stdout(listener);
        */
        ps.readStdout().readStderr(); // TODO RemoteLauncher.launch fails to check ps.stdout == NULL_OUTPUT_STREAM, so it creates a useless thread even if you never called stdout(…)
        ps.start();
        return c;
    }

    /*package*/ static final class ShellController extends FileMonitoringController {

        private int pid;
        private final long startTime = System.currentTimeMillis();

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

        @Override public Integer exitStatus(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            Integer status = super.exitStatus(workspace, launcher);
            if (status != null) {
                return status;
            }
            int _pid = pid(workspace);
            if (_pid > 0 && !ProcessLiveness.isAlive(workspace.getChannel(), _pid, launcher)) {
                // it looks like the process has disappeared. one last check to make sure it's not a result of a race condition,
                // then if we still don't have the exit code, use fake exit code to distinguish from 0 (success) and 1+ (observed failure)
                // TODO would be better to have exitStatus accept a TaskListener so we could print an informative message
                status = super.exitStatus(workspace, launcher);
                if (status == null) {
                    status = -1;
                }
                return status;
            } else if (_pid == 0 && /* compatibility */ startTime > 0 && System.currentTimeMillis() - startTime > /* 15s */15000) {
                return -2; // apparently never started
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

    private static final class DarwinCheck extends MasterToSlaveCallable<Boolean,RuntimeException> {
        @Override public Boolean call() throws RuntimeException {
            return Platform.isDarwin();
        }
    }

}
