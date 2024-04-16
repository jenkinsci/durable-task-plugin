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
import hudson.PluginWrapper;
import hudson.Proc;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.Shell;
import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import edu.umd.cs.findbugs.annotations.NonNull;

import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.durabletask.AgentInfo.OsType;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import jenkins.MasterToSlaveFileCallable;

/**
 * Runs a Bourne shell script on a Unix node using {@code nohup}.
 */
public final class BourneShellScript extends FileMonitoringTask {

    @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to control usage of binary or shell wrapper
    @Restricted(NoExternalUse.class)
    public static boolean USE_BINARY_WRAPPER = Boolean.getBoolean(BourneShellScript.class.getName() + ".USE_BINARY_WRAPPER");

    private static final Logger LOGGER = Logger.getLogger(BourneShellScript.class.getName());

    private static final String SYSTEM_DEFAULT_CHARSET = "SYSTEM_DEFAULT";

    private static final String LAUNCH_DIAGNOSTICS_PROP = BourneShellScript.class.getName() + ".LAUNCH_DIAGNOSTICS";

    /**
     * Whether to stream stdio from the wrapper script, which should normally not print any.
     * Copying output from the controller process consumes a Java thread, so we want to avoid it generally.
     * If requested, we can do this to assist in diagnosis.
     * (For example, if we are unable to write to a workspace due to permissions,
     * we would want to see that error message.)
     *
     * For the binary wrapper, this enables the debug flag.
     */
    @SuppressWarnings("FieldMayBeFinal")
    // TODO use SystemProperties if and when unrestricted
    private static boolean LAUNCH_DIAGNOSTICS = Boolean.getBoolean(LAUNCH_DIAGNOSTICS_PROP);

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

    private final @NonNull String script;
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

    @Override protected FileMonitoringController launchWithCookie(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars, String cookieVariable, String cookieValue) throws IOException, InterruptedException {
        if (script.isEmpty()) {
            listener.getLogger().println("Warning: was asked to run an empty script");
        }

        FilePath nodeRoot = getNodeRoot(ws);
        AgentInfo agentInfo = getAgentInfo(nodeRoot);

        OsType os = agentInfo.getOs();
        String scriptEncodingCharset = "UTF-8";
        String jenkinsResultTxtEncoding = null;
        if(os == OsType.ZOS) {
            Charset zOSSystemEncodingCharset = Charset.forName(ws.act(new getIBMzOsEncoding()));
            if(SYSTEM_DEFAULT_CHARSET.equals(getCharset())) {
                // Setting default charset to IBM z/OS default EBCDIC charset on z/OS if no encoding specified on sh step
                charset(zOSSystemEncodingCharset);
            }
            scriptEncodingCharset = zOSSystemEncodingCharset.name();
            jenkinsResultTxtEncoding = zOSSystemEncodingCharset.name();
        }

        ShellController c = new ShellController(ws,(os == OsType.ZOS), cookieValue, jenkinsResultTxtEncoding);
        FilePath shf = c.getScriptFile(ws);

        // JENKINS-70874: if a new process is forked during this call, the writeable file handle will be copied and leading to the "Text file busy" issue
        // when executing the script.
        shf.write(script, scriptEncodingCharset);

        String shell = null;
        if (!script.startsWith("#!")) {
            shell = Jenkins.get().getDescriptorByType(Shell.DescriptorImpl.class).getShell();
            if (shell == null) {
                // Do not use getShellOrDefault, as that assumes that the filesystem layout of the agent matches that seen from a possibly decorated launcher.
                shell = "sh";
            }
        } else {
            shf.chmod(0755);
        }

        String scriptPath = shf.getRemote();

        // The temporary variable is to ensure JENKINS_SERVER_COOKIE=durable-… does not appear even in argv[], lest it be confused with the environment.
        envVars.put(cookieVariable, "please-do-not-kill-me");

        List<String> launcherCmd = null;
        FilePath binary;
        if (USE_BINARY_WRAPPER && (binary = requestBinary(nodeRoot, agentInfo, ws, c)) != null) {
            launcherCmd = binaryLauncherCmd(c, ws, shell, binary.getRemote(), scriptPath, cookieValue, cookieVariable);
        }
        if (launcherCmd == null) {
            launcherCmd = scriptLauncherCmd(c, ws, shell, os, scriptPath, cookieValue, cookieVariable);
        }

        LOGGER.log(Level.FINE, "launching {0}", launcherCmd);
        Launcher.ProcStarter ps = launcher.launch().cmds(launcherCmd).envs(escape(envVars)).pwd(ws).quiet(true);
        if (LAUNCH_DIAGNOSTICS) {
            ps.stdout(listener);
            ps.start();
        } else {
            ps.readStdout().readStderr(); // TODO RemoteLauncher.launch fails to check ps.stdout == NULL_OUTPUT_STREAM, so it creates a useless thread even if you never called stdout(…)
            Proc p = ps.start();
            // Make sure these stream will get closed later, to release their remote counterpart from the agent's ExportTable. See JENKINS-60960.
            c.registerForCleanup(p.getStdout());
            c.registerForCleanup(p.getStderr());
        }
        return c;
    }

