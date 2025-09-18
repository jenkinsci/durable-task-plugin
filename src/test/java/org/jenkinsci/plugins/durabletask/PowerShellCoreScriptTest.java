package org.jenkinsci.plugins.durabletask;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.DumbSlave;
import hudson.util.StreamTaskListener;
import org.jenkinsci.plugins.durabletask.fixtures.PowerShellCoreFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.DockerClientFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.logging.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@WithJenkins
@DisabledOnOs(OS.WINDOWS)
class PowerShellCoreScriptTest {

    private final PowerShellCoreFixture dockerPWSH = new PowerShellCoreFixture();

    private final LogRecorder logging = new LogRecorder().recordPackage(PowershellScript.class, Level.FINE);

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private Slave s;
    private static boolean pwshExists;

    private JenkinsRule j;

    @BeforeAll
    static void setUp() {
        checkPwsh();
        assumeTrue(pwshExists || DockerClientFactory.instance().isDockerAvailable(), "This test should only run on Docker or if pwsh is available");
    }

    private static void checkPwsh() {
        Properties properties = System.getProperties();
        String pathSeparator = properties.getProperty("path.separator");
        String[] paths = System.getenv("PATH").split(pathSeparator);
        String cmd = "pwsh";
        for (String p : paths) {
            // If running on *nix then the binary does not have an extension.  Check for both variants to ensure *nix and windows+cygwin are both supported.
            File withoutExtension = new File(p, cmd);
            File withExtension = new File(p, cmd + ".exe");
            if (withoutExtension.exists() || withExtension.exists()) {
                pwshExists = true;
                break;
            }
        }
    }

    @BeforeEach
    void setUp(JenkinsRule rule) throws Exception {
        j = rule;

        listener = StreamTaskListener.fromStdout();

        if (pwshExists) {
            s = j.createOnlineSlave();
        } else {
            SystemCredentialsProvider.getInstance().setDomainCredentialsMap(Collections.singletonMap(
                    Domain.global(), Collections.singletonList(new UsernamePasswordCredentialsImpl(
                            CredentialsScope.GLOBAL, "test", null, "test", "test"))));
            dockerPWSH.start();
            SSHLauncher sshLauncher = new SSHLauncher(dockerPWSH.getHost(), dockerPWSH.getMappedPort(22), "test");
            sshLauncher.setJavaPath(PowerShellCoreFixture.PWSH_JAVA_LOCATION);
            s = new DumbSlave("docker", "/home/test", sshLauncher);
            j.jenkins.addNode(s);
            j.waitOnline(s);
        }
        ws = s.getWorkspaceRoot().child("ws");
        launcher = s.createLauncher(listener);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (s != null) {
            j.jenkins.removeNode(s);
        }
        dockerPWSH.stop();
    }

    @Test
    void explicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; exit 1;");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher));
        assertThat(baos.toString(), containsString("Hello, World!"));
        c.cleanup(ws);
    }

    @Test
    void implicitExit() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Success!\";");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        assertThat(baos.toString(), containsString("Success!"));
        c.cleanup(ws);
    }

    @Test
    void implicitError() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'Stop'; Write-Error \"Bogus error\"");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Bogus error"));
        c.cleanup(ws);
    }

    @Test
    void implicitErrorNegativeTest() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'SilentlyContinue'; Write-Error \"Bogus error\"");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertEquals(0, (int) c.exitStatus(ws, launcher, listener));
        c.cleanup(ws);
    }

    @Test
    void parseError() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("string is missing the terminator"));
        c.cleanup(ws);
    }

    @Test
    void invocationCommandNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-VerbDoesNotExist");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Get-VerbDoesNotExist"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -PARAMDOESNOTEXIST");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("parameter cannot be found"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterBindingError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Command -CommandType DOESNOTEXIST");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Cannot bind parameter"));
        c.cleanup(ws);
    }

    @Test
    void invocationParameterValidationError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -Month 13");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener) != 0);
        assertThat(baos.toString(), containsString("Cannot validate argument"));
        c.cleanup(ws);
    }

    @Test
    void cmdletBindingScriptDoesNotError() throws Exception {
        PowershellScript s = new PowershellScript("[CmdletBinding()]param([Parameter()][string]$Param1) \"Param1 = $Param1\"");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, (int) c.exitStatus(ws, launcher, listener));
        c.cleanup(ws);
    }

    @Test
    void explicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; throw \"explicit error\";");
        s.setPowershellBinary("pwsh");
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        s.setPowershellBinary("pwsh");
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("VERBOSE: Hello, Verbose!"));
        assertThat(baos.toString(), containsString("WARNING: Hello, Warning!"));
        assertThat(baos.toString(), containsString("DEBUG: Hello, Debug!"));
        c.cleanup(ws);
    }

    @Test
    void spacesInWorkspace() throws Exception {
        final FilePath newWs = new FilePath(ws, "subdirectory with spaces");
        PowershellScript s = new PowershellScript("Write-Host 'Running in a workspace with spaces in the path'");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), newWs, launcher, listener);
        while (c.exitStatus(newWs, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertEquals(0, c.exitStatus(newWs, launcher).intValue());
        c.cleanup(ws);
    }

    @Test
    void echoEnvVar() throws Exception {
        PowershellScript s = new PowershellScript("echo envvar=$env:MYVAR");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars("MYVAR", "power$hell"), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("envvar=power$hell"));
        c.cleanup(ws);
    }

    @Test
    void unicodeChars() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Helló, Wõrld ®\";");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        String log = baos.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Helló, Wõrld ®"), log);
        c.cleanup(ws);
    }

    @Test
    void correctExitCode() throws Exception {
        PowershellScript s = new PowershellScript("exit 5;");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertEquals(Integer.valueOf(5), c.exitStatus(ws, launcher));
        c.cleanup(ws);
    }
}
