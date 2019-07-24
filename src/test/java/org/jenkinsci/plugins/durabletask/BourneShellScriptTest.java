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
import hudson.Platform;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    NATIVE, ALPINE, CENTOS, UBUNTU, SLIM, SIMPLE
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
        this.platform = platform;
        this.listener = StreamTaskListener.fromStdout();
    }

    @Before public void prepareAgentForPlatform() throws Exception {
        switch (platform) {
            case NATIVE:
                s = j.createOnlineSlave();
                break;
            case SLIM:
            case ALPINE:
            case CENTOS:
            case UBUNTU:
            case SIMPLE:
                s = prepareAgentDocker();
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
        switch(platform) {
            case SLIM:
            case ALPINE:
            case CENTOS:
            case UBUNTU:
                return prepareDockerPlatforms();
            case SIMPLE:
                return prepareAgentCommandLauncher();
            default:
                Assert.fail("Unknown test platform: " + platform);
                return null;
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
                container = dockerUbuntu.get();
                break;
            default:
                Assert.fail("Unknown enum value: " + platform);
                break;
        }
        return new DumbSlave("docker",
                "/home/test",
                new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", "", customJavaPath, null, null));
    }

    private Slave prepareAgentCommandLauncher() throws Exception{
        // counter used to prevent name smashing when a docker container from the previous test is
        // still shutting down but the new test container is being spun up. Seems more ideal than adding a wait.
        String name = "docker";
        String agent = "agent-" + counter++;
        String remoteFs = "/home/jenkins/" + agent;

        String dockerRunSimple = String.format("docker run -i --rm --name %s jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar", agent);
        return new DumbSlave(name, remoteFs, new SimpleCommandLauncher(dockerRunSimple));
    }

    @After public void agentCleanup() throws IOException, InterruptedException {
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
        j.jenkins.removeNode(s);
    }

    @Test public void smokeTest() throws Exception {
        int sleepSeconds = -1;
        switch (platform) {
            case NATIVE:
                sleepSeconds = 0;
                break;
            case CENTOS:
            case UBUNTU:
            case SIMPLE:
                sleepSeconds = 10;
                break;
            case ALPINE:
            case SLIM:
                sleepSeconds = 45;
                break;
            default:
                Assert.fail("Unknown enum value: " + platform);
                break;
        }

        String script = String.format("echo hello world; sleep %s", sleepSeconds);
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        assertThat(getZombies(), isEmptyString());
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
        assertThat(getZombies(), isEmptyString());
    }

    @Test public void reboot() throws Exception {
        int orig = BourneShellScript.HEARTBEAT_CHECK_INTERVAL;
        BourneShellScript.HEARTBEAT_CHECK_INTERVAL = 15;
        try {
            FileMonitoringTask.FileMonitoringController c = (FileMonitoringTask.FileMonitoringController) new BourneShellScript("sleep 999").launch(new EnvVars("killemall", "true"), ws, launcher, listener);
            Thread.sleep(1000);
            psOut(null);
            launcher.kill(Collections.singletonMap("killemall", "true"));
            psOut(null);
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
        assertThat(getZombies(), isEmptyString());
    }

    @Test public void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
        assertThat(getZombies(), isEmptyString());
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
        assertThat(getZombies(), isEmptyString());
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
     * Checks if the golang binary outputs to stdout under normal shell execution.
     * The binary must NOT output to stdout or else it will crash when Jenkins is terminated
     * unexpectedly.
     */
    @Test public void stdout() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TeeOutputStream teeOut = new TeeOutputStream(baos, System.out);
        StreamTaskListener stdoutListener = new StreamTaskListener(teeOut, Charset.defaultCharset());
        String script = String.format("echo hello world");
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, stdoutListener);
        awaitCompletion(c);
        assertThat(baos.toString(), isEmptyString());
        c.cleanup(ws);
    }

    private String getZombies() throws InterruptedException, IOException {
        // Due to backgrounding, running durable-task in a docker container with init process is guaranteed to leave a zombie. Just let this test pass.
        // See PR #98 (https://github.com/jenkinsci/durable-task-plugin/pull/98)
        if (platform.equals(TestPlatform.SIMPLE)) {
            return "";
        }

        String psFormat = setPsFormat();
        String psString = null;
        do {
            Thread.sleep(1000);
           psString = psOut(psFormat);
        } while (psString.contains("sh -xe " + ws.getRemote()));

        // Give some time to see if binary becomes a zombie
        Thread.sleep(1000);
        Pattern zombiePattern = Pattern.compile(".+Z[s|\\s]\\s*\\[heart.+");
        Matcher zombieMatcher = zombiePattern.matcher(psOut(psFormat));
        if (zombieMatcher.find()) {
            return zombieMatcher.group();
        } else {
            return "";
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
        assertEquals(0, launcher.launch().cmds("ps", "-e", "-o", psFormat).stdout(new TeeOutputStream(baos, System.out)).join());
        return baos.toString();
    }

    /**
     * Convenience method that sets the `ps` column format to PID, parent PID, process status, and launch command of the process
     *
     * @return
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

