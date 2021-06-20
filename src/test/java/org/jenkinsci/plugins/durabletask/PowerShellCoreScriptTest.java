package org.jenkinsci.plugins.durabletask;

import com.cloudbees.plugins.credentials.Credentials;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.Properties;
import java.util.logging.Level;
import org.jenkinsci.test.acceptance.docker.Docker;
import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

import static org.hamcrest.Matchers.containsString;
import static org.jenkinsci.plugins.durabletask.BourneShellScriptTest.assumeDocker;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class PowerShellCoreScriptTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    @Rule
    public DockerRule<PowerShellCoreFixture> dockerPWSH = new DockerRule<>(PowerShellCoreFixture.class);
    @Rule
    public LoggerRule logging = new LoggerRule().recordPackage(PowershellScript.class, Level.FINE);

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private Slave s;
    static boolean pwshExists;

    @BeforeClass
    public static void pwshOrDocker() throws Exception {
        BourneShellScriptTest.unix();
        checkPwsh();
        if (!pwshExists && new Docker().isAvailable()) {
            assumeDocker();
        } else {
            Assume.assumeTrue("This test should only run if pwsh is available", pwshExists);
        }
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

    @Before
    public void setUp() throws Exception {
        PowershellScript.FORCE_BINARY_WRAPPER = true;
        listener = StreamTaskListener.fromStdout();
        if (pwshExists) {
            s = j.createOnlineSlave();
        } else {
            DockerContainer container = dockerPWSH.get();
            SystemCredentialsProvider.getInstance().setDomainCredentialsMap(Collections.singletonMap(
                Domain
                    .global(), Collections.<Credentials>singletonList(new UsernamePasswordCredentialsImpl(
                    CredentialsScope.GLOBAL, "test", null, "test", "test"))));
            SSHLauncher sshLauncher = new SSHLauncher(container.ipBound(22), container.port(22), "test");
            sshLauncher.setJavaPath(PowerShellCoreFixture.PWSH_JAVA_LOCATION);
            s = new DumbSlave("docker", "/home/test", sshLauncher);
            j.jenkins.addNode(s);
            j.waitOnline(s);
        }
        ws = s.getWorkspaceRoot().child("ws");
        launcher = s.createLauncher(listener);
    }

    @After
    public void tearDown() throws Exception {
        if (s != null) {
            j.jenkins.removeNode(s);
        }
    }

    @Test
    public void explicitExit() throws Exception {
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

    @Test public void implicitExit() throws Exception {
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

    @Test public void implicitError() throws Exception {
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

    @Test public void implicitErrorNegativeTest() throws Exception {
        PowershellScript s = new PowershellScript("$ErrorActionPreference = 'SilentlyContinue'; Write-Error \"Bogus error\"");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        assertTrue(c.exitStatus(ws, launcher, listener) == 0);
        c.cleanup(ws);
    }

    @Test public void parseError() throws Exception {
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

    @Test public void invocationCommandNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-VerbDoesNotExist");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("Get-VerbDoesNotExist"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterNotExistError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -PARAMDOESNOTEXIST");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("parameter cannot be found"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterBindingError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Command -CommandType DOESNOTEXIST");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("Cannot bind parameter"));
        c.cleanup(ws);
    }

    @Test public void invocationParameterValidationError() throws Exception {
        PowershellScript s = new PowershellScript("Get-Date -Month 13");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() != 0);
        assertThat(baos.toString(), containsString("Cannot validate argument"));
        c.cleanup(ws);
    }

    @Test public void cmdletBindingScriptDoesNotError() throws Exception {
        PowershellScript s = new PowershellScript("[CmdletBinding()]param([Parameter()][string]$Param1) \"Param1 = $Param1\"");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertTrue(c.exitStatus(ws, launcher, listener).intValue() == 0);
        c.cleanup(ws);
    }

    @Test public void explicitThrow() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Hello, World!\"; throw \"explicit error\";");
        s.setPowershellBinary("pwsh");
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        s.setPowershellBinary("pwsh");
        s.captureOutput();
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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

    @Test public void spacesInWorkspace() throws Exception {
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

    @Test public void echoEnvVar() throws Exception {
        PowershellScript s = new PowershellScript("echo envvar=$env:MYVAR");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars("MYVAR", "power$hell"), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("envvar=power$hell"));
        c.cleanup(ws);
    }

    @Test public void unicodeChars() throws Exception {
        PowershellScript s = new PowershellScript("Write-Output \"Helló, Wõrld ®\";");
        s.setPowershellBinary("pwsh");
        Controller c = s.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        String log = baos.toString("UTF-8");
        assertTrue(log, log.contains("Helló, Wõrld ®"));
        c.cleanup(ws);
    }

    @Test public void correctExitCode() throws Exception {
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
