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
import java.lang.Class;
import java.lang.ClassLoader;
import java.lang.reflect.Method;
import java.lang.Exception;
import java.lang.ClassNotFoundException;
import java.lang.InstantiationException;
import java.lang.NoSuchMethodException;
import java.lang.IllegalAccessException;
import java.lang.reflect.InvocationTargetException;
import hudson.model.TaskListener;
import java.io.IOException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Runs a Powershell script
 */
public final class PowershellScript extends FileMonitoringTask {
    private final String script;
    private boolean capturingOutput;
    private boolean addVerboseOutput;

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
            // Using redirection in PowerShell produces extra newlines in output, so I am using [io.file]::WriteAllText to prevent corrupted output of exit code
            cmd = String.format("$res = (& \"%s\" | Out-String); 2> $null; [io.file]::WriteAllText(\"%s\",$LastExitCode); [io.file]::WriteAllText(\"%s\",$error); [io.file]::WriteAllText(\"%s\",$res);", 
                quote(c.getPowershellMainFile(ws)),
                quote(c.getResultFile(ws)),
                quote(c.getLogFile(ws)),
                quote(c.getOutputFile(ws)));

        } else {
            cmd = String.format("$res = (& \"%s\" 2>&1 | Out-String); [io.file]::WriteAllText(\"%s\",$LastExitCode); [io.file]::WriteAllText(\"%s\",$res);",
                quote(c.getPowershellMainFile(ws)),
                quote(c.getResultFile(ws)),
                quote(c.getLogFile(ws)));
        }
        
        // Force the resulting script to exit with an exit code.  This ensures that the result file will always have a non-null value
        String scriptWithExit = "try {\r\n" + script + "\r\n} catch {\r\nWrite-Output $_; exit 1;\r\n}\r\nif ($LastExitCode -ne $null -and $LastExitCode -ne 0) {\r\nexit $LastExitCode;\r\n} elseif ($error.Count -gt 0 -or !$?) {\r\nexit 1;\r\n} else {\r\nexit 0;\r\n}";
        
        // Write the script and execution wrapper to powershell files in the workspace
        c.getPowershellMainFile(ws).write(scriptWithExit, "UTF-8");
        c.getPowershellWrapperFile(ws).write(cmd, "UTF-8");
        
        // Try to load the PowerShell plugin to produce the command line arguments for running the script
        Plugin powershellPlugin = Jenkins.getInstance().getPlugin("powershell");
        boolean notLoadedOrError = false;
        if (powershellPlugin != null && powershellPlugin.getWrapper().isEnabled()) {
            try {
                ClassLoader cl = powershellPlugin.getWrapper().classLoader;
                Class<?> powershellClass = cl.loadClass("hudson.plugins.powershell.PowerShell");
                Object powershellInst = powershellClass.getConstructor(String.class).newInstance(script);
                Method m = powershellClass.getMethod("buildCommandLine",FilePath.class);
                String[] cmdLine = (String[])m.invoke(powershellInst,c.getPowershellWrapperFile(ws));
                
                // Get the command line for running the script from the PowerShell plugin
                args.addAll(Arrays.asList(cmdLine));
            } catch (ClassNotFoundException e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught ClassNotFoundException: " + e.getMessage());
            } catch (InstantiationException e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught InstantiationException: " + e.getMessage());
            } catch (NoSuchMethodException e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught NoSuchMethodException: " + e.getMessage());
            } catch (IllegalAccessException e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught IllegalAccessException: " + e.getMessage());
            } catch (InvocationTargetException e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught InvocationTargetException: " + e.getMessage());
            } catch (Exception e) {
                notLoadedOrError = true;
                listener.getLogger().println("Caught Exception: " + e.getMessage());
            }
        } else {
            notLoadedOrError = true;
        }
        
        // PowerShell plugin is not installed, not enabled, or cannot be loaded for some reason. Fall back using standard command line arguments
        if (notLoadedOrError) {
            listener.getLogger().println("Warning: PowerShell plugin is not installed, not enabled, or cannot be loaded.  Executing script using the built in fallback.");
            if (launcher.isUnix()) {
                // Open Powershell does not support ExecutionPolicy
                args.addAll(Arrays.asList("powershell", "-NonInteractive", "-File", c.getPowershellWrapperFile(ws).getRemote()));
            } else {
                args.addAll(Arrays.asList("powershell.exe", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", c.getPowershellWrapperFile(ws).getRemote()));    
            }
        }

        Launcher.ProcStarter ps = launcher.launch().cmds(args).envs(escape(envVars)).pwd(ws).quiet(true);
        listener.getLogger().println("[" + ws.getRemote().replaceFirst("^.+\\\\", "") + "] Running PowerShell script");
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

        public FilePath getPowershellMainFile(FilePath ws) throws IOException, InterruptedException {
            return controlDir(ws).child("powershellMain.ps1");
        }
        
        public FilePath getPowershellWrapperFile(FilePath ws) throws IOException, InterruptedException {
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