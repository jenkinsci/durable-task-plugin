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

import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.tasks.Shell;
import hudson.util.StreamTaskListener;
import hudson.util.VersionNumber;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.*;

import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;

enum TestPlatform {
    NATIVE, ALPINE, CENTOS, UBUNTU, SLIM, SIMPLE, TINI
}

@RunWith(Parameterized.class)
public class BourneShellScriptTest {
    @Parameters(name = "{index}: {0}")
    public static Iterable<? extends Object> data() {
        return EnumSet.allOf(TestPlatform.class);
    }

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public DockerRule<JavaContainer> dockerUbuntu = new DockerRule<>(JavaContainer.class);
    @Rule public DockerRule<CentOSFixture> dockerCentOS = new DockerRule<>(CentOSFixture.class);
    @Rule public DockerRule<AlpineFixture> dockerAlpine = new DockerRule<>(AlpineFixture.class);
    @Rule public DockerRule<SlimFixture> dockerSlim = new DockerRule<>(SlimFixture.class);

    @BeforeClass public static void unixAndDocker() throws Exception {
        assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');
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
    private static int counter = 0; // used to prevent docker container name-smashing

    public BourneShellScriptTest(TestPlatform platform) throws Exception {
        System.out.println("My platform: " + platform);
        this.platform = platform;
        this.listener = StreamTaskListener.fromStdout();
    }

    @Before public void prepareAgentForPlatform() throws Exception {
        System.out.println("prepare platform: " + platform);
        switch (platform) {
            case NATIVE:
                s = j.createOnlineSlave();
                break;
            case SLIM:
            case ALPINE:
            case CENTOS:
            case UBUNTU:
                s = prepareAgentDocker();
                j.jenkins.addNode(s);
                j.waitOnline(s);
                break;
            case SIMPLE:
            case TINI:
                s = prepareAgentCommandLauncher();
                j.jenkins.addNode(s);
                j.waitOnline(s);
                break;
            default:
                Assert.fail("Unknown enum value: " + platform);
                break;
        }
        ws = s.getWorkspaceRoot().child("ws");
        launcher = s.createLauncher(listener);
    }

    private Slave prepareAgentDocker() throws Exception {
        DockerContainer container = null;
        switch (platform) {
            case SLIM:
                container = dockerSlim.get();
                break;
            case ALPINE:
                container = dockerAlpine.get();
                break;
            case CENTOS:
                container = dockerCentOS.get();
                break;
            case UBUNTU:
                container = dockerUbuntu.get();
                break;
            default:
                Assert.fail("Unknown enum value: " + platform);
                break;
        }
        return createDockerSlave(container);
    }

    private Slave prepareAgentCommandLauncher() throws Exception{
        // counter used to prevent name smashing when a docker container from the previous
        // test is still being shut down but the new test container is being spun up. Seems more ideal than adding a wait.
        String agent = "agent-" + Integer.toString(counter++);
        String remoteFs = "/home/jenkins/" + agent;

        String dockerRunSimple = String.format("docker run -i --rm --name %s jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar", agent);
        String dockerRunTini = String.format("docker run -i --rm --name %s --init jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar", agent);
        Slave agentSlave = null;
        switch (platform) {
            case SIMPLE:
                agentSlave = new DumbSlave("docker", remoteFs, new SimpleCommandLauncher(dockerRunSimple));
                break;
            case TINI:
                agentSlave = new DumbSlave("docker", remoteFs, new SimpleCommandLauncher(dockerRunTini));
                break;
            default:
                // error
                break;
        }
        return agentSlave;
    }

    @After public void slaveCleanup() throws IOException, InterruptedException {
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
        j.jenkins.removeNode(s);
    }

    @Test public void smokeTest() throws Exception {
        boolean isNative = (platform == TestPlatform.NATIVE);
        int sleepSeconds = 10;
        if (isNative) {
            sleepSeconds = 0;
        }

        String script = String.format("echo hello world; sleep %s", sleepSeconds);
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        if (!isNative) {
            assertTrue("no zombies running", noZombies());
        }
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
    }

    @Test public void reboot() throws Exception {
        int orig = BourneShellScript.HEARTBEAT_CHECK_INTERVAL;
        BourneShellScript.HEARTBEAT_CHECK_INTERVAL = 15;
        try {
            FileMonitoringTask.FileMonitoringController c = (FileMonitoringTask.FileMonitoringController) new BourneShellScript("sleep 999").launch(new EnvVars("killemall", "true"), ws, launcher, listener);
            Thread.sleep(1000);
            psOut();
            launcher.kill(Collections.singletonMap("killemall", "true"));
            psOut();
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
    }

    @Test public void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
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
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
    }

    @Test public void shebang() throws Exception {
        setGlobalInterpreter("/bin/false"); // Should be overridden
        Controller c = new BourneShellScript("#!/bin/cat\nHello, world!").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("Hello, world!"));
        c.cleanup(ws);
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
    }

    @Issue("JENKINS-50902")
    @Test public void configuredInterpreter() throws Exception {
        setGlobalInterpreter("/bin/bash");
        String script = "if [ ! -z \"$BASH_VERSION\" ]; then echo 'this is bash'; else echo 'this is not'; fi";
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("this is bash"));
        c.cleanup(ws);

        // Find it in the PATH
        setGlobalInterpreter("bash");
        c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("this is bash"));
        c.cleanup(ws);

        setGlobalInterpreter("no_such_shell");
        c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertNotEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("no_such_shell"));
        c.cleanup(ws);
        if (simpleOrTini()) {
            assertTrue("no zombies running", noZombies());
        }
    }

    private boolean noZombies() throws InterruptedException {
        ByteArrayOutputStream baos = null;
        do {
            Thread.sleep(1000);
            baos = new ByteArrayOutputStream();
            try {
                assertEquals(0, launcher.launch().cmds("ps", "-e", "-o", "pid,ppid,stat,comm").stdout(new TeeOutputStream(baos, System.out)).join());
            } catch (IOException x) { // no ps? forget this check
                System.err.println(x);
                break;
            }
        } while (baos.toString().contains(" sleep "));
        return !baos.toString().contains(" Z ");
    }

    // to assist with debugging
    private String psOut() throws InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            launcher.launch().cmds("ps", "-ef").stdout(new TeeOutputStream(baos, System.out)).join();
        } catch (IOException x) { // no ps? skip
            System.err.println(x);
        }
        return baos.toString();
    }

    private boolean simpleOrTini() {
        if (platform == TestPlatform.SIMPLE || platform == TestPlatform.TINI) {
            return true;
        } else {
            return false;
        }
    }

    private DumbSlave createDockerSlave(DockerContainer container) throws hudson.model.Descriptor.FormException, IOException {
        return new DumbSlave("docker", "/home/test", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", ""));
    }

    private void awaitCompletion(Controller c) throws IOException, InterruptedException {
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
    }

    private void setGlobalInterpreter(String interpreter) {
        ExtensionList.lookup(Shell.DescriptorImpl.class).get(0).setShell(interpreter);
    }
}

