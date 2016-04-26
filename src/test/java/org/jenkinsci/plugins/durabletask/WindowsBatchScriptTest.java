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
import java.util.Arrays;
import java.util.List;

import org.apache.commons.io.output.TeeOutputStream;
import static org.junit.Assert.*;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class WindowsBatchScriptTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @BeforeClass public static void windows() {
        Assume.assumeTrue("These tests are only for Windows", File.pathSeparatorChar == ';');
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
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath().child(path);
        Launcher launcher = j.jenkins.createLauncher(listener);
        Controller c = new WindowsBatchScript("echo hello world").launch(new EnvVars(), ws, launcher, listener);
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

    @Issue("JENKINS-27419")
    @Test public void exitCommand() throws Exception {
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath().child("ws");
        Launcher launcher = j.jenkins.createLauncher(listener);
        Controller c = new WindowsBatchScript("echo hello world\r\nexit 1").launch(new EnvVars(), ws, launcher, listener);
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

    @Issue("JENKINS-34150")
    @Test(timeout = 5000) public void batchScriptHanging() throws Exception {
        final int concurrency = 4;

        StreamTaskListener listener = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath().child("ws");
        Launcher launcher = j.jenkins.createLauncher(listener);
        WindowsBatchScript script = new WindowsBatchScript("ping 127.0.0.1 -n 2");

        List<Controller> controllers = Arrays.asList(new Controller[concurrency]);
        EnvVars envVars = new EnvVars();
        for (int i = 0; i < concurrency; i++) {
            Controller controller = script.launch(envVars, ws, launcher, listener);
            controller.writeLog(ws, System.out);
            controllers.set(i, controller);
        }

        boolean stillRunning = true;
        while (stillRunning) {
            stillRunning = false;
            for (int i = 0; i < concurrency; i++) {
                Controller controller = controllers.get(i);
                boolean running = (controller.exitStatus(ws, launcher) == null);
                stillRunning |= running;
            }
        }

        for (int i = 0; i < concurrency; i++) {
            controllers.get(i).cleanup(ws);
        }
    }

    /**
     * This issue came up while trying to fix JENKINS-34150. The problem was that the termination of the batch file
     * did not work properly anymore. This is the reason why it is referencing to the same issue.
     *
     * @throws Exception
     */
    @Issue("JENKINS-34150")
    @Test(timeout=500000) public void batchScriptForcedTerminate() throws Exception {
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath().child("ws");
        Launcher launcher = j.jenkins.createLauncher(listener);
        WindowsBatchScript script = new WindowsBatchScript("TIMEOUT /T 10000");

        EnvVars envVars = new EnvVars();
        Controller controller = script.launch(envVars, ws, launcher, listener);
        controller.writeLog(ws, System.out);

        Thread.sleep(1000);

        Integer exitStatus;
        if ((exitStatus = controller.exitStatus(ws, launcher)) != null) {
            fail("Process already finished. Something went badly wrong. Got exit code: " + exitStatus.toString());
        }

        controller.stop(ws, launcher);

        /* Now we check the remaining time for the exit status */
        while ((exitStatus = controller.exitStatus(ws, launcher)) == null) {
            Thread.sleep(1);
        }

        assertEquals(exitStatus.intValue(), -1);
    }
}
