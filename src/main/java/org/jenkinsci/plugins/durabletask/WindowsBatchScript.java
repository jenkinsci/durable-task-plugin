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
import hudson.PluginWrapper;
import hudson.Proc;
import hudson.model.TaskListener;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import hudson.util.LineEndingConversion;
import jenkins.model.Jenkins;

/**
 * Runs a Windows batch script.
 */
public final class WindowsBatchScript extends FileMonitoringTask {
    @SuppressFBWarnings("MS_SHOULD_BE_FINAL") // Used to control usage of binary or shell wrapper
    @Restricted(NoExternalUse.class)
    public static boolean FORCE_SHELL_WRAPPER = Boolean.getBoolean(WindowsBatchScript.class.getName() + ".FORCE_SHELL_WRAPPER");

    private final String script;
    private boolean capturingOutput;
    private static final Logger LOGGER = Logger.getLogger(WindowsBatchScript.class.getName());

    @DataBoundConstructor public WindowsBatchScript(String script) {
        this.script = LineEndingConversion.convertEOL(script, LineEndingConversion.EOLType.Windows);
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

        FilePath nodeRoot = getNodeRoot(ws);
        final Jenkins jenkins = Jenkins.get();
        PluginWrapper durablePlugin = jenkins.getPluginManager().getPlugin("durable-task");
        if (durablePlugin == null) {
            throw new IOException("Unable to find durable task plugin");
        }
        String pluginVersion = StringUtils.substringBefore(durablePlugin.getVersion(), "-");
        AgentInfo agentInfo = nodeRoot.act(new AgentInfo.GetAgentInfo(pluginVersion));
        BatchController c = new BatchController(ws);

        // launcher -daemon=true -shell=powershell -script=test.ps1 -log=logging.txt -result=result.txt -controldir=. -output=output.txt -debug
        List<String> launcherCmd = null;
        if (!FORCE_SHELL_WRAPPER && agentInfo.isBinaryCompatible()) {
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
                    launcherCmd = binaryLauncherCmd(c, ws, controlDir.getRemote(), binary.getRemote(), c.getBatchFile2(ws).getRemote());
                    c.getBatchFile2(ws).write(script, "UTF-8");
                }
            }
        }
        if (launcherCmd == null) {
            launcherCmd = scriptLauncherCmd(c, ws);
        }

//        LOGGER.log(Level.FINE, "launching {0}", launcherCmd);
        LOGGER.log(Level.INFO, "launching {0}", launcherCmd);
        Launcher.ProcStarter ps = launcher.launch().cmds(launcherCmd).envs(escape(envVars)).pwd(ws).quiet(true);

        /* Too noisy, and consumes a thread:
        ps.stdout(listener);
        */
        ps.readStdout().readStderr(); // TODO see BourneShellScript
        Proc p = ps.start();
        c.registerForCleanup(p.getStdout());
        c.registerForCleanup(p.getStderr());

        return c;
    }

    @Nonnull
    private List<String> binaryLauncherCmd(BatchController c, FilePath ws, String controlDirPath, String binaryPath, String scriptPath) throws IOException, InterruptedException {
        String logFile = c.getLogFile(ws).getRemote();
        String resultFile = c.getResultFile(ws).getRemote();
        String outputFile = c.getOutputFile(ws).getRemote();

        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("-daemon");
        cmd.add("-executable=cmd");
        cmd.add("-args=/C call \\\"" + scriptPath + "\\\"");
        cmd.add("-controldir=" + controlDirPath);
        cmd.add("-result=" + resultFile);
        cmd.add("-log=" + logFile);
        if (capturingOutput) {
            cmd.add("-output=" + outputFile);
        }
        // TODO: REMOVE
        cmd.add("-debug");
        return cmd;
    }

    @Nonnull
    private List<String> scriptLauncherCmd(BatchController c, FilePath ws) throws IOException, InterruptedException {

        String cmdString;
        if (capturingOutput) {
            cmdString = String.format("@echo off \r\ncmd /c call \"%s\" > \"%s\" 2> \"%s\"\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmdString = String.format("@echo off \r\ncmd /c call \"%s\" > \"%s\" 2>&1\r\necho %%ERRORLEVEL%% > \"%s\"\r\n",
                quote(c.getBatchFile2(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
        c.getBatchFile1(ws).write(cmdString, "UTF-8");
        c.getBatchFile2(ws).write(script, "UTF-8");

        List<String> cmd = new ArrayList<>();
        cmd.addAll(Arrays.asList("cmd", "/c", "\"\"" + c.getBatchFile1(ws).getRemote() + "\"\""));
        return cmd;
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