    @NonNull
    private List<String> binaryLauncherCmd(ShellController c, FilePath ws, @Nullable String shell, String binaryPath,
                                           String scriptPath, String cookieValue, String cookieVariable) throws IOException, InterruptedException {
        String logFile = c.getLogFile(ws).getRemote();
        String resultFile = c.getResultFile(ws).getRemote();
        String outputFile = c.getOutputFile(ws).getRemote();
        String controlDirPath = c.controlDir(ws).getRemote();

        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("-controldir=" + controlDirPath);
        cmd.add("-result=" + resultFile);
        cmd.add("-log=" + logFile);
        cmd.add("-cookiename=" + cookieVariable);
        cmd.add("-cookieval=" + cookieValue);
        cmd.add("-script=" + scriptPath);
        if (shell != null) {
            cmd.add("-shell=" + shell);
        }
        if (capturingOutput) {
            cmd.add("-output=" + outputFile);
        }
        // JENKINS-58290: launch in the background. No need to close stdout/err, binary does not write to them.
        cmd.add("-daemon");
        if (LAUNCH_DIAGNOSTICS) {
            cmd.add("-debug");
        }
        return cmd;
    }

    @NonNull
    private List<String> scriptLauncherCmd(ShellController c, FilePath ws, @CheckForNull String shell,
                                           OsType os, String scriptPath, String cookieValue,
                                           String cookieVariable) throws IOException, InterruptedException {
        String cmdString;
        FilePath logFile = c.getLogFile(ws);
        FilePath resultFile = c.getResultFile(ws);
        FilePath controlDir = c.controlDir(ws);
        String interpreter = "";

        if ((shell != null) && !script.startsWith("#!")) {
            interpreter = "'" + shell + "' -xe ";
        }
        if (os == OsType.WINDOWS) { // JENKINS-40255
            scriptPath = scriptPath.replace("\\", "/"); // cygwin sh understands mixed path  (ie : "c:/jenkins/workspace/script.sh" )
        }
        String scriptPathCopy = scriptPath + ".copy"; // copy file to protect against "Text file busy", see JENKINS-70874
        if (capturingOutput) {
            cmdString = String.format("cp '%s' '%s'; { while [ -d '%s' -a \\! -f '%s' ]; do touch '%s'; sleep 3; done } & jsc=%s; %s=$jsc %s '%s' > '%s' 2> '%s'; echo $? > '%s.tmp'; mv '%s.tmp' '%s'; wait",
                                      scriptPath,
                                      scriptPathCopy,
                                      controlDir,
                                      resultFile,
                                      logFile,
                                      cookieValue,
                                      cookieVariable,
                                      interpreter,
                                      scriptPathCopy,
                                      c.getOutputFile(ws),
                                      logFile,
                                      resultFile, resultFile, resultFile);
        } else {
            cmdString = String.format("cp '%s' '%s'; { while [ -d '%s' -a \\! -f '%s' ]; do touch '%s'; sleep 3; done } & jsc=%s; %s=$jsc %s '%s' > '%s' 2>&1; echo $? > '%s.tmp'; mv '%s.tmp' '%s'; wait",
                                      scriptPath,
                                      scriptPathCopy,
                                      controlDir,
                                      resultFile,
                                      logFile,
                                      cookieValue,
                                      cookieVariable,
                                      interpreter,
                                      scriptPathCopy,
                                      logFile,
                                      resultFile, resultFile, resultFile);
        }

        cmdString = cmdString.replace("$", "$$"); // escape against EnvVars jobEnv in LocalLauncher.launch
        List<String> cmd = new ArrayList<>();
        if (os != OsType.DARWIN) { // JENKINS-25848
            cmd.add("nohup");
        }
        if (LAUNCH_DIAGNOSTICS) {
            cmd.addAll(Arrays.asList("sh", "-c", cmdString));
        } else {
            // JENKINS-58290: launch in the background. Also close stdout/err so docker-exec and the like do not wait.
            cmd.addAll(Arrays.asList("sh", "-c", "(" + cmdString + ") >&- 2>&- &"));
        }
        return cmd;
    }

