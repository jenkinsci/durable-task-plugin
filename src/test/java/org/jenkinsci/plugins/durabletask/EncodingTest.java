/*
 * The MIT License
 *
 * Copyright 2018 CloudBees, Inc.
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

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.slaves.DumbSlave;
import hudson.slaves.OfflineCause;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;
import jenkins.security.MasterToSlaveCallable;
import org.apache.commons.io.output.TeeOutputStream;
import static org.hamcrest.Matchers.containsString;
import org.jenkinsci.test.acceptance.docker.DockerClassRule;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.BeforeClass;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;

@Issue({"JENKINS-31096", "JENKINS-52165"})
@RunWith(Parameterized.class)
public class EncodingTest {

    @ClassRule public static JenkinsRule r = new JenkinsRule();

    @ClassRule public static LoggerRule logging = new LoggerRule().recordPackage(BourneShellScript.class, Level.FINE);

    private static DumbSlave s;
    private static StreamTaskListener listener;
    private static FilePath ws;
    private static Launcher launcher;

    @BeforeClass public static void setUp() throws Exception {
        BourneShellScriptTest.unix();
        BourneShellScriptTest.assumeDocker();
        listener = StreamTaskListener.fromStdout();
        launcher = r.jenkins.createLauncher(listener);
        JavaContainer container = new DockerClassRule<>(JavaContainer.class).create();
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(Collections.singletonMap(Domain.global(), Collections.<Credentials>singletonList(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "test", null, "test", "test"))));
        SSHLauncher sshLauncher = new SSHLauncher(container.ipBound(22), container.port(22), "test");
        sshLauncher.setJvmOptions("-Dfile.encoding=ISO-8859-1");
        s = new DumbSlave("docker", "/home/test", sshLauncher);
        r.jenkins.addNode(s);
        r.waitOnline(s);
        assertEquals("ISO-8859-1", s.getChannel().call(new DetectCharset()));
        ws = s.getWorkspaceRoot();
        launcher = s.createLauncher(listener);
    }
    private static class DetectCharset extends MasterToSlaveCallable<String, RuntimeException> {
        @Override public String call() throws RuntimeException {
            return Charset.defaultCharset().name();
        }
    }

    @AfterClass public static void tearDown() throws Exception {
        s.toComputer().disconnect(new OfflineCause.UserCause(null, null));
    }

    public static final class TestCase {
        final String description;
        final String actualEncoding;
        final String actualLine;
        final String charset;
        final String expectedLine;
        final String expectedEncoding;
        TestCase(String description, String actualEncoding, String actualLine, String charset, String expectedLine, String expectedEncoding) {
            this.description = description;
            this.actualEncoding = actualEncoding;
            this.actualLine = actualLine;
            this.charset = charset;
            this.expectedLine = expectedLine;
            this.expectedEncoding = expectedEncoding;
        }
        @Override public String toString() {
            return description;
        }
    }

    private static final TestCase[] CASES = {
        new TestCase("(control) no transcoding", "ISO-8859-1", "¡Ole!", null, "¡Ole!", "ISO-8859-1"),
        new TestCase("specify particular charset (UTF-8)", "UTF-8", "¡Čau → there!", "UTF-8", "¡Čau → there!", "UTF-8"),
        new TestCase("specify particular charset (unrelated)", "ISO-8859-2", "Čau!", "ISO-8859-2", "Čau!", "UTF-8"),
        new TestCase("specify agent default charset", "ISO-8859-1", "¡Ole!", "", "¡Ole!", "UTF-8"),
        new TestCase("inappropriate charset, some replacement characters", "UTF-8", "¡Čau → there!", "US-ASCII", "����au ��� there!", "UTF-8"),
    };

    @Parameterized.Parameter(0) public TestCase testCase;

    @Parameterized.Parameter(1) public boolean output;

    @Parameterized.Parameter(2) public boolean watch;

    @Parameterized.Parameters(name = "testCase={0}, output={1}, watch={2}") public static Iterable<Object[]> parameters() {
        // TODO is there no utility method to produce the cross-product?
        List<Object[]> params = new ArrayList<>();
        for (TestCase testCase : CASES) {
            for (boolean output : new boolean[] {false, true}) {
                for (boolean watch : new boolean[] {false, true}) {
                    params.add(new Object[] {testCase, output, watch});
                }
            }
        }
        return params;
    }

    @Test public void run() throws Exception {
        ws.child("file").write(testCase.actualLine + '\n', testCase.actualEncoding);
        BourneShellScript dt = new BourneShellScript("set +x; cat file; sleep 1; tr '[a-z]' '[A-Z]' < file");
        if (testCase.charset != null) {
            if (testCase.charset.isEmpty()) {
                dt.defaultCharset();
            } else {
                dt.charset(Charset.forName(testCase.charset));
            }
            assertEquals("MockHandler is UTF-8 only", "UTF-8", testCase.expectedEncoding);
        } else {
            assumeFalse("Callers which use watch are expected to also specify an encoding", watch);
        }
        if (output) {
            dt.captureOutput();
        }
        Controller c = dt.launch(new EnvVars(), ws, launcher, listener);
        String expectedUC = testCase.expectedLine.toUpperCase(Locale.ENGLISH);
        String fullExpected = testCase.expectedLine + "\n" + expectedUC + "\n";
        if (watch) {
            BlockingQueue<Integer> status = new LinkedBlockingQueue<>();
            BlockingQueue<String> stdout = new LinkedBlockingQueue<>();
            BlockingQueue<String> lines = new LinkedBlockingQueue<>();
            c.watch(ws, new BourneShellScriptTest.MockHandler(ws.getChannel(), status, stdout, lines), listener);
            assertEquals("+ set +x", lines.take());
            assertEquals(0, status.take().intValue());
            if (output) {
                assertEquals(fullExpected, stdout.take());
                assertEquals("[]", lines.toString());
            } else {
                assertEquals("<no output>", stdout.take());
                assertEquals("[" + testCase.expectedLine + ", " + expectedUC + "]", lines.toString());
            }
        } else {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            OutputStream tee = new TeeOutputStream(baos, System.err);
            while (c.exitStatus(ws, launcher, listener) == null) {
                c.writeLog(ws, tee);
                Thread.sleep(100);
            }
            c.writeLog(ws, tee);
            assertEquals(0, c.exitStatus(ws, launcher, listener).intValue());
            if (output) {
                assertEquals(fullExpected, new String(c.getOutput(ws, launcher), testCase.expectedEncoding));
            } else {
                assertThat(baos.toString(testCase.expectedEncoding), containsString(fullExpected));
            }
        }
        c.cleanup(ws);
    }

}
