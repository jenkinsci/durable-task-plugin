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
import java.io.ByteArrayOutputStream;
import java.io.File;
import static org.hamcrest.Matchers.containsString;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.Arrays;

@RunWith(Parameterized.class)
public class WindowsBatchScriptTest {
    @Parameterized.Parameters(name = "{index}: USE_BINARY={0}")
    public static Object[] parameters() {
        return new Object[] {true, false};
    }

    @Rule public JenkinsRule j = new JenkinsRule();

    @BeforeClass public static void windows() {
        Assume.assumeTrue("These tests are only for Windows", File.pathSeparatorChar == ';');
    }

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;
    private boolean useBinary;

    public WindowsBatchScriptTest(boolean useBinary) {
        this.useBinary = useBinary;
    }

    @Before public void vars() {
        WindowsBatchScript.USE_SCRIPT_WRAPPER = !useBinary;
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
    }

    @Issue("JENKINS-25678")
    @Test public void spaceInPath() throws Exception {
        testWithPath("space in path");
    }

    @Issue("JENKINS-25678")
    @Test public void spaceInPath2() throws Exception {
        testWithPath("space in path@2");
    }

    @Issue("JENKINS-32701")
    @Test public void percentInPath() throws Exception {
        testWithPath("percent%in%path");
    }

    private void testWithPath(String path) throws Exception {
        FilePath wsWithPath = ws.child(path);
        Controller c = new WindowsBatchScript("echo hello world").launch(new EnvVars(), wsWithPath, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(wsWithPath, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(wsWithPath, launcher, listener));
        String log = baos.toString();
        System.err.print(log);
        assertTrue(log, log.contains("hello world"));
        c.cleanup(wsWithPath);
    }

    @Issue("JENKINS-27419")
    @Test public void exitCommand() throws Exception {
        Controller c = new WindowsBatchScript("echo hello world\r\nexit 1").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher, listener));
        String log = baos.toString();
        assertTrue(log, log.contains("hello world"));
        c.cleanup(ws);
    }

    @Test public void exitBCommandAfterError() throws Exception {
        Controller c = new WindowsBatchScript("cmd /c exit 42\r\nexit /b").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(42, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    @Issue("JENKINS-26133")
    @Test public void output() throws Exception {
        DurableTask task = new WindowsBatchScript("@echo 42"); // http://stackoverflow.com/a/8486061/12916
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertEquals("42\r\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Issue("JENKINS-40734")
    @Test public void envWithShellChar() throws Exception {
        Controller c = new WindowsBatchScript("echo value=%MYNEWVAR%").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

    @Test public void scriptAffectedByLineEndings() throws Exception {
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
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        c.cleanup(ws);
    }

    @Test public void unicodeChars() throws Exception {
        Controller c = new WindowsBatchScript("echo Helló, Wõrld ®").launch(new EnvVars(), ws, launcher, listener);
        awaitCompletion(c);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
        String log = baos.toString("UTF-8");
        assertTrue(log, log.contains("Helló, Wõrld ®"));
        c.cleanup(ws);
    }

    private void awaitCompletion(Controller c) throws IOException, InterruptedException {
        while (c.exitStatus(ws, launcher, listener) == null) {
            Thread.sleep(100);
        }
    }

}
