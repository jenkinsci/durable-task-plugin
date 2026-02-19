/*
 * The MIT License
 *
 * Copyright 2015 Jesse Glick.
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
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.ValueSource;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static hudson.Functions.isWindows;
import java.time.Duration;
import org.apache.commons.io.output.TeeOutputStream;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@WithJenkins
@EnabledOnOs(OS.WINDOWS)
@ParameterizedClass(name = "{index}: USE_BINARY={0}")
@ValueSource(booleans = {true, false})
class WindowsBatchScriptTest {

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;

    @Parameter
    private boolean enableBinary;

    private JenkinsRule j;

    @BeforeAll
    static void beforeAll() {
        assumeTrue(isWindows(), "This test is only for Windows");
    }

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;

        WindowsBatchScript.USE_BINARY_WRAPPER = enableBinary;
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
    }

    @Issue("JENKINS-25678")
    @Test
    void spaceInPath() throws Exception {
        testWithPath("space in path");
    }

    @Issue("JENKINS-25678")
    @Test
    void spaceInPath2() throws Exception {
        testWithPath("space in path@2");
    }

    @Issue("JENKINS-32701")
    @Test
    void percentInPath() throws Exception {
        testWithPath("percent%in%path");
    }

    private void testWithPath(String path) throws Exception {
        FilePath wsWithPath = ws.child(path);
        Controller c = new WindowsBatchScript("echo hello world").launch(new EnvVars(), wsWithPath, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(wsWithPath, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(wsWithPath, launcher, listener));
        String log = baos.toString();
        System.err.print(log);
        assertTrue(log.contains("hello world"), log);
        c.cleanup(wsWithPath);
    }

    @Issue("JENKINS-27419")
    @Test
    void exitCommand() throws Exception {
        Controller c = new WindowsBatchScript("echo hello world\r\nexit 1").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher, listener));
        String log = baos.toString();
        assertTrue(log.contains("hello world"), log);
        c.cleanup(ws);
    }

    @Test
    void exitCommandUnsignedInt() throws Exception {
        Controller c = new WindowsBatchScript("echo hello world\r\nexit 3221225477").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        if (enableBinary) {
            assertEquals(Integer.valueOf(Integer.MAX_VALUE), c.exitStatus(ws, launcher, listener));
        } else {
            assertEquals(Integer.valueOf(-1073741819), c.exitStatus(ws, launcher, listener));
        }

        String log = baos.toString();
        assertTrue(log.contains("hello world"), log);
        c.cleanup(ws);
    }

    @Test
    void exitBCommandAfterError() throws Exception {
        Controller c = new WindowsBatchScript("cmd /c exit 42\r\nexit /b").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(42, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    @Issue("JENKINS-26133")
    @Test
    void output() throws Exception {
        DurableTask task = new WindowsBatchScript("@echo 42"); // http://stackoverflow.com/a/8486061/12916
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertEquals("42\r\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Issue("JENKINS-40734")
    @Test
    void envWithShellChar() throws Exception {
        Controller c = new WindowsBatchScript("echo value=%MYNEWVAR%").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test
    void scriptAffectedByLineEndings() throws Exception {
        char[] pad254 = new char[254];
        char[] pad231 = new char[231];
        Arrays.fill(pad254, '#');
        Arrays.fill(pad231, '#');
        // script is found on https://www.dostips.com/forum/viewtopic.php?f=3&t=8988#p58860
        String script =
                "@echo off\n" +
                        "goto :zwei\n" +
                        ":" + new String(pad254) + "\n" +
                        ":" + new String(pad254) + "\n" +
                        ":" + new String(pad254) + "\n" +
                        ":" + new String(pad231) + "\n" +
                        "\n" +
                        ":eins\n" +
                        "ECHO eins\n" +
                        "goto :eof\n" +
                        "\n" +
                        ":zwei\n" +
                        "echo zwei\n" +
                        "goto :eins\n";
        Controller c = new WindowsBatchScript(script).launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    @Test
    void unicodeChars() throws Exception {
        Controller c = new WindowsBatchScript("echo Helló, Wõrld ®").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c, ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        String log = baos.toString(StandardCharsets.UTF_8);
        assertTrue(log.contains("Helló, Wõrld ®"), log);
        c.cleanup(ws);
    }

    static void awaitCompletion(Controller c, FilePath ws, Launcher launcher, StreamTaskListener listener) throws IOException, InterruptedException {
        await().atMost(Duration.ofMinutes(2)).until(() -> c.exitStatus(ws, launcher, listener), notNullValue());
        await().atMost(Duration.ofMinutes(1)).pollInterval(Duration.ofMillis(500)).until(() -> binaryInactive(launcher));
    }

    /**
     * Determines if the windows binary is not running by checking the tasklist
     */
    private static boolean binaryInactive(Launcher launcher) throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int status = launcher.launch().cmds("tasklist", "/fi", "\"imagename eq durable_task_monitor_*\"").stdout(new TeeOutputStream(baos, System.err)).join();
        var out = baos.toString();
        if (status == 1 && out.contains("Access denied")) {
            return true; // https://github.com/jenkinsci/durable-task-plugin/pull/541
        }
        assertEquals(0, status, out);
        return out.contains("No tasks");
    }

}
