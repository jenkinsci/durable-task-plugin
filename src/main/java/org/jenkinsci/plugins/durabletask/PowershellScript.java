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
            cmd = String.format(". \"%s\"; Execute-AndWriteOutput -MainScript \"%s\" -OutputFile \"%s\" -LogFile \"%s\" -ResultFile \"%s\" -CaptureOutput;", 
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellWrapperFile(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmd = String.format(". \"%s\"; Execute-AndWriteOutput -MainScript \"%s\" -LogFile \"%s\" -ResultFile \"%s\";",
                quote(c.getPowerShellHelperFile(ws)),
                quote(c.getPowerShellWrapperFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
       
        // By default PowerShell adds a byte order mark (BOM) to the beginning of a file when using Out-File with a unicode encoding such as UTF8.
        // This causes the Jenkins output to contain bogus characters because Java does not handle the BOM characters by default.
        // This code mimics Out-File, but does not write a BOM.  Hopefully PowerShell will provide a non-BOM option for writing files in future releases.
        String helperScript = "Function Out-FileNoBom {\n" +
        "[CmdletBinding()]\n" +
        "param(\n" +
        "  [Parameter(Mandatory=$true, Position=0)] [string] $FilePath,\n" +
        "  [Parameter(ValueFromPipeline=$true)] $InputObject\n" +
        ")\n" +
        "  $out = New-Object IO.StreamWriter $FilePath, $false\n" +
        "  try {\n" +
        "    $Input | Out-String -Stream | % { $out.WriteLine($_) }\n" +
        "  } finally {\n" +
        "    $out.Dispose()\n" +
        "  }\n" +
        "}\n" +
        "Function Execute-AndWriteOutput {\n" +
        "[CmdletBinding()]\n" +
        "param(\n" +
        "  [Parameter(Mandatory=$true)]  [string]$MainScript,\n" +
        "  [Parameter(Mandatory=$false)] [string]$OutputFile,\n" +
        "  [Parameter(Mandatory=$true)]  [string]$LogFile,\n" +
        "  [Parameter(Mandatory=$true)]  [string]$ResultFile,\n" +
        "  [Parameter(Mandatory=$false)] [switch]$CaptureOutput\n" +
        ")\n" +
        "  if ($CaptureOutput -eq $true) {\n" +
        "      if ($PSVersionTable.PSVersion.Major -ge 5) {\n" +
        "          $(& $MainScript | Out-FileNoBom -FilePath $OutputFile) 2>&1 3>&1 4>&1 5>&1 6>&1 | Out-FileNoBom -FilePath $LogFile; $LastExitCode | Out-File -FilePath $ResultFile -Encoding ASCII;\n" +
        "      } else {\n" +
        "          $(& $MainScript | Out-FileNoBom -FilePath $OutputFile) 2>&1 3>&1 4>&1 5>&1 | Out-FileNoBom -FilePath $LogFile; $LastExitCode | Out-File -FilePath $ResultFile -Encoding ASCII;\n" +
        "      }\n" +
        "  } else {\n" +
        "      & $MainScript *>&1 | Out-FileNoBom -FilePath $LogFile; $LastExitCode | Out-File -FilePath $ResultFile -Encoding ASCII;\n" +
        "  }\n" +
        "}";
        
        // Execute the script, and catch any errors in order to properly set the jenkins build status. $LastExitCode cannot be solely responsible for determining build status because
        // there are several instances in which it is not set, e.g. thrown exceptions, and errors that aren't caused by native executables.
        String wrapperScriptContent = "try {\r\n" + 
        "  & '" + quote(c.getPowerShellScriptFile(ws)) + "'\r\n" + 
        "} catch {\r\n" + 
        "  Write-Error $_;" + 
        "  exit 1;\r\n" + 
        "} finally {\r\n" +
        "  if ($LastExitCode -ne $null) {\r\n" + 
        "    exit $LastExitCode;\r\n" +
        "  } elseif ($error.Count -gt 0 -or !$?) {\r\n" + 
        "    exit 1;\r\n" +
        "  } else {\r\n" +
        "    exit 0;\r\n" +
        "  }\r\n" +
        "}";
                   
        // Write the PowerShell scripts out with a UTF8 BOM
        writeWithBom(c.getPowerShellHelperFile(ws), helperScript);
        writeWithBom(c.getPowerShellScriptFile(ws), script);
        writeWithBom(c.getPowerShellWrapperFile(ws), wrapperScriptContent);

        if (launcher.isUnix()) {
            // Open-Powershell does not support ExecutionPolicy
            args.addAll(Arrays.asList("powershell", "-NonInteractive", "-Command", cmd));
        } else {
            args.addAll(Arrays.asList("powershell.exe", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", cmd));    
        }

        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+(\\\\|/)", "") + "] Running PowerShell script");
        ps.readStdout().readStderr();
        ps.start();

        return c;
    }
    
    private static String quote(FilePath f) {
        return f.getRemote().replace("$", "`$");
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
        
        public FilePath getPowerShellWrapperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellWrapper.ps1");
        }
        
        public FilePath getPowerShellHelperFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellHelper.ps1");
        }

        private static final long serialVersionUID = 1L;
    }

    @Extension public static final class DescriptorImpl extends DurableTaskDescriptor {

        @Override public String getDisplayName() {
            return Messages.PowershellScript_powershell();
        }

    }

}
