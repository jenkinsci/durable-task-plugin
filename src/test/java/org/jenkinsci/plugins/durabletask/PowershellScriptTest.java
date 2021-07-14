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

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.TaskListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import static org.hamcrest.Matchers.containsString;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.jvnet.hudson.test.JenkinsRule;
import java.util.Properties;
import java.util.*;
import org.apache.commons.io.IOUtils;
import org.junit.AssumptionViolatedException;

@RunWith(Parameterized.class)
public class PowershellScriptTest {
    @Parameters(name = "{index}: USE_BINARY={0}")
    public static Object[] parameters() {
        return new Object[] {true, false};
    }

    @Rule public JenkinsRule j = new JenkinsRule();

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private int psVersion;
    private boolean enablePwsh = false;
    private boolean useBinary;

    public PowershellScriptTest(boolean useBinary) {
        this.useBinary = useBinary;
    }

    @Before public void vars() throws IOException {
        PowershellScript.USE_SCRIPT_WRAPPER = !useBinary;
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
        Properties properties = System.getProperties();
        String pathSeparator = properties.getProperty("path.separator");
        String[] paths = System.getenv("PATH").split(pathSeparator);
        boolean powershellExists = false;
        // Note: This prevents this set of tests from running on PowerShell core unless a symlink is created that maps 'powershell' to 'pwsh' on *nix systems
        String cmd = "powershell";
        for (String p : paths) {
            // If running on *nix then the binary does not have an extension.  Check for both variants to ensure *nix and windows+cygwin are both supported.
            File withoutExtension = new File(p, cmd);
            File withExtension = new File(p, cmd + ".exe");
            if (withoutExtension.exists() || withExtension.exists()) {
                powershellExists = true;
                break;
            }
        }
        Assume.assumeTrue("This test should only run if powershell is available", powershellExists == true);

        // Assume Powershell major version is at least 3
        if (powershellExists == true) {
            List<String> args = new ArrayList<>(Collections.singleton(cmd));
            if (!launcher.isUnix()) {
                args.addAll(Arrays.asList("-ExecutionPolicy", "Bypass"));
            }
            args.addAll(Arrays.asList("-NoProfile" ,"-Command", "& {Write-Output $PSVersionTable.PSVersion.Major}"));
            Launcher.ProcStarter ps = launcher.launch().cmds(args).quiet(true);
            ps.readStdout();
            Proc proc = ps.start();
            String psVersionStr = IOUtils.toString(proc.getStdout());
            try {
                psVersion = Integer.parseInt(psVersionStr.trim());
            } catch (NumberFormatException x) {
                throw new AssumptionViolatedException(psVersionStr, x);
            }
            Assume.assumeTrue("This test should only run if the powershell major version is at least 3", psVersion >= 3);
        }
    }

