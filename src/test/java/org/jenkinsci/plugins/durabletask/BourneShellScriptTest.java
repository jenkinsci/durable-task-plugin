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

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.ExtensionList;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Platform;
import hudson.Proc;
import hudson.model.Node;
import hudson.model.Slave;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.remoting.Channel;
import hudson.remoting.TeeOutputStream;
import hudson.remoting.VirtualChannel;
import hudson.slaves.DumbSlave;
import hudson.tasks.Shell;
import hudson.util.StreamTaskListener;
import hudson.util.VersionNumber;
import jenkins.security.MasterToSlaveCallable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Serial;
import java.io.StringWriter;
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

import static hudson.Functions.isWindows;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.apache.commons.io.IOUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

@WithJenkins
abstract class BourneShellScriptTest {

    protected JenkinsRule j;

    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder().recordPackage(BourneShellScript.class, Level.FINEST);
    private final TestPlatform platform = getPlatform();
    private final StreamTaskListener listener = StreamTaskListener.fromStdout();

    private GenericContainer<?> container;
    private Node s;
    private FilePath ws;
    private Launcher launcher;

    enum TestPlatform {
        ON_CONTROLLER, NATIVE, ALPINE, CENTOS, UBUNTU, NO_INIT, UBUNTU_NO_BINARY, SLIM
    }

