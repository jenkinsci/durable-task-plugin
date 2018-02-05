/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
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

package org.jenkinsci.plugins.durabletask.executors;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.queue.QueueTaskFuture;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.junit.Test;
import static org.junit.Assert.*;
import org.junit.Rule;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LoggerRule;
import org.jvnet.hudson.test.TestBuilder;

public class ContinuedTaskTest {

    @Rule public JenkinsRule r = new JenkinsRule();
    @Rule public LoggerRule logging = new LoggerRule().record(ContinuedTask.class, Level.FINER);

    @Test public void basics() throws Exception {
        FreeStyleProject p = r.createFreeStyleProject();
        Label label = Label.get("test");
        p.setAssignedLabel(label);
        final AtomicInteger cntA = new AtomicInteger();
        final AtomicInteger cntB = new AtomicInteger();
        p.getBuildersList().add(new TestBuilder() {
            @Override public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                assertEquals(1, cntB.get());
                return true;
            }
        });
        QueueTaskFuture<FreeStyleBuild> b1 = p.scheduleBuild2(0);
        r.jenkins.getQueue().schedule(new TestTask(cntA, false), 0);
        r.jenkins.getQueue().schedule(new TestTask(cntB, true), 0);
        // cntB task ought to run first, then b1 and the cntA task in either order
        r.createSlave(label);
        r.assertBuildStatusSuccess(b1);
        r.waitUntilNoActivity();
        assertEquals(1, cntA.get());
        assertEquals(1, cntB.get());
    }

    private static final class TestTask extends MockTask implements ContinuedTask {
        private final boolean continued;
        TestTask(AtomicInteger cnt, boolean continued) {
            super(cnt);
            this.continued = continued;
        }
        @Override public boolean isContinued() {
            return continued;
        }
        @Override public Label getAssignedLabel() {
            return Label.get("test");
        }
        @Override public String toString() {
            return "TestTask:" + continued;
        }
    }

}
