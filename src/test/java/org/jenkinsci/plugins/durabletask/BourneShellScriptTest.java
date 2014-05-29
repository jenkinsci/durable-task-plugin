package org.jenkinsci.plugins.durabletask;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.util.StreamTaskListener;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.File;

/**
 * @author Kohsuke Kawaguchi
 */
public class BourneShellScriptTest extends Assert {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void smokeTest() throws Exception {
        Assume.assumeTrue("This test is only for Unix", File.pathSeparatorChar==':');

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
}