    /*package*/ static final class ShellController extends FileMonitoringController {

        /** Last time we checked the timestamp, in nanoseconds on the master. */
        private transient long lastCheck;
        /** Last-observed modification time of {@link FileMonitoringTask.FileMonitoringController#getLogFile(FilePath)} on remote computer, in milliseconds. */
        private transient long checkedTimestamp;

        /** Caching zOS flag to avoid round trip calls in exitStatus()         */
        private final boolean isZos;
        /** Encoding of jenkins-result.txt if on z/OS, null otherwise          */
        private String jenkinsResultTxtEncoding;

        private ShellController(FilePath ws, boolean zOsFlag, @NonNull String cookieValue, String jenkinsResultTxtEncoding) throws IOException, InterruptedException {
            super(ws, cookieValue);
            this.isZos = zOsFlag;
            this.jenkinsResultTxtEncoding = jenkinsResultTxtEncoding;
        }

        public FilePath getScriptFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("script.sh");
        }

        /** Only here for compatibility. */
        private FilePath pidFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("pid");
        }

        @Override protected Integer exitStatus(FilePath workspace, TaskListener listener) throws IOException, InterruptedException {
            Integer status;
            if(isZos) {
                // We need to transcode status file from EBCDIC only on z/OS platform
                FilePath statusFile = getResultFile(workspace);
                status = statusFile.act(new StatusCheckWithEncoding(jenkinsResultTxtEncoding != null ? jenkinsResultTxtEncoding : getCharset()));
            }
            else {
                status = super.exitStatus(workspace, listener);
            }
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
                        FilePath pidFile = pidFile(workspace);
                        if (pidFile.exists()) {
                            listener.getLogger().println("still have " + pidFile + " so heartbeat checks unreliable; process may or may not be alive");
                        } else {
                            listener.getLogger().println("wrapper script does not seem to be touching the log file in " + controlDir);
                            listener.getLogger().println("(JENKINS-48300: if on an extremely laggy filesystem, consider -Dorg.jenkinsci.plugins.durabletask.BourneShellScript.HEARTBEAT_CHECK_INTERVAL=86400)");
                            return recordExitStatus(workspace, -1);
                        }
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

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.BourneShellScript_bourne_shell();
        }

    }

    private static final class getIBMzOsEncoding extends MasterToSlaveCallable<String,RuntimeException> {
        @Override public String call() throws RuntimeException {
            // Not null on z/OS systems
            return System.getProperty("ibm.system.encoding");
        }
        private static final long serialVersionUID = 1L;
    }

    /* Local copy of StatusCheck to run on z/OS   */
    static class StatusCheckWithEncoding extends MasterToSlaveFileCallable<Integer> {
        private final String charset;
        StatusCheckWithEncoding(String charset) {
            this.charset = charset;
        }
        @Override
        @CheckForNull
        public Integer invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            if (f.exists() && f.length() > 0) {
                try {
                    String fileString = com.google.common.io.Files.readFirstLine(f, Charset.forName(charset));
                    if (fileString == null || fileString.isEmpty()) {
                        return null;
                    } else {
                        fileString = fileString.trim();
                        if (fileString.isEmpty()) {
                            return null;
                        } else {
                            return Integer.parseInt(fileString);
                        }
                    }
                } catch (NumberFormatException x) {
                    throw new IOException("corrupted content in " + f + " using " + charset + ": " + x, x);
                }
            }
            return null;
        }
    }
}
