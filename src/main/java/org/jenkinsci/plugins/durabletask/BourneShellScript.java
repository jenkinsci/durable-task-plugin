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
import hudson.LauncherDecorator;
import hudson.Platform;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.tasks.Shell;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Bourne shell script on a Unix node using {@code nohup}.
 */
public final class BourneShellScript extends FileMonitoringTask {

    private static enum OsType {DARWIN, UNIX, WINDOWS}

    /** Number of times we will show launch diagnostics in a newly encountered workspace before going mute to save resources. */
    private static /* not final */ int NOVEL_WORKSPACE_DIAGNOSTICS_COUNT = Integer.getInteger(BourneShellScript.class.getName() + ".NOVEL_WORKSPACE_DIAGNOSTICS_COUNT", 10);
    /** Number of seconds we will wait for a controller script to be launched before assuming the launch failed. */
    private static /* not final */ int LAUNCH_FAILURE_TIMEOUT = Integer.getInteger(BourneShellScript.class.getName() + ".LAUNCH_FAILURE_TIMEOUT", 15);

    private final @Nonnull String script;
    private boolean capturingOutput;

    @DataBoundConstructor public BourneShellScript(String script) {
        this.script = Util.fixNull(script);
    }
    
    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    /**
     * Set of workspaces which we have already run a process in.
     * Copying output from the controller process consumes a Java thread, so we want to avoid it generally.
     * But we do it the first few times we run a process in a new workspace, to assist in diagnosis.
     * (For example, if we are unable to write to it due to permissions, we want to see that error message.)
     * Ideally we would display output the first time a given {@link Launcher} was used in that workspace,
     * but this seems impractical since {@link LauncherDecorator#decorate} may be called anew for each process,
     * and forcing the resulting {@link Launcher}s to implement {@link Launcher#equals} seems onerous.
     */
    private static final Map<FilePath,Integer> encounteredPaths = new WeakHashMap<FilePath,Integer>();

    @Override protected FileMonitoringController launchWithCookie(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        if (script.isEmpty()) {
            listener.getLogger().println("Warning: was asked to run an empty script");
        }

        ShellController c = new ShellController(ws);

        FilePath shf = c.getScriptFile(ws);

        String s = script, scriptPath;
        final Jenkins jenkins = Jenkins.getInstance();
        if (!s.startsWith("#!") && jenkins != null) {
            String defaultShell = jenkins.getInjector().getInstance(Shell.DescriptorImpl.class).getShellOrDefault(ws.getChannel());
            s = "#!"+defaultShell+" -xe\n" + s;
        }
        shf.write(s, "UTF-8");
        shf.chmod(0755);

        scriptPath = shf.getRemote();
        List<String> args = new ArrayList<String>();

        OsType os = ws.act(new getOsType());

        if (os != OsType.DARWIN) { // JENKINS-25848
            args.add("nohup");
        }
        if (os == OsType.WINDOWS) { // JENKINS-40255
            scriptPath= scriptPath.replace("\\", "/"); // cygwin sh understands mixed path  (ie : "c:/jenkins/workspace/script.sh" )
        }

        envVars.put(cookieVariable, "please-do-not-kill-me");
        // The temporary variable is to ensure JENKINS_SERVER_COOKIE=durable-… does not appear even in argv[], lest it be confused with the environment.
        String cmd;
        if (capturingOutput) {
            cmd = String.format("echo $$ > '%s'; jsc=%s; %s=$jsc '%s' > '%s' 2> '%s'; echo $? > '%s'",
                c.pidFile(ws),
                cookieValue,
                cookieVariable,
                scriptPath,
                c.getOutputFile(ws),
                c.getLogFile(ws),
                c.getResultFile(ws));
        } else {
            cmd = String.format("echo $$ > '%s'; jsc=%s; %s=$jsc '%s' > '%s' 2>&1; echo $? > '%s'",
                c.pidFile(ws),
                cookieValue,
                cookieVariable,
                scriptPath,
                c.getLogFile(ws),
                c.getResultFile(ws));
        }
        cmd = cmd.replace("$", "$$"); // escape against EnvVars jobEnv in LocalLauncher.launch

        args.addAll(Arrays.asList("sh", "-c", cmd));
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+/", "") + "] Running shell script"); // -x will give details
        boolean novel;
        synchronized (encounteredPaths) {
            Integer cnt = encounteredPaths.get(ws);
            if (cnt == null) {
                cnt = 0;
            }
            novel = cnt < NOVEL_WORKSPACE_DIAGNOSTICS_COUNT;
            encounteredPaths.put(ws, cnt + 1);
        }
        if (novel) {
            // First time in this combination. Display any output from the wrapper script for diagnosis.
            ps.stdout(listener);
        } else {
            // Second or subsequent time. Suppress output to save a thread.
            ps.readStdout().readStderr(); // TODO RemoteLauncher.launch fails to check ps.stdout == NULL_OUTPUT_STREAM, so it creates a useless thread even if you never called stdout(…)
        }
        ps.start();
        return c;
    }

    /*package*/ static final class ShellController extends FileMonitoringController {

        private int pid;
        private final long startTime = System.currentTimeMillis();

        private ShellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getScriptFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("script.sh");
        }

        FilePath pidFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("pid");
        }

        private synchronized int pid(FilePath ws) throws IOException, InterruptedException {
            if (pid == 0) {
                FilePath pidFile = pidFile(ws);
                if (pidFile.exists()) {
                    for ( int tries = 30; tries > 0; tries-- ) {
                        String _pid = "";
                        try {
                            _pid = pidFile.readToString().trim();
                            pid = Integer.parseInt(_pid);
                        } catch (NumberFormatException x) {
                            if ( tries == 1 ) {
                                throw new IOException("corrupted content in " + pidFile + ": " + x, x);
                            }
                            if ( tries != 1 && _pid.length() == 0) {
                                // potential timing issue where pid file created but not yet populated
                                Thread.sleep(100);
                            }
                        }
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
            } else if (_pid == 0 && /* compatibility */ startTime > 0 && System.currentTimeMillis() - startTime > 1000 * LAUNCH_FAILURE_TIMEOUT) {
                return -2; // apparently never started
            }
            return null;
        }

        @Override public String getDiagnostics(FilePath workspace, Launcher launcher) throws IOException, InterruptedException {
            return super.getDiagnostics(workspace, launcher) + " (pid: " + pid + ")";
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.BourneShellScript_bourne_shell();
        }

    }

    private static final class getOsType extends MasterToSlaveCallable<OsType,RuntimeException> {
        @Override public OsType call() throws RuntimeException {
            if (Platform.isDarwin()) {
              return OsType.DARWIN;
            } else if (Platform.current() == Platform.WINDOWS) {
              return OsType.WINDOWS;
            } else {
              return OsType.UNIX; // Default Value
            }
        }
    }
}
