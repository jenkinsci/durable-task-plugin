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
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.logging.Level;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.test.acceptance.docker.Docker;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Before;
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

    @Before public void unix() {
        assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');
    }

    @Rule public LoggerRule logging = new LoggerRule().record(BourneShellScript.class, Level.FINE);

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
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
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
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(99, c.exitStatus(ws, launcher).intValue());
        assertTrue(log.contains("sleep 999"));
        assertTrue(log.contains("got SIG"));
        c.cleanup(ws);
    }

    @Test public void reboot() throws Exception {
        FileMonitoringTask.FileMonitoringController c = (FileMonitoringTask.FileMonitoringController) new BourneShellScript("sleep 999").launch(new EnvVars("killemall", "true"), ws, launcher, listener);
        Thread.sleep(1000);
        launcher.kill(Collections.singletonMap("killemall", "true"));
        c.getResultFile(ws).delete();
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(Integer.valueOf(-1), c.exitStatus(ws, launcher));
        assertTrue(log.contains("sleep 999"));
        c.cleanup(ws);
    }

    @Test public void justSlow() throws Exception {
        Controller c = new BourneShellScript("sleep 60").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        c.writeLog(ws, System.out);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        c.cleanup(ws);
    }

    @Issue("JENKINS-27152")
    @Test public void cleanWorkspace() throws Exception {
        Controller c = new BourneShellScript("touch stuff && echo ---`ls -1a`---").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertThat(baos.toString(), containsString("---. .. stuff---"));
        c.cleanup(ws);
    }

    @Issue("JENKINS-26133")
    @Test public void output() throws Exception {
        DurableTask task = new BourneShellScript("echo 42");
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertThat(baos.toString(), containsString("+ echo 42"));
        assertEquals("42\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Issue("JENKINS-40734")
    @Test public void envWithShellChar() throws Exception {
        Controller c = new BourneShellScript("echo \"value=$MYNEWVAR\"").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test public void shebang() throws Exception {
        Controller c = new BourneShellScript("#!/bin/cat\nHello, world!").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, new TeeOutputStream(baos, System.out));
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
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

    private void runOnDocker(DumbSlave s) throws Exception {
        j.jenkins.addNode(s);
        j.waitOnline(s);
        FilePath dockerWS = s.getWorkspaceRoot();
        Launcher dockerLauncher = s.createLauncher(listener);
        Controller c = new BourneShellScript("echo hello world; sleep 10").launch(new EnvVars(), dockerWS, dockerLauncher, listener);
        while (c.exitStatus(dockerWS, dockerLauncher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(dockerWS, baos);
        assertEquals(0, c.exitStatus(dockerWS, dockerLauncher).intValue());
        assertTrue(baos.toString().contains("hello world"));
        c.cleanup(dockerWS);
        do {
            Thread.sleep(1000);
            baos = new ByteArrayOutputStream();
            assertEquals(0, dockerLauncher.launch().cmds("ps", "-e", "-o", "pid,stat,command").stdout(new TeeOutputStream(baos, System.out)).join());
        } while (baos.toString().contains(" sleep "));
        assertThat("no zombies running", baos.toString(), not(containsString(" Z ")));
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
    }

    @Test public void runWithCommandLauncher() throws Exception {
        assumeTrue("Docker required for this test", new Docker().isAvailable());
        runOnDocker(new DumbSlave("docker", "/home/jenkins/agent", new SimpleCommandLauncher("docker run -i --rm --name agent jenkinsci/slave:3.7-1 java -jar /usr/share/jenkins/slave.jar")));
    }

}