    @Test public void explicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; exit 1;");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher, TaskListener.NULL));
        assertThat(baos.toString(), containsString("Hello, World!"));
        c.cleanup(ws);
    }

    @Test public void implicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Success!\";");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher, TaskListener.NULL));
        assertThat(baos.toString(), containsString("Success!"));
        c.cleanup(ws);
    }

    @Test public void implicitError() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'Stop'; Write-Error \"Bogus error\"");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, TaskListener.NULL).intValue() != 0);
        assertThat(baos.toString(), containsString("Bogus error"));
        c.cleanup(ws);
    }

    @Test public void implicitErrorNegativeTest() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'SilentlyContinue'; Write-Error \"Bogus error\"");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertTrue(c.exitStatus(ws, launcher, TaskListener.NULL).intValue() == 0);
        c.cleanup(ws);
    }

    @Test public void parseError() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("string is missing the terminator"));
        c.cleanup(ws);
    }

    @Test public void invocationCommandNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-VerbDoesNotExist");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("Get-VerbDoesNotExist"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -PARAMDOESNOTEXIST");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("parameter cannot be found"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterBindingError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Command -CommandType DOESNOTEXIST");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("Cannot bind parameter"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterValidationError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -Month 15");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        c.cleanup(ws);
    }

    @Test public void cmdletBindingScriptDoesNotError() throws Exception {
        PowershellScript s = new PowershellScript("[CmdletBinding()]param([Parameter()][string]$Param1) \"Param1 = $Param1\"");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() == 0);
        c.cleanup(ws);
    }

    @Test public void explicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; throw \"explicit error\";");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("explicit error"));
        if (launcher.isUnix()) {
            assertEquals("Hello, World!\n", new String(c.getOutput(ws, launcher)));
        } else {
            assertEquals("Hello, World!\r\n", new String(c.getOutput(ws, launcher)));
        }
        c.cleanup(ws);
    }

    @Test public void implicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'Stop'; My-BogusCmdlet;");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("My-BogusCmdlet"));
        c.cleanup(ws);
    }

    @Test public void noStdoutPollution() throws Exception {
        PowershellScript s = new PowershellScript("$VerbosePreference = \"Continue\"; " +
                                                  "$WarningPreference = \"Continue\"; " +
                                                  "$DebugPreference = \"Continue\"; " +
                                                  "Write-Verbose \"Hello, Verbose!\"; " +
                                                  "Write-Warning \"Hello, Warning!\"; " +
                                                  "Write-Debug \"Hello, Debug!\"; " +
                                                  "Write-Output \"Success\"");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() == 0);
        assertThat(baos.toString(), containsString("Hello, Verbose!"));
        assertThat(baos.toString(), containsString("Hello, Warning!"));
        assertThat(baos.toString(), containsString("Hello, Debug!"));
        if (launcher.isUnix()) {
            assertEquals("Success\n", new String(c.getOutput(ws, launcher)));
        } else {
            assertEquals("Success\r\n", new String(c.getOutput(ws, launcher)));
        }
        c.cleanup(ws);
    }

    @Test public void specialStreams() throws Exception {
        PowershellScript s = new PowershellScript("$VerbosePreference = \"Continue\"; " +
                                                  "$WarningPreference = \"Continue\"; " +
                                                  "$DebugPreference = \"Continue\"; " +
                                                  "Write-Verbose \"Hello, Verbose!\"; " +
                                                  "Write-Warning \"Hello, Warning!\"; " +
                                                  "Write-Debug \"Hello, Debug!\";");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, TaskListener.NULL).intValue());
        assertThat(baos.toString(), containsString("VERBOSE: Hello, Verbose!"));
        assertThat(baos.toString(), containsString("WARNING: Hello, Warning!"));
        assertThat(baos.toString(), containsString("DEBUG: Hello, Debug!"));
        c.cleanup(ws);
    }

    @Test public void spacesInWorkspace() throws Exception {
        final FilePath newWs = new FilePath(ws, "subdirectory with spaces");
        PowershellScript s = new PowershellScript("Write-Host 'Running in a workspace with spaces in the path'");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(newWs, launcher, TaskListener.NULL).intValue());
        c.cleanup(ws);
    }

    @Test public void echoEnvVar() throws Exception {
        PowershellScript s = new PowershellScript("echo envvar=$env:MYVAR");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars("MYVAR", "power$hell"), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("envvar=power$hell"));
        c.cleanup(ws);
    }

    @Test public void unicodeChars() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Helló, Wõrld ®\";");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher, TaskListener.NULL));
        String log = baos.toString("UTF-8");
        assertTrue(log, log.contains("Helló, Wõrld ®"));
        c.cleanup(ws);
    }

    @Test public void correctExitCode() throws Exception {
        PowershellScript s = new PowershellScript("exit 5;");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(Integer.valueOf(5), c.exitStatus(ws, launcher, TaskListener.NULL));
        c.cleanup(ws);
    }

    @Test public void checkProfile() throws Exception {
        PowershellScript s = new PowershellScript("if ((Get-CimInstance Win32_Process -Filter \"ProcessId = $PID\").CommandLine.split(\" \").Contains(\"-NoProfile\")) { exit 0; } else { exit 1; } ");
        if (enablePwsh) {s.setPowershellBinary("pwsh");}
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);

        s.setLoadProfile(true);
        c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(1, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    private void awaitCompletion(Controller c) throws IOException, InterruptedException {
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        Thread.sleep(3000); // Pause allows slower systems to exit binary before cleanup is attempted
    }
}
