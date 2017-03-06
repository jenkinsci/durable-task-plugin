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
import hudson.Launcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.File;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;

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
        c.getPowershellFile(ws).write(script, "UTF-8");
        
        String cmd;
        if (capturingOutput) {
            cmd = String.format("\"& \'%s\'\" | Out-String > \"%s\" 2> \"%s\"; echo $LastExitCode > \"%s\";", 
                quote(c.getPowershellFile(ws)),
                quote(c.getOutputFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        } else {
            cmd = String.format("\"& '%s'\" | Out-String > \"%s\" 2>&1; echo $LastExitCode > \"%s\";",
                quote(c.getPowershellFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getResultFile(ws)));
        }
        
        //FilePath cmdOutPath = new FilePath(new File("C:\\users\\gabloe\\desktop\\cmdtest\\cmd.txt"));
        
        if (launcher.isUnix()) {
            args.addAll(Arrays.asList("powershell", "-NonInteractive", cmd));
        } else {
            args.addAll(Arrays.asList("powershell.exe", "-NonInteractive", "-ExecutionPolicy", "Bypass", cmd));    
        }

        //cmdOutPath.write(quote(c.getPowershellFile(ws)), "UTF-8");
        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+\\\\", "") + "] Running PowerShell script"); // details printed by cmd
        ps.readStdout().readStderr();
        ps.start();
        return c;
    }
    
    private static String quote(FilePath f) {
        return f.getRemote().replace("%", "%%");
    }

    private static final class PowershellController extends FileMonitoringController {
        private PowershellController(FilePath ws) throws IOException, InterruptedException {
            super(ws);
        }

        public FilePath getPowershellFile(FilePath ws) throws IOException, InterruptedException {
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
