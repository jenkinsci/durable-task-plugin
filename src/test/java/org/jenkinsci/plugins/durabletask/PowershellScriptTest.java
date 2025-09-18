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
import hudson.util.StreamTaskListener;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@WithJenkins
@EnabledOnOs(OS.WINDOWS)
@ParameterizedClass(name = "{index}: USE_BINARY={0}")
@ValueSource(booleans = {true, false})
class PowershellScriptTest {

    private final boolean enablePwsh = false;

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;

    @Parameter
    private boolean enableBinary;

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;

        PowershellScript.USE_BINARY_WRAPPER = enableBinary;
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
        assumeTrue(powershellExists, "This test should only run if powershell is available");

        // Assume Powershell major version is at least 3
        List<String> args = new ArrayList<>(Collections.singleton(cmd));
        if (!launcher.isUnix()) {
            args.addAll(Arrays.asList("-ExecutionPolicy", "Bypass"));
        }
        args.addAll(Arrays.asList("-NoProfile", "-Command", "& {Write-Output $PSVersionTable.PSVersion.Major}"));
        Launcher.ProcStarter ps = launcher.launch().cmds(args).quiet(true);
        ps.readStdout();
        Proc proc = ps.start();
        String psVersionStr = IOUtils.toString(proc.getStdout(), StandardCharsets.UTF_8);
        int psVersion;
        try {
            psVersion = Integer.parseInt(psVersionStr.trim());
        } catch (NumberFormatException x) {
            throw new TestAbortedException(psVersionStr, x);
        }
        assumeTrue(psVersion >= 3, "This test should only run if the powershell major version is at least 3");
    }

    @Test
    void explicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; exit 1;");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher, TaskListener.NULL));
        assertThat(baos.toString(), containsString("Hello, World!"));
        c.cleanup(ws);
    }

    @Test
    void implicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Success!\";");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher, TaskListener.NULL));
        assertThat(baos.toString(), containsString("Success!"));
        c.cleanup(ws);
    }

    @Test
    void implicitError() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'Stop'; Write-Error \"Bogus error\"");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, TaskListener.NULL) != 0);
        assertThat(baos.toString(), containsString("Bogus error"));
        c.cleanup(ws);
    }

    @Test
    void implicitErrorNegativeTest() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'SilentlyContinue'; Write-Error \"Bogus error\"");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, (int) c.exitStatus(ws, launcher, TaskListener.NULL));
        c.cleanup(ws);
    }

    @Test
    void parseError() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("string is missing the terminator"));
        c.cleanup(ws);
    }

    @Test
    void invocationCommandNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-VerbDoesNotExist");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Get-VerbDoesNotExist"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -PARAMDOESNOTEXIST");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("parameter cannot be found"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterBindingError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Command -CommandType DOESNOTEXIST");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Cannot bind parameter"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterValidationError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -Month 15");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        c.cleanup(ws);
    }

    @Test
    void cmdletBindingScriptDoesNotError() throws Exception {
        PowershellScript s = new PowershellScript("[CmdletBinding()]param([Parameter()][string]$Param1) \"Param1 = $Param1\"");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, (int) c.exitStatus(ws, launcher, listener));
        c.cleanup(ws);
    }

    @Test
    void explicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; throw \"explicit error\";");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("explicit error"));
        if (launcher.isUnix()) {
            assertEquals("Hello, World!\n", new String(c.getOutput(ws, launcher)));
        } else {
            assertEquals("Hello, World!\r\n", new String(c.getOutput(ws, launcher)));
        }
        c.cleanup(ws);
    }

    @Test
    void implicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'Stop'; My-BogusCmdlet;");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("My-BogusCmdlet"));
        c.cleanup(ws);
    }

    @Test
    void noStdoutPollution() throws Exception {
        PowershellScript s = new PowershellScript("$VerbosePreference = \"Continue\"; " +
                "$WarningPreference = \"Continue\"; " +
                "$DebugPreference = \"Continue\"; " +
                "Write-Verbose \"Hello, Verbose!\"; " +
                "Write-Warning \"Hello, Warning!\"; " +
                "Write-Debug \"Hello, Debug!\"; " +
                "Write-Output \"Success\"");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, (int) c.exitStatus(ws, launcher, listener));
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

    @Test
    void specialStreams() throws Exception {
        PowershellScript s = new PowershellScript("$VerbosePreference = \"Continue\"; " +
                "$WarningPreference = \"Continue\"; " +
                "$DebugPreference = \"Continue\"; " +
                "Write-Verbose \"Hello, Verbose!\"; " +
                "Write-Warning \"Hello, Warning!\"; " +
                "Write-Debug \"Hello, Debug!\";");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
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

    @Test
    void spacesInWorkspace() throws Exception {
        final FilePath newWs = new FilePath(ws, "subdirectory with spaces");
        PowershellScript s = new PowershellScript("Write-Host 'Running in a workspace with spaces in the path'");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(newWs, launcher, TaskListener.NULL).intValue());
        c.cleanup(ws);
    }

    @Test
    void echoEnvVar() throws Exception {
        PowershellScript s = new PowershellScript("echo envvar=$env:MYVAR");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars("MYVAR", "power$hell"), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("envvar=power$hell"));
        c.cleanup(ws);
    }

    @Test
    void unicodeChars() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Helló, Wõrld ®\";");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher, TaskListener.NULL));
        String log = baos.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Helló, Wõrld ®"), log);
        c.cleanup(ws);
    }

    @Test
    void correctExitCode() throws Exception {
        PowershellScript s = new PowershellScript("exit 5;");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(Integer.valueOf(5), c.exitStatus(ws, launcher, TaskListener.NULL));
        c.cleanup(ws);
    }

    @Test
    void checkProfile() throws Exception {
        PowershellScript s = new PowershellScript("if ((Get-CimInstance Win32_Process -Filter \"ProcessId = $PID\").CommandLine.split(\" \").Contains(\"-NoProfile\")) { exit 0; } else { exit 1; } ");
        if (enablePwsh) {
            s.setPowershellBinary("pwsh");
        }
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
        int retries = 0;
        while (retries < 6) {
            if (binaryInactive()) {
                break;
            }
            Thread.sleep(500);
            retries++;
        }
    }

    /**
     * Determines if the windows binary is not running by checking the tasklist
     */
    private boolean binaryInactive() throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertEquals(0, launcher.launch().cmds("tasklist", "/fi", "\"imagename eq durable_task_monitor_*\"").stdout(baos).join());
        return baos.toString().contains("No tasks");
    }
}
