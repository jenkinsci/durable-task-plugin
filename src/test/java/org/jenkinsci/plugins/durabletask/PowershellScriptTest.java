/*
 * The MIT License
 *
 * Copyright 2017 Gabriel Loewen
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
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.containsString;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

@Issue("JENKINS-34581")
public class PowershellScriptTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    private StreamTaskListener listener;
    private FilePath ws;
    private Launcher launcher;

    @Before public void vars() {
        listener = StreamTaskListener.fromStdout();
        ws = j.jenkins.getRootPath().child("ws");
        launcher = j.jenkins.createLauncher(listener);
    }

    @Test public void spaceInPath() throws Exception {
        testWithPath("space in path");
    }

    @Test public void spaceInPath2() throws Exception {
        testWithPath("space in path@2");
    }

    @Test public void percentInPath() throws Exception {
        testWithPath("percent%in%path");
    }

    private void testWithPath(String path) throws Exception {
        Controller c = new PowershellScript("Write-Output \"hello world\"").launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(Integer.valueOf(0), c.exitStatus(ws, launcher));
        String log = baos.toString();
        System.err.print(log);
        assertTrue(log, log.contains("hello world"));
        c.cleanup(ws);
    }

    @Test public void exitCommand() throws Exception {
        Controller c = new PowershellScript("Write-Output \"hello world\"; exit 1;").launch(new EnvVars(), ws, launcher, listener);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        TeeOutputStream tos = new TeeOutputStream(baos, System.err);
        while (c.exitStatus(ws, launcher) == null) {
            c.writeLog(ws, tos);
            Thread.sleep(100);
        }
        c.writeLog(ws, tos);
        assertEquals(Integer.valueOf(1), c.exitStatus(ws, launcher));
        String log = baos.toString();
        assertTrue(log, log.contains("hello world"));
        c.cleanup(ws);
    }

    @Test public void output() throws Exception {
        DurableTask task = new PowershellScript("Write-Output \"42\"");
        task.captureOutput();
        Controller c = task.launch(new EnvVars(), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertEquals("42\r\n", new String(c.getOutput(ws, launcher)));
        c.cleanup(ws);
    }

    @Test public void envWithShellChar() throws Exception {
        Controller c = new PowershellScript("echo value=$env:MYNEWVAR").launch(new EnvVars("MYNEWVAR", "foo$$bar"), ws, launcher, listener);
        while (c.exitStatus(ws, launcher) == null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);
        assertEquals(0, c.exitStatus(ws, launcher).intValue());
        assertThat(baos.toString(), containsString("value=foo$$bar"));
        c.cleanup(ws);
    }

}
