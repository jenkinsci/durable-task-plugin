/*
 * The MIT License
 *
 * Copyright 2015 CloudBees, Inc.
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

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Platform;
import hudson.Proc;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import hudson.tasks.Shell;
import hudson.util.StreamTaskListener;
import hudson.util.VersionNumber;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.*;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.jenkinsci.test.acceptance.docker.Docker;
import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;

enum TestPlatform {
    NATIVE, ALPINE, CENTOS, UBUNTU, SLIM, NO_INIT, UBUNTU_NO_BINARY
}

@RunWith(Parameterized.class)
public class BourneShellScriptTest {
    @Parameters(name = "{index}: {0}")
    public static Object[] data() {
        return TestPlatform.values();
    }

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public DockerRule<JavaContainer> dockerUbuntu = new DockerRule<>(JavaContainer.class);
    @Rule public DockerRule<CentOSFixture> dockerCentOS = new DockerRule<>(CentOSFixture.class);
    @Rule public DockerRule<AlpineFixture> dockerAlpine = new DockerRule<>(AlpineFixture.class);
    @Rule public DockerRule<SlimFixture> dockerSlim = new DockerRule<>(SlimFixture.class);

    @BeforeClass public static void unix() throws Exception {
        assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');
    }

    static void assumeDocker() throws Exception {
        assumeTrue("Docker is available", new Docker().isAvailable());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assumeThat("`docker version` could be run", new Launcher.LocalLauncher(StreamTaskListener.fromStderr()).launch().cmds("docker", "version", "--format", "{{.Client.Version}}").stdout(new TeeOutputStream(baos, System.err)).stderr(System.err).join(), is(0));
        assumeThat("Docker must be at least 1.13.0 for this test (uses --init)", new VersionNumber(baos.toString().trim()), greaterThanOrEqualTo(new VersionNumber("1.13.0")));
    }

    @Rule public LoggerRule logging = new LoggerRule().recordPackage(BourneShellScript.class, Level.FINE);

    private TestPlatform platform;
    private StreamTaskListener listener;
    private Slave s;
    private FilePath ws;
    private Launcher launcher;

    public BourneShellScriptTest(TestPlatform platform) throws Exception {
        this.platform = platform;
        this.listener = StreamTaskListener.fromStdout();
    }

    @Before public void prepareAgentForPlatform() throws Exception {
        BourneShellScript.PLUGIN_VERSION = readPluginVersion();
        switch (platform) {
            case NATIVE:
                s = j.createOnlineSlave();
                break;
            case UBUNTU_NO_BINARY:
                BourneShellScript.FORCE_SHELL_WRAPPER = true;
            case SLIM:
            case ALPINE:
            case CENTOS:
            case UBUNTU:
            case NO_INIT:
                assumeDocker();
                s = prepareAgentDocker();
                j.jenkins.addNode(s);
                j.waitOnline(s);
                break;
            default:
                throw new AssertionError(platform);
        }
        ws = s.getWorkspaceRoot().child("ws");
        launcher = s.createLauncher(listener);
    }

    private Slave prepareAgentDocker() throws Exception {
        switch(platform) {
            case SLIM:
            case ALPINE:
            case CENTOS:
            case UBUNTU:
            case UBUNTU_NO_BINARY:
                return prepareDockerPlatforms();
            case NO_INIT:
                return new DumbSlave("docker",
                            "/home/jenkins/agent",
                            new SimpleCommandLauncher("docker run -i --rm jenkins/slave:3.29-2 java -jar /usr/share/jenkins/slave.jar"));
            default:
                throw new AssertionError(platform);
        }
    }

    private Slave prepareDockerPlatforms() throws Exception {
        DockerContainer container = null;
        String customJavaPath = null;
        switch (platform) {
            case SLIM:
                container = dockerSlim.get();
                customJavaPath = SlimFixture.SLIM_JAVA_LOCATION;
                break;
            case ALPINE:
                container = dockerAlpine.get();
                break;
            case CENTOS:
                container = dockerCentOS.get();
                break;
            case UBUNTU:
            case UBUNTU_NO_BINARY:
                container = dockerUbuntu.get();
                break;
            default:
                throw new AssertionError(platform);
        }
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(Collections.singletonMap(Domain.global(), Collections.<Credentials>singletonList(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "test", null, "test", "test"))));
        SSHLauncher sshLauncher = new SSHLauncher(container.ipBound(22), container.port(22), "test");
        sshLauncher.setJavaPath(customJavaPath);
        return new DumbSlave("docker", "/home/test", sshLauncher);
    }

    @After public void agentCleanup() throws IOException, InterruptedException {
        if (s != null) {
            j.jenkins.removeNode(s);
        }
        BourneShellScript.FORCE_SHELL_WRAPPER = false;
    }

    @Test public void smokeTest() throws Exception {
        int sleepSeconds;
        switch (platform) {
            case NATIVE:
                sleepSeconds = 0;
                break;
            case CENTOS:
            case UBUNTU:
            case UBUNTU_NO_BINARY:
            case NO_INIT:
                sleepSeconds = 10;
                break;
            case ALPINE:
            case SLIM:
                sleepSeconds = 45;
                break;
            default:
                throw new AssertionError(platform);
        }

        String script = String.format("echo hello world; sleep %s", sleepSeconds);
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        assertNoZombies();
    }

    @Test public void stop() throws Exception {
        // Have observed both SIGTERM and SIGCHLD, perhaps depending on which process (the written sh, or sleep) gets the signal first.
        // TODO without the `trap … EXIT` the other handlers do not seem to get run, and we get exit code 143 (~ uncaught SIGTERM). Why?
        // Also on jenkins.ci neither signal trap is encountered, only EXIT.
        Controller c = new BourneShellScript("trap 'echo got SIGCHLD' CHLD; trap 'echo got SIGTERM' TERM; trap 'echo exiting; exit 99' EXIT; sleep 999").launch(new EnvVars(), ws, launcher, listener);
        Thread.sleep(1000);
        c.stop(ws, launcher);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(99, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(log.contains("sleep 999"));
        assertTrue(log.contains("got SIG"));
        c.cleanup(ws);
        assertNoZombies();
    }

    @Test public void reboot() throws Exception {
        int orig = BourneShellScript.HEARTBEAT_CHECK_INTERVAL;
        BourneShellScript.HEARTBEAT_CHECK_INTERVAL = 15;
        try {
            FileMonitoringTask.FileMonitoringController c = (FileMonitoringTask.FileMonitoringController) new BourneShellScript("sleep 999").launch(new EnvVars("killemall", "true"), ws, launcher, listener);
            Thread.sleep(1000);
            launcher.kill(Collections.singletonMap("killemall", "true"));
            // waiting for launcher to write a termination result before attempting to delete it
            awaitCompletion(c);
            c.getResultFile(ws).delete();
            awaitCompletion(c);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            c.writeLog(ws, baos);
            String log = baos.toString();
            System.out.println(log);
            assertEquals(Integer.valueOf(-1), c.exitStatus(ws, launcher, listener));
            assertTrue(log.contains("sleep 999"));
            c.cleanup(ws);
        } finally {
            BourneShellScript.HEARTBEAT_CHECK_INTERVAL = orig;
        }
        assertNoZombies();
    }

    @Test public void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
        assertNoZombies();
    }

    @Issue("JENKINS-27152")
    @Test public void cleanWorkspace() throws Exception {
        Controller c = new BourneShellScript("touch stuff && echo ---`ls -1a`---").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("---. .. stuff---"));
        c.cleanup(ws);
    }

    @Issue("JENKINS-26133")
    @Test public void output() throws Exception {
        DurableTask task = new BourneShellScript("echo 42");
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("+ echo 42"));
        assertEquals("42\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Issue("JENKINS-38381")
    @Test public void watch() throws Exception {
        DurableTask task = new BourneShellScript("set +x; for x in 1 2 3 4 5; do echo $x; sleep 1; done");
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        BlockingQueue<Integer> status = new LinkedBlockingQueue<>();
        BlockingQueue<String> output = new LinkedBlockingQueue<>();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        c.watch(ws, new MockHandler(s.getChannel(), status, output, lines), listener);
        assertEquals("+ set +x", lines.take());
        assertEquals(0, status.take().intValue());
        assertEquals("<no output>", output.take());
        assertEquals("[1, 2, 3, 4, 5]", lines.toString());
        task = new BourneShellScript("echo result");
        task.captureOutput();
        c = task.launch(new EnvVars(), ws, launcher, listener);
        status = new LinkedBlockingQueue<>();
        output = new LinkedBlockingQueue<>();
        lines = new LinkedBlockingQueue<>();
        c.watch(ws, new MockHandler(s.getChannel(), status, output, lines), listener);
        assertEquals(0, status.take().intValue());
        assertEquals("result\n", output.take());
        assertEquals("[+ echo result]", lines.toString());
        c.cleanup(ws);
        assertNoZombies();
    }
    static class MockHandler extends Handler {
        final BlockingQueue<Integer> status;
        final BlockingQueue<String> output;
        final BlockingQueue<String> lines;
        @SuppressWarnings("unchecked")
        MockHandler(VirtualChannel channel, BlockingQueue<Integer> status, BlockingQueue<String> output, BlockingQueue<String> lines) {
            this.status = channel.export(BlockingQueue.class, status);
            this.output = channel.export(BlockingQueue.class, output);
            this.lines = channel.export(BlockingQueue.class, lines);
        }
        @Override public void output(InputStream stream) throws Exception {
            lines.addAll(IOUtils.readLines(stream, StandardCharsets.UTF_8));
        }
        @Override public void exited(int code, byte[] data) throws Exception {
            status.add(code);
            output.add(data != null ? new String(data, StandardCharsets.UTF_8) : "<no output>");
        }
    }

    @Issue("JENKINS-40734")
    @Test public void envWithShellChar() throws Exception {
        Controller c = new BourneShellScript("echo \"value=$MYNEWVAR\"").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test public void shebang() throws Exception {
        ExtensionList.lookupSingleton(Shell.DescriptorImpl.class).setShell("/bin/false"); // Should be overridden
        Controller c = new BourneShellScript("#!/bin/cat\nHello, world!").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("Hello, world!"));
        c.cleanup(ws);
    }

    @Issue("JENKINS-50902")
    @Test public void configuredInterpreter() throws Exception {
        ExtensionList.lookupSingleton(Shell.DescriptorImpl.class).setShell("/bin/bash");
        String script = "if [ ! -z \"$BASH_VERSION\" ]; then echo 'this is bash'; else echo 'this is not'; fi";
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("this is bash"));
        c.cleanup(ws);

        // Find it in the PATH
        ExtensionList.lookupSingleton(Shell.DescriptorImpl.class).setShell("bash");
        c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("this is bash"));
        c.cleanup(ws);

        ExtensionList.lookupSingleton(Shell.DescriptorImpl.class).setShell("no_such_shell");
        c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertNotEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("no_such_shell"));
        c.cleanup(ws);
    }

    /**
     * Make sure the golang binary does not output to stdout/stderr when running in non-daemon mode,
     * otherwise binary will crash if Jenkins is terminated unexpectedly.
     */
    @Test public void noStdout() throws Exception {
        assumeTrue(!platform.equals(TestPlatform.UBUNTU_NO_BINARY));
        System.setProperty(BourneShellScript.class.getName() + ".LAUNCH_DIAGNOSTICS", "true");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TeeOutputStream teeOut = new TeeOutputStream(baos, System.out);
        StreamTaskListener stdoutListener = new StreamTaskListener(teeOut, Charset.defaultCharset());
        String script = String.format("echo hello world");
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, stdoutListener);
        awaitCompletion(c);
        assertThat(baos.toString(), isEmptyString());
        c.cleanup(ws);
        System.clearProperty(BourneShellScript.class.getName() + ".LAUNCH_DIAGNOSTICS");
    }

    @Issue("JENKINS-58290")
    @Test public void backgroundLaunch() throws IOException, InterruptedException {
        int sleepSeconds;
        switch (platform) {
            case NATIVE:
            case CENTOS:
            case UBUNTU:
            case UBUNTU_NO_BINARY:
            case NO_INIT:
                sleepSeconds = 10;
                break;
            case ALPINE:
            case SLIM:
                sleepSeconds = 45;
                break;
            default:
                throw new AssertionError(platform);
        }
        final AtomicReference<Proc> proc = new AtomicReference<>();
        Launcher decorated = new Launcher.DecoratedLauncher(launcher) {
            @Override public Proc launch(Launcher.ProcStarter starter) throws IOException {
                Proc delegate = super.launch(starter);
                assertTrue(proc.compareAndSet(null, delegate));
                return delegate;
            }
        };
        String script = String.format("echo hello world; sleep 5; echo long since started; sleep %s", sleepSeconds - 5);
        ByteArrayOutputStream baos;
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, decorated, listener);
        baos = new ByteArrayOutputStream();
        while (c.exitStatus(ws, launcher, listener) == null) {
            c.writeLog(ws, baos);
            if (baos.toString().contains("long since started")) {
                assertNotNull(proc.get());
                assertFalse("JENKINS-58290: wrapper process still running:\n" + baos, proc.get().isAlive());
            }
            Thread.sleep(100);
        }
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        assertNoZombies();
    }

    @Test public void caching() throws Exception {
        assumeTrue(!platform.equals(TestPlatform.UBUNTU_NO_BINARY));
        String os;
        if (Platform.isDarwin()) {
            os = "darwin";
        } else {
            os = "unix";
        }

        String binaryName = "durable_task_monitor_" + BourneShellScript.PLUGIN_VERSION + "_" + os + "_64";
        FilePath binaryPath = ws.getParent().getParent().child(binaryName);
        assertFalse(binaryPath.exists());

        BourneShellScript script = new BourneShellScript("echo hello");
        EnvVars envVars = new EnvVars();
        Controller c = script.launch(envVars, ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        Long timeCheck1 = binaryPath.lastModified();

        c = script.launch(envVars, ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        Long timeCheck2 = binaryPath.lastModified();
        assertEquals(timeCheck1, timeCheck2);

        binaryPath.delete();
        binaryPath.touch(Instant.now().toEpochMilli());
        try {
            c = script.launch(envVars, ws, launcher, listener);
        } catch (Exception e) {
            assertThat(e, instanceOf(IOException.class));
            assertThat(e.getMessage(), containsString("Cannot run program"));
        }
    }

    private static String readPluginVersion() throws IOException {
        String pluginVersion = null;
        try (FileReader pomReader = new FileReader("pom.xml")) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);
            pluginVersion = model.getProperties().getProperty("revision");
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        }
        return pluginVersion;
    }

    /**
     * Returns the first zombie process it finds.
     *
     * @return String `ps` line output of the zombie process found. Empty string otherwise.
     * @throws InterruptedException
     * @throws IOException
     */
    private void assertNoZombies() throws InterruptedException, IOException {
        String exitString = null;
        String zombieString = null;
        switch (platform) {
            case SLIM:
                // Debian slim does not have ps
            case NO_INIT:
                // (See JENKINS-58656) Running in a container with no init process is guaranteed to leave a zombie. Just let this test pass.
                assumeTrue(true);
            case UBUNTU_NO_BINARY:
                exitString = " sleep ";
                zombieString = ".+ Z .+";
                break;
            default:
                exitString = "sh -xe " + ws.getRemote();
                zombieString = ".+Z[s|\\s]\\s*\\[durable.+";
        }

        String psFormat = setPsFormat();
        String psString = null;
        do {
            Thread.sleep(1000);
            psString = psOut(psFormat);
        } while (psString.contains(exitString));

        // Give some time to see if binary becomes a zombie
        Thread.sleep(1000);
        Pattern zombiePattern = Pattern.compile(zombieString);
        Matcher zombieMatcher = zombiePattern.matcher(psOut(psFormat));
        if (zombieMatcher.find()) {
            fail(zombieMatcher.group());
        }
    }

    /**
     * Outputs the result of a `ps` shell call to a string. Useful for debugging.
     *
     * @param psFormat String of the column format in which to display the `ps` result
     * @return String of the `ps` shell call
     * @throws InterruptedException
     * @throws IOException
     */
    private String psOut(String psFormat) throws InterruptedException, IOException {
        if (psFormat == null) {
            psFormat = setPsFormat();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assertEquals(0, launcher.launch().cmds("ps", "-e", "-o", psFormat).stdout(baos).join());
        return baos.toString();
    }

    /**
     * Convenience method that will set the `ps -o` column format to a consistent output across *NIX flavors.
     * The column format is process PID, parent PID, process status, and launch command of the process.
     *
     * @return String of the `ps -o` column format
     */
    private String setPsFormat() {
        String cmdCol = null;
        switch (platform) {
            case NATIVE:
                cmdCol = Platform.isDarwin() ? "comm" : "cmd";
                break;
            case ALPINE:
                cmdCol = "args";
                break;
            default:
                cmdCol = "cmd";
        }
        return "pid,ppid,stat," + cmdCol;
    }

    private void awaitCompletion(Controller c) throws IOException, InterruptedException {
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
    }
}