    static void assumeDocker() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is available");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assumeTrue(new Launcher.LocalLauncher(StreamTaskListener.fromStderr()).launch().cmds("docker", "version", "--format", "{{.Client.Version}}")
                .stdout(new TeeOutputStream(baos, System.err)).stderr(System.err).join() == 0, "`docker version` could be run");
        assumeTrue(new VersionNumber(baos.toString().trim()).isNewerThanOrEqualTo(new VersionNumber("1.13.0")), "Docker must be at least 1.13.0 for this test (uses --init)");
    }

    protected boolean useBinaryWrapper() {
        return true;
    }

    protected Node createNode() throws Exception {
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(Collections.singletonMap(
                Domain.global(), Collections.singletonList(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "test", null, "test", "test"))));
        var sshLauncher = new SSHLauncher(container.getHost(), container.getMappedPort(22), "test");
        sshLauncher.setJavaPath(customJavaPath());
        return new DumbSlave("docker", "/home/test", sshLauncher);
    }

    protected abstract TestPlatform getPlatform();

    protected GenericContainer<?> createContainer() {
        return null;
    }

    protected String customJavaPath() {
        return null;
    }

    @BeforeAll
    static void beforeAll() {
        assumeFalse(isWindows(), "This test is only for Unix");
    }

    @BeforeEach
    void beforeEach(JenkinsRule rule) throws Exception {
        j = rule;

        container = createContainer();
        if (container != null) {
            assumeDocker();
            container.start();
        }

        BourneShellScript.USE_BINARY_WRAPPER = useBinaryWrapper();
        s = createNode();
        if (s instanceof Slave slave) {
            j.jenkins.addNode(s);
            j.waitOnline(slave);
            ws = slave.getWorkspaceRoot().child("ws");
        } else {
            ws = j.jenkins.getRootPath().child("ws");
        }
        launcher = s.createLauncher(listener);
    }

    @AfterEach
    void afterEach() throws Exception {
        if (s != null) {
            j.jenkins.removeNode(s);
        }
        BourneShellScript.USE_BINARY_WRAPPER = false;

        if (container != null) {
            container.stop();
        }
    }

    @Test
    void smokeTest() throws Exception {
        int sleepSeconds = switch (platform) {
            case ON_CONTROLLER, NATIVE -> 0;
            case CENTOS, UBUNTU, UBUNTU_NO_BINARY, NO_INIT -> 10;
            case ALPINE, SLIM -> 45;
        };

        String script = String.format("echo hello world; sleep %s", sleepSeconds);
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        assertNoZombies();
        assertNoProcessPipeInputStreamInRemoteExportTable();
    }

    @Test
    void stop() throws Exception {
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

    @Test
    void reboot() throws Exception {
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

    @Test
    void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
        assertNoZombies();
    }

    @Issue("JENKINS-27152")
    @Test
    void cleanWorkspace() throws Exception {
        Controller c = new BourneShellScript("touch stuff && echo ---`ls -1a`---").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("---. .. stuff---"));
        c.cleanup(ws);
    }

    @Issue("JENKINS-26133")
    @Test
    void output() throws Exception {
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
    @Test
    void watch() throws Exception {
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

        @Override
        public void output(@NonNull InputStream stream) throws Exception {
            lines.addAll(IOUtils.readLines(stream, StandardCharsets.UTF_8));
        }

        @Override
        public void exited(int code, byte[] data) throws Exception {
            status.add(code);
            output.add(data != null ? new String(data, StandardCharsets.UTF_8) : "<no output>");
        }
    }

    @Issue("JENKINS-40734")
    @Test
    void envWithShellChar() throws Exception {
        Controller c = new BourneShellScript("echo \"value=$MYNEWVAR\"").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test
    void shebang() throws Exception {
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
    @Test
    void configuredInterpreter() throws Exception {
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
    @Test
    void noStdout() throws Exception {
        assumeTrue(!platform.equals(TestPlatform.UBUNTU_NO_BINARY));
        System.setProperty(BourneShellScript.class.getName() + ".LAUNCH_DIAGNOSTICS", "true");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TeeOutputStream teeOut = new TeeOutputStream(baos, System.out);
        StreamTaskListener stdoutListener = new StreamTaskListener(teeOut, Charset.defaultCharset());
        String script = "echo hello world";
        Controller c = new BourneShellScript(script).launch(new EnvVars(), ws, launcher, stdoutListener);
        awaitCompletion(c);
        assertThat(baos.toString(), emptyString());
        c.cleanup(ws);
        System.clearProperty(BourneShellScript.class.getName() + ".LAUNCH_DIAGNOSTICS");
    }

    @Issue("JENKINS-58290")
    @Test
    void backgroundLaunch() throws IOException, InterruptedException {
        int sleepSeconds = switch (platform) {
            case ON_CONTROLLER, NATIVE, CENTOS, UBUNTU, UBUNTU_NO_BINARY, NO_INIT -> 10;
            case ALPINE, SLIM -> 45;
        };
        final AtomicReference<Proc> proc = new AtomicReference<>();
        Launcher decorated = new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(Launcher.ProcStarter starter) throws IOException {
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
                assertFalse(proc.get().isAlive(), "JENKINS-58290: wrapper process still running:\n" + baos);
            }
            Thread.sleep(100);
        }
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(ws);
        assertNoZombies();
    }

    @Test
    void binaryCaching() throws Exception {
        assumeFalse(platform.equals(TestPlatform.UBUNTU_NO_BINARY));
        String os;
        String architecture;
        switch (platform) {
            case ON_CONTROLLER, NATIVE:
                if (Platform.isDarwin()) {
                    os = "darwin";
                    String macArch = System.getProperty("os.arch");
                    if (macArch.contains("aarch") || macArch.contains("arm")) {
                        architecture = "arm";
                    } else if (macArch.contains("amd") || macArch.contains("x86")) {
                        architecture = "amd";
                    } else {
                        architecture = "NOTSUPPORTED";
                    }
                } else {
                    os = "linux";
                    architecture = "";
                }
                break;
            default:
                os = "linux";
                architecture = "";
        }
        String bits = System.getProperty("sun.arch.data.model");
        if (bits.equals("64")) {
            architecture += "64";
        } else {
            architecture += "32";
        }

        String version = j.getPluginManager().getPlugin("durable-task").getVersion();
        if (version != null && version.contains("-")) {
            version = version.substring(0, version.indexOf("-"));
        }
        String binaryName = "durable_task_monitor_" + version + "_" + os + "_" + architecture;
        FilePath binaryPath = s.getRootPath().child("caches/durable-task/" + binaryName);
        assertFalse(binaryPath.exists());

        BourneShellScript script = new BourneShellScript("echo hello");
        EnvVars envVars = new EnvVars();
        Controller c = script.launch(envVars, ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertTrue(binaryPath.exists());
        Long timeCheck1 = binaryPath.lastModified();

        c = script.launch(envVars, ws, launcher, listener);
        awaitCompletion(c);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        Long timeCheck2 = binaryPath.lastModified();
        assertEquals(timeCheck1, timeCheck2);

        binaryPath.delete();
        binaryPath.touch(Instant.now().toEpochMilli());

        IOException e = assertThrows(IOException.class, () -> script.launch(envVars, ws, launcher, listener), "binary was copied over");
        assertThat(e.getMessage(), containsString("Cannot run program"));
    }

    /**
     * Returns the first zombie process it finds.
     *
     * @return String `ps` line output of the zombie process found. Empty string otherwise.
     * @throws InterruptedException
     * @throws IOException
     */
    private void assertNoZombies() throws InterruptedException, IOException {
        String exitString;
        String zombieString;
        switch (platform) {
            case ON_CONTROLLER:
            case NATIVE:
                return; // cannot control the CI environment
            case SLIM:
                // Debian slim does not have ps
            case NO_INIT:
                // (See JENKINS-58656) Running in a container with no init process is guaranteed to leave a zombie.
                return;
            case UBUNTU_NO_BINARY:
                exitString = " sleep ";
                zombieString = ".+ Z .+";
                break;
            default:
                exitString = "sh -xe " + ws.getRemote();
                zombieString = ".+Z[s|\\s]\\s*\\[durable.+";
        }

        String psFormat = setPsFormat();
        String psString;
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
        String cmdCol = switch (platform) {
            case ON_CONTROLLER, NATIVE -> Platform.isDarwin() ? "comm" : "cmd";
            case ALPINE -> "args";
            default -> "cmd";
        };
        return "pid,ppid,stat," + cmdCol;
    }

    private void awaitCompletion(Controller c) throws IOException, InterruptedException {
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
    }

    /**
     * Check that the agent ExportTable does not retain references to ProcessPipeInputStream.
     *
     * @throws IOException
     * @throws InterruptedException
     */
    @Issue("JENKINS-60960")
    private void assertNoProcessPipeInputStreamInRemoteExportTable()
            throws IOException, InterruptedException {
        VirtualChannel virtualChannel = launcher.getChannel();
        if (virtualChannel instanceof Channel channel) {
            String remoteExportTable = channel.call(new DumpExportTableCallable());
            if (remoteExportTable.contains("object=java.lang.UNIXProcess$ProcessPipeInputStream")) {
                fail("remote ExportTable contains some java.lang.UNIXProcess$ProcessPipeInputStream objects\n"
                        + remoteExportTable);
            }
        }
    }

    private static final class DumpExportTableCallable extends MasterToSlaveCallable<String, IOException> {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public String call() throws IOException {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            Channel channel = getChannelOrFail();
            channel.dumpExportTable(pw);
            return sw.toString();
        }
    }

}
