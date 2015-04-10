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
import hudson.util.StreamTaskListener;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import org.junit.Before;

public class BourneShellScriptTest extends Assert {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before public void unix() {
        Assume.assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');
    }

    @Test
    public void smokeTest() throws Exception {
        StreamTaskListener l = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath();

        Controller c = new BourneShellScript("echo hello world").launch(
                new EnvVars(),
                ws,
                j.jenkins.createLauncher(l), l
        );

        while (c.exitStatus(ws)==null) {
            Thread.sleep(100);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws,baos);

        assertEquals(0, c.exitStatus(ws).intValue());
        assertTrue(baos.toString().contains("hello world"));

        c.cleanup(ws);
    }

    @Test public void stop() throws Exception {
        StreamTaskListener listener = StreamTaskListener.fromStdout();
        FilePath ws = j.jenkins.getRootPath();
        Launcher launcher = j.jenkins.createLauncher(listener);
        // Have observed both SIGTERM and SIGCHLD, perhaps depending on which process (the written sh, or sleep) gets the signal first.
        // TODO without the `trap … EXIT` the other handlers do not seem to get run, and we get exit code 143 (~ uncaught SIGTERM). Why?
        Controller c = new BourneShellScript("trap 'echo got SIGCHLD; exit 99' CHLD; trap 'echo got SIGTERM; exit 99' TERM; trap 'echo exiting' EXIT; sleep 999").launch(new EnvVars(), ws, launcher, listener);
        Thread.sleep(1000);
        c.stop(ws, launcher);
        while (c.exitStatus(ws)==null) {
            Thread.sleep(100);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        c.writeLog(ws, baos);
        String log = baos.toString();
        System.out.println(log);
        assertEquals(99, c.exitStatus(ws).intValue());
        assertTrue(log.contains("sleep 999"));
        assertTrue(log.contains("got SIG"));
        c.cleanup(ws);
    }

}
