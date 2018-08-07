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
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.util.StreamTaskListener;
import hudson.util.VersionNumber;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.test.acceptance.docker.Docker;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.SimpleCommandLauncher;

public class BourneShellScriptTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @Rule public DockerRule<JavaContainer> dockerUbuntu = new DockerRule<>(JavaContainer.class);

    @Rule public DockerRule<CentOSFixture> dockerCentOS = new DockerRule<>(CentOSFixture.class);

    @Rule public DockerRule<AlpineFixture> dockerAlpine = new DockerRule<>(AlpineFixture.class);

    @BeforeClass public static void unixAndDocker() throws Exception {
        assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assumeThat("`docker version` could be run", new Launcher.LocalLauncher(StreamTaskListener.fromStderr()).launch().cmds("docker", "version", "--format", "{{.Client.Version}}").stdout(new TeeOutputStream(baos, System.err)).stderr(System.err).join(), is(0));
        assumeThat("Docker must be at least 1.13.0 for this test (uses --init)", new VersionNumber(baos.toString().trim()), greaterThanOrEqualTo(new VersionNumber("1.13.0")));
    }

    @Rule public LoggerRule logging = new LoggerRule().recordPackage(BourneShellScript.class, Level.FINE);

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;

    @Before public void vars() {
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
    }

    @Test
    public void smokeTest() throws Exception {
        Controller c = new BourneShellScript("echo hello world").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
    }

    @Test public void stop() throws Exception {
        // Have observed both SIGTERM and SIGCHLD, perhaps depending on which process (the written sh, or sleep) gets the signal first.
        // TODO without the `trap … EXIT` the other handlers do not seem to get run, and we get exit code 143 (~ uncaught SIGTERM). Why?
        // Also on jenkins.ci neither signal trap is encountered, only EXIT.
        Controller c = new BourneShellScript("trap 'echo got SIGCHLD' CHLD; trap 'echo got SIGTERM' TERM; trap 'echo exiting; exit 99' EXIT; sleep 999").launch(new EnvVars(), ws, launcher, listener);
        Thread.sleep(1000);
        c.stop(ws, launcher);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(99, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(log.contains("sleep 999"));
        assertTrue(log.contains("got SIG"));
        c.cleanup(ws);
    }

    @Test public void reboot() throws Exception {
        FileMonitoringTask.FileMonitoringController c = (FileMonitoringTask.FileMonitoringController) new BourneShellScript("sleep 999").launch(new EnvVars("killemall", "true"), ws, launcher, listener);
        Thread.sleep(1000);
        launcher.kill(Collections.singletonMap("killemall", "true"));
        c.getResultFile(ws).delete();
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(Integer.valueOf(-1), c.exitStatus(ws, launcher, listener));
        assertTrue(log.contains("sleep 999"));
        c.cleanup(ws);
    }

    @Test public void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    @Issue("JENKINS-27152")
    @Test public void cleanWorkspace() throws Exception {
        Controller c = new BourneShellScript("touch stuff && echo ---`ls -1a`---").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
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
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("+ echo 42"));
        assertEquals("42\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Issue("JENKINS-38381")
    @Test public void watch() throws Exception {
        Slave s = j.createOnlineSlave();
        ws = s.getWorkspaceRoot();
        launcher = s.createLauncher(listener);
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
    }
    private static class MockHandler extends Handler {
        private final BlockingQueue<Integer> status;
        private final BlockingQueue<String> output;
        private final BlockingQueue<String> lines;
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
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test public void shebang() throws Exception {
        Controller c = new BourneShellScript("#!/bin/cat\nHello, world!").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("Hello, world!"));
        c.cleanup(ws);
    }

    @Test public void runOnUbuntuDocker() throws Exception {
        JavaContainer container = dockerUbuntu.get();
        runOnDocker(new DumbSlave("docker", "/home/test", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", "")));
    }

    @Test public void runOnCentOSDocker() throws Exception {
        CentOSFixture container = dockerCentOS.get();
        runOnDocker(new DumbSlave("docker", "/home/test", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", "")));
    }

    @Test public void runOnAlpineDocker() throws Exception {
        AlpineFixture container = dockerAlpine.get();
        runOnDocker(new DumbSlave("docker", "/home/test", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", "")), 45);
    }

    private void runOnDocker(DumbSlave s) throws Exception {
        runOnDocker(s, 10);
    }

    private void runOnDocker(DumbSlave s, int sleepSeconds) throws Exception {
        j.jenkins.addNode(s);
        j.waitOnline(s);
        FilePath dockerWS = s.getWorkspaceRoot();
        Launcher dockerLauncher = s.createLauncher(listener);
        String script = String.format("echo hello world; sleep %s", sleepSeconds);
        Controller c = new BourneShellScript(script).launch(new EnvVars(), dockerWS, dockerLauncher, listener);
        while (c.exitStatus(dockerWS, dockerLauncher, listener) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(dockerWS, baos);
        assertEquals(0, c.exitStatus(dockerWS, dockerLauncher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(dockerWS);
        do {
            Thread.sleep(1000);
            baos = new ByteArrayOutputStream();
            assertEquals(0, dockerLauncher.launch().cmds("ps", "-e", "-o", "pid,stat,comm").stdout(new TeeOutputStream(baos, System.out)).join());
        } while (baos.toString().contains(" sleep "));
        assertThat("no zombies running", baos.toString(), not(containsString(" Z ")));
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
    }

    @Test public void runWithCommandLauncher() throws Exception {
        assumeTrue("Docker required for this test", new Docker().isAvailable());
        runOnDocker(new DumbSlave("docker", "/home/jenkins/agent", new SimpleCommandLauncher("docker run -i --rm --name agent jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar")));
    }

    @Test public void runWithTiniCommandLauncher() throws Exception {
        assumeTrue("Docker required for this test", new Docker().isAvailable());
        runOnDocker(new DumbSlave("docker", "/home/jenkins/agent", new SimpleCommandLauncher("docker run -i --rm --name agent --init jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar")));
    }

    @Issue({"JENKINS-31096", "JENKINS-38381"})
    @Test public void encoding() throws Exception {
        JavaContainer container = dockerUbuntu.get();
        DumbSlave s = new DumbSlave("docker", "/home/test", new SSHLauncher(container.ipBound(22), container.port(22), "test", "test", "", "-Dfile.encoding=ISO-8859-1"));
        j.jenkins.addNode(s);
        j.waitOnline(s);
        assertEquals("ISO-8859-1", s.getChannel().call(new DetectCharset()));
        FilePath dockerWS = s.getWorkspaceRoot();
        dockerWS.child("latin").write("¡Ole!\n", "ISO-8859-1");
        dockerWS.child("eastern").write("Čau!\n", "ISO-8859-2");
        dockerWS.child("mixed").write("¡Čau → there!\n", "UTF-8");
        Launcher dockerLauncher = s.createLauncher(listener);
        assertEncoding("control: no transcoding", "latin", null, "¡Ole!", "ISO-8859-1", false, dockerWS, dockerLauncher);
        assertEncoding("test: specify particular charset (UTF-8)", "mixed", "UTF-8", "¡Čau → there!", "UTF-8", dockerWS, dockerLauncher);
        assertEncoding("test: specify particular charset (unrelated)", "eastern", "ISO-8859-2", "Čau!", "UTF-8", dockerWS, dockerLauncher);
        assertEncoding("test: specify agent default charset", "latin", "", "¡Ole!", "UTF-8", dockerWS, dockerLauncher);
        assertEncoding("test: inappropriate charset, some replacement characters", "mixed", "US-ASCII", "����au ��� there!", "UTF-8", dockerWS, dockerLauncher);
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
    }
    private static class DetectCharset extends MasterToSlaveCallable<String, RuntimeException> {
        @Override public String call() throws RuntimeException {
            return Charset.defaultCharset().name();
        }
    }
    private void assertEncoding(String description, String file, String charset, String expected, String expectedEncoding, FilePath dockerWS, Launcher dockerLauncher) throws Exception {
        assertEncoding(description, file, charset, expected, expectedEncoding, false, dockerWS, dockerLauncher);
        assertEncoding(description, file, charset, expected, expectedEncoding, true, dockerWS, dockerLauncher);
    }
    private void assertEncoding(String description, String file, String charset, String expected, String expectedEncoding, boolean watch, FilePath dockerWS, Launcher dockerLauncher) throws Exception {
        assertEncoding(description, file, charset, expected, expectedEncoding, false, watch, dockerWS, dockerLauncher);
        assertEncoding(description, file, charset, expected, expectedEncoding, true, watch, dockerWS, dockerLauncher);
    }
    private void assertEncoding(String description, String file, String charset, String expected, String expectedEncoding, boolean output, boolean watch, FilePath dockerWS, Launcher dockerLauncher) throws Exception {
        BourneShellScript dt = new BourneShellScript("set +x; cat " + file + "; sleep 1; tr '[a-z]' '[A-Z]' < " + file);
        System.err.println(description + " (output=" + output + ", watch=" + watch + ")"); // TODO maybe this should just be moved into a new class and @RunWith(Parameterized.class) for clarity
        if (charset != null) {
            if (charset.isEmpty()) {
                dt.defaultCharset();
            } else {
                dt.charset(Charset.forName(charset));
            }
        }
        if (output) {
            dt.captureOutput();
        }
        Controller c = dt.launch(new EnvVars(), dockerWS, dockerLauncher, listener);
        String expectedUC = expected.toUpperCase(Locale.ENGLISH);
        String fullExpected = expected + "\n" + expectedUC + "\n";
        if (watch) {
            BlockingQueue<Integer> status = new LinkedBlockingQueue<>();
            BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            c.watch(dockerWS, new MockHandler(dockerWS.getChannel(), status, stdout, lines), listener);
            assertEquals(description, "+ set +x", lines.take());
            assertEquals(description, 0, status.take().intValue());
            if (output) {
                assertEquals(description, fullExpected, stdout.take());
                assertEquals(description, "[]", lines.toString());
            } else {
                assertEquals(description, "<no output>", stdout.take());
                assertEquals(description, "[" + expected + ", " + expectedUC + "]", lines.toString());
            }
        } else {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStream tee = new TeeOutputStream(baos, System.err);
            while (c.exitStatus(dockerWS, dockerLauncher, listener) == null) {
                c.writeLog(dockerWS, tee);
                Thread.sleep(100);
            }
            c.writeLog(dockerWS, tee);
            assertEquals(description, 0, c.exitStatus(dockerWS, dockerLauncher, listener).intValue());
            if (output) {
                assertEquals(description, fullExpected, new String(c.getOutput(dockerWS, launcher), expectedEncoding));
            } else {
                assertThat(description, baos.toString(expectedEncoding), containsString(fullExpected));
            }
        }
        c.cleanup(dockerWS);
    }

}
