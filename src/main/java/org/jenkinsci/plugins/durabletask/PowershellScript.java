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
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Plugin;
import hudson.Launcher;
import jenkins.model.Jenkins;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    private final String script;
    private boolean capturingOutput;
    
    @DataBoundConstructor public PowershellScript(String script) {
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
        List<String> args = new ArrayList<String>();
        PowershellController c = new PowershellController(ws);
        
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
      
        // Note: PowerShell core is now named pwsh. Workaround this issue on *nix systems by creating a symlink that maps 'powershell' to 'pwsh'.
        String powershellBinary = "powershell";
        String powershellArgs;
        if (launcher.isUnix()) {
            powershellArgs = "-NoProfile -NonInteractive";
        } else {
            powershellArgs = "-NoProfile -NonInteractive -ExecutionPolicy Bypass";
        }
        args.add(powershellBinary);
        args.addAll(Arrays.asList(powershellArgs.split(" ")));
        args.addAll(Arrays.asList("-Command", cmd));
        
        // Ensure backwards compatibility with PowerShell 3,4 for proper error propagation while also ensuring that output stream designations are present in PowerShell 5+
        String scriptWrapper = String.format("[CmdletBinding()]\r\n" +
                                             "param()\r\n" +
                                             "& '%s';\r\n" +
                                             "exit $LASTEXITCODE;", quote(c.getPowerShellScriptFile(ws)));
        
        // Add an explicit exit to the end of the script so that exit codes are propagated
        String scriptWithExit = script + "\r\nexit $LASTEXITCODE;";
        
        // Copy the helper script from the resources directory into the workspace
        c.getPowerShellHelperFile(ws).copyFrom(getClass().getResource("powershellHelper.ps1"));
        
        if (launcher.isUnix()) {
            // There is no need to add a BOM with Open PowerShell
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
        
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+(\\\\|/)", "") + "] Running PowerShell script");
        ps.readStdout().readStderr();
        ps.start();

        return c;
    }
    
    private static String quote(FilePath f) {
        return f.getRemote().replace("'", "''");
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

    }

}
