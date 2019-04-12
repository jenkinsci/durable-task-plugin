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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.File;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import jenkins.security.MasterToSlaveCallable;
import hudson.remoting.VirtualChannel;
import org.kohsuke.stapler.DataBoundConstructor;
import javax.annotation.CheckForNull;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.FileUtils;
import com.google.common.io.Files;



/**
 * Runs a Bourne shell script on a Unix node using {@code nohup}.
 */
public final class BourneShellScript extends FileMonitoringTask {

    private static final Logger LOGGER = Logger.getLogger(BourneShellScript.class.getName());

    private static enum OsType {DARWIN, UNIX, WINDOWS, ZOS}

    private static final String SYSTEM_DEFAULT_CHARSET = "SYSTEM_DEFAULT";

    /** Number of times we will show launch diagnostics in a newly encountered workspace before going mute to save resources. */
    @SuppressWarnings("FieldMayBeFinal")
    // TODO use SystemProperties if and when unrestricted
    private static int NOVEL_WORKSPACE_DIAGNOSTICS_COUNT = Integer.getInteger(BourneShellScript.class.getName() + ".NOVEL_WORKSPACE_DIAGNOSTICS_COUNT", 10);

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
        OsType os = ws.act(new getOsType());
        String scriptEncodingCharset = "UTF-8";
        if(os == OsType.ZOS) {
            Charset zOSSystemEncodingCharset = Charset.forName(ws.act(new getIBMzOsEncoding()));
            if(SYSTEM_DEFAULT_CHARSET.equals(getCharset())) {
                // Setting default charset to IBM z/OS default EBCDIC charset on z/OS if no encoding specified on sh step
                charset(zOSSystemEncodingCharset);
            }
            scriptEncodingCharset = zOSSystemEncodingCharset.name();
        }
        
        ShellController c = new ShellController(ws,(os == OsType.ZOS));
        FilePath shf = c.getScriptFile(ws);

        shf.write(script, scriptEncodingCharset);

        final Jenkins jenkins = Jenkins.getInstance();
        String interpreter = "";
        if (!script.startsWith("#!")) {
            String shell = jenkins.getDescriptorByType(Shell.DescriptorImpl.class).getShellOrDefault(ws.getChannel());
            interpreter = "'" + shell + "' -xe ";
        } else {
            shf.chmod(0755);
        }

        String scriptPath = shf.getRemote();
        List<String> args = new ArrayList<>();

        if (os != OsType.DARWIN) { // JENKINS-25848
            args.add("nohup");
        }
        if (os == OsType.WINDOWS) { // JENKINS-40255
            scriptPath= scriptPath.replace("\\", "/"); // cygwin sh understands mixed path  (ie : "c:/jenkins/workspace/script.sh" )
        }

        envVars.put(cookieVariable, "please-do-not-kill-me");
        // The temporary variable is to ensure JENKINS_SERVER_COOKIE=durable-… does not appear even in argv[], lest it be confused with the environment.
        String cmd;
        FilePath logFile = c.getLogFile(ws);
        FilePath resultFile = c.getResultFile(ws);
        FilePath controlDir = c.controlDir(ws);
        if (capturingOutput) {
            cmd = String.format("pid=$$; { while [ \\( -d /proc/$pid -o \\! -d /proc/$$ \\) -a -d '%s' -a \\! -f '%s' ]; do touch '%s'; sleep 3; done } & jsc=%s; %s=$jsc %s '%s' > '%s' 2> '%s'; echo $? > '%s.tmp'; mv '%s.tmp' '%s'; wait",
                controlDir,
                resultFile,
                logFile,
                cookieValue,
                cookieVariable,
                interpreter,
                scriptPath,
                c.getOutputFile(ws),
                logFile,
                resultFile, resultFile, resultFile);
        } else {
            cmd = String.format("pid=$$; { while [ \\( -d /proc/$pid -o \\! -d /proc/$$ \\) -a -d '%s' -a \\! -f '%s' ]; do touch '%s'; sleep 3; done } & jsc=%s; %s=$jsc %s '%s' > '%s' 2>&1; echo $? > '%s.tmp'; mv '%s.tmp' '%s'; wait",
                controlDir,
                resultFile,
                logFile,
                cookieValue,
                cookieVariable,
                interpreter,
                scriptPath,
                logFile,
                resultFile, resultFile, resultFile);
        }
        cmd = cmd.replace("$", "$$"); // escape against EnvVars jobEnv in LocalLauncher.launch

        args.addAll(Arrays.asList("sh", "-c", cmd));
        LOGGER.log(Level.FINE, "launching {0}", args);
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
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

        /** Last time we checked the timestamp, in nanoseconds on the master. */
        private transient long lastCheck;
        /** Last-observed modification time of {@link getLogFile} on remote computer, in milliseconds. */
        private transient long checkedTimestamp;

        /** Caching zOS flag to avoid round trip calls in exitStatus()         */
        private final boolean isZos;
        
        private ShellController(FilePath ws, boolean zOsFlag) throws IOException, InterruptedException {
            super(ws);
            this.isZos = zOsFlag;
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
                status = statusFile.act(new StatusCheckWithEncoding(getCharset()));
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

    private static final class getOsType extends MasterToSlaveCallable<OsType,RuntimeException> {
        @Override public OsType call() throws RuntimeException {
            if (Platform.isDarwin()) {
              return OsType.DARWIN;
            } else if (Platform.current() == Platform.WINDOWS) {
              return OsType.WINDOWS;
            } else if(Platform.current() == Platform.UNIX && System.getProperty("os.name").equals("z/OS")) {
              return OsType.ZOS;  
            } else {
              return OsType.UNIX; // Default Value
            }
        }
        private static final long serialVersionUID = 1L;
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
                    String fileString = Files.readFirstLine(f, Charset.forName(charset));
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
                    throw new IOException("corrupted content in " + f + ": " + x, x);
                }
            }
            return null;
        }
    }
}
