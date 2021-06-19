/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
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
import hudson.*;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import hudson.model.TaskListener;
import java.io.IOException;

import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to control usage of binary or shell wrapper
    @Restricted(NoExternalUse.class)
    public static boolean FORCE_BINARY_WRAPPER = Boolean.getBoolean(PowershellScript.class.getName() + ".FORCE_BINARY_WRAPPER");

    private final String script;
    private String powershellBinary = "powershell";
    private boolean needsBom = true;
    private boolean loadProfile;
    private boolean capturingOutput;
    private static final Logger LOGGER = Logger.getLogger(PowershellScript.class.getName());
    private static final String LAUNCH_DIAGNOSTICS_PROP = PowershellScript.class.getName() + ".LAUNCH_DIAGNOSTICS";

    /**
     * Enables the debug flag for the binary wrapper.
     */
    @SuppressWarnings("FieldMayBeFinal")
    // TODO use SystemProperties if and when unrestricted
    private static boolean LAUNCH_DIAGNOSTICS = Boolean.getBoolean(LAUNCH_DIAGNOSTICS_PROP);

    @DataBoundConstructor public PowershellScript(String script) {
        this.script = script;
    }

    public String getPowershellBinary() {
        return powershellBinary;
    }

    @DataBoundSetter
    public void setPowershellBinary(String powershellBinary) {
        this.powershellBinary = powershellBinary;
    }

    public boolean isLoadProfile() {
        return loadProfile;
    }

    @DataBoundSetter
    public void setLoadProfile(boolean loadProfile) {
        this.loadProfile = loadProfile;
    }

    public String getScript() {
        return script;
    }

    @Override public void captureOutput() {
        capturingOutput = true;
    }

    @SuppressFBWarnings(value="VA_FORMAT_STRING_USES_NEWLINE", justification="%n from master might be \\n")
    @Override protected FileMonitoringController doLaunch(FilePath ws, Launcher launcher, TaskListener listener, EnvVars envVars) throws IOException, InterruptedException {
        FilePath nodeRoot = getNodeRoot(ws);
        final Jenkins jenkins = Jenkins.get();
        PluginWrapper durablePlugin = jenkins.getPluginManager().getPlugin("durable-task");
        if (durablePlugin == null) {
            throw new IOException("Unable to find durable task plugin");
        }
        String pluginVersion = StringUtils.substringBefore(durablePlugin.getVersion(), "-");
        AgentInfo agentInfo = nodeRoot.act(new AgentInfo.GetAgentInfo(pluginVersion));

        PowershellController c = new PowershellController(ws);

        List<String> powershellArgs = new ArrayList<>();
        if (!loadProfile) {
            powershellArgs.add("-NoProfile");
        }
        powershellArgs.add("-NonInteractive");
        if (!launcher.isUnix()) {
            powershellArgs.addAll(Arrays.asList("-ExecutionPolicy", "Bypass"));
        }

        if (launcher.isUnix() || "pwsh".equals(powershellBinary)) {
            needsBom = false;
        }

            List<String> launcherCmd = null;
        if (FORCE_BINARY_WRAPPER && agentInfo.isBinaryCompatible()) {
            FilePath controlDir = c.controlDir(ws);
            FilePath binary;
            if (agentInfo.isCachingAvailable()) {
                binary = nodeRoot.child(agentInfo.getBinaryPath());
            } else {
                binary = controlDir.child(agentInfo.getBinaryPath());
            }
            String resourcePath = BINARY_RESOURCE_PREFIX + agentInfo.getOs().getNameForBinary() + "_" + agentInfo.getArchitecture() + ".exe";
            try (InputStream binaryStream = BourneShellScript.class.getResourceAsStream(resourcePath)) {
                if (binaryStream != null) {
                    if (!agentInfo.isCachingAvailable() || !agentInfo.isBinaryCached()) {
                        binary.copyFrom(binaryStream);
                    }

                    launcherCmd = binaryLauncherCmd(c, ws, controlDir.getRemote(), binary.getRemote(), c.getPowerShellScriptFile(ws).getRemote(), powershellArgs);
                    if (!needsBom) {
                        // There is no need to add a BOM with Open PowerShell / PowerShell Core
                        c.getPowerShellScriptFile(ws).write(script, "UTF-8");
                    } else {
                        // Write the Windows PowerShell scripts out with a UTF8 BOM
                        writeWithBom(c.getPowerShellScriptFile(ws), script);
                    }
                }
            }
        }
        if (launcherCmd == null) {
            launcherCmd = scriptLauncherCmd(c, ws, powershellArgs);

            String scriptWrapper = generateScriptWrapper(powershellBinary, powershellArgs, c.getPowerShellScriptFile(ws));

            // Add an explicit exit to the end of the script so that exit codes are propagated
            String scriptWithExit = script + "\r\nexit $LASTEXITCODE";

            // Copy the helper script from the resources directory into the workspace
            c.getPowerShellHelperFile(ws).copyFrom(getClass().getResource("powershellHelper.ps1"));

            if (!needsBom) {
                // There is no need to add a BOM with Open PowerShell / PowerShell Core
                c.getPowerShellScriptFile(ws).write(scriptWithExit, "UTF-8");
                if (!capturingOutput) {
                    c.getPowerShellWrapperFile(ws).write(scriptWrapper, "UTF-8");
                }
            } else {
                // Write the Windows PowerShell scripts out with a UTF8 BOM
                writeWithBom(c.getPowerShellScriptFile(ws), scriptWithExit);
                if (!capturingOutput) {
                    writeWithBom(c.getPowerShellWrapperFile(ws), scriptWrapper);
                }
            }

        }

//        LOGGER.log(Level.FINE, "launching {0}", launcherCmd);
        // TODO: REMOVE
        LOGGER.log(Level.INFO, "launching {0}", launcherCmd);
        Launcher.ProcStarter ps = launcher.launch().cmds(launcherCmd).envs(escape(envVars)).pwd(ws).quiet(true);
        ps.readStdout().readStderr();  // TODO see BourneShellScript
        Proc p = ps.start();
        c.registerForCleanup(p.getStdout());
        c.registerForCleanup(p.getStderr());

        return c;
    }

    @Nonnull
    private List<String> binaryLauncherCmd(PowershellController c, FilePath ws, String controlDirPath, String binaryPath, String scriptPath, List<String> powershellArgs) throws IOException, InterruptedException {
        String logFile = c.getLogFile(ws).getRemote();
        String resultFile = c.getResultFile(ws).getRemote();
        String outputFile = c.getOutputFile(ws).getRemote();

        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("-daemon");
        cmd.add(String.format("-executable=%s", powershellBinary));
        cmd.add(String.format("-args=%s,-Command,%s", String.join(",", powershellArgs), generateCommandWrapper(scriptPath, capturingOutput, outputFile, needsBom)));
        cmd.add("-controldir=" + controlDirPath);
        cmd.add("-result=" + resultFile);
        cmd.add("-log=" + logFile);
        if (LAUNCH_DIAGNOSTICS) {
            cmd.add("-debug");
        }
        return cmd;
    }

    private List<String> scriptLauncherCmd(PowershellController c, FilePath ws, List<String> powershellArgs) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        String cmd;
        if (capturingOutput) {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -OutputFile '%s' -LogFile '%s' -ResultFile '%s' -CaptureOutput;",
                    quote(c.getPowerShellHelperFile(ws)),
                    quote(c.getPowerShellScriptFile(ws)),
                    quote(c.getOutputFile(ws)),
                    quote(c.getLogFile(ws)),
                    quote(c.getResultFile(ws)));
        } else {
            cmd = String.format(". '%s'; Execute-AndWriteOutput -MainScript '%s' -LogFile '%s' -ResultFile '%s';",
                    quote(c.getPowerShellHelperFile(ws)),
                    quote(c.getPowerShellWrapperFile(ws)),
                    quote(c.getLogFile(ws)),
                    quote(c.getResultFile(ws)));
        }

        args.add(powershellBinary);
        args.addAll(powershellArgs);
        args.addAll(Arrays.asList("-Command", cmd));

        return args;
    }

    /**
     * Fix https://issues.jenkins.io/browse/JENKINS-59529
     * Fix https://issues.jenkins.io/browse/JENKINS-65597
     * Wrap invocation of powershellScript.ps1 in a try/catch in order to propagate PowerShell errors like:
     * command/script not recognized, parameter not found, parameter validation failed, parse errors, etc. In
     * PowerShell, $LASTEXITCODE applies **only** to the invocation of native apps and is not set when built-in
     * PowerShell commands or script invocation fails.
     * While you **could** prepend your script with "$ErrorActionPreference = 'Stop'; <script>" to get the step
     * to fail on a PowerShell error, that is not discoverable resulting in issues like 59529 being submitted.
     * The problem with setting $ErrorActionPreference before the script is that value propagates into the script
     * which may not be what the user wants.
     * One consequence of leaving the "exit $LASTEXITCODE" is if the last native command in a script exits with
     * a non-zero exit code, the step will fail. That may sound obvious but most PowerShell scripters are not used
     * to that. PowerShell doesn't have the equivalent of "set -e" yet. However, in the context of a build system,
     * I believe we should err on the side of false negatives instead of false positives. If a scripter doesn't
     * want a non-zero exit code to fail the step, they can do the following (PS >= v7) to reset $LASTEXITCODE:
     * whoami -f || $($global:LASTEXITCODE = 0)
     *
     */
    private static String generateScriptWrapper(String powershellBinary, List<String> powershellArgs, FilePath powerShellScriptFile) {
        return String.format(
                "[CmdletBinding()]\r\n" +
                "param()\r\n" +
                "& %s %s -Command '& {try {& ''%s''} catch {throw}; exit $LASTEXITCODE}'\r\n" +
                "exit $LASTEXITCODE",
                powershellBinary, powershellArgs, quote(quote(powerShellScriptFile)));
    }

    /**
     * Same motivation as {@link PowershellScript#generateScriptWrapper(String, List, FilePath)}, only for the binary-based launcher
     */
    private static String generateCommandWrapper(String scriptPath, boolean capturingOutput, String outputPath, boolean needsBom) {
        String encoding = "UTF8";
        if (!needsBom) {
            encoding = "utf8NoBOM";
        }
        String wrapper = String.format(
                    "[Console]::OutputEncoding = [Text.Encoding]::%s; [Console]::InputEncoding = [System.Text.Encoding]::%s; " +
                    "& {try {& \\\"%s\\\"} catch {throw}; " +
                    "exit $LASTEXITCODE}",
                    encoding, encoding, scriptPath);

        // Note: Do not set the `-output` flag for the binary when capturing output, it will pollute the success stream.
        // This is because Powershell automatically redirects the non-error streams to the success stream when running -File or -Command.
        // Non-success streams can only redirect to the success stream or a separate file (streams may not share a file).
        // Using Tee-Object outputs the success stream to both the output file and stdout. This allows the binary to capture
        String teeEncoding = "ascii";
        if (!needsBom) {
            encoding = "utf8NoBOM";
        }

        if (capturingOutput && outputPath != "") {
            wrapper = String.format("$PSDefaultParameterValues = @{'Out-File:Encoding' = '%s'}; %s | Tee-Object -FilePath \\\"%s\\\"",
                    teeEncoding, wrapper, outputPath);
        }
        return wrapper;
    }

    private static String quote(FilePath f) {
        return quote(f.getRemote());
    }

    private static String quote(String f) {
        return f.replace("'", "''");
    }

    // In order for PowerShell to properly read a script that contains unicode characters the script should have a BOM, but there is no built in support for
    // writing UTF-8 with BOM in Java.  This code writes a UTF-8 BOM before writing the file contents.
    private static void writeWithBom(FilePath f, String contents) throws IOException, InterruptedException {
        OutputStream out = f.write();
        out.write(new byte[] { (byte)0xEF, (byte)0xBB, (byte)0xBF });
        out.write(contents.getBytes(Charset.forName("UTF-8")));
        out.flush();
        out.close();
    }

    private static final class PowershellController extends FileMonitoringController {
        private PowershellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getPowerShellScriptFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellScript.ps1");
        }

        public FilePath getPowerShellHelperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellHelper.ps1");
        }

        public FilePath getPowerShellWrapperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellWrapper.ps1");
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.PowershellScript_powershell();
        }

        public ListBoxModel doFillPowershellBinary() {
            return new ListBoxModel(new Option("powershell"), new Option("pwsh"));
        }

    }

}
