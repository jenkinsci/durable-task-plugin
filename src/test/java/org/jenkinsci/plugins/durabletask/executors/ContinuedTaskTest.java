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

import hudson.ExtensionList;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Queue;
import hudson.model.queue.QueueTaskFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.LogRecorder;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;

@WithJenkins
class ContinuedTaskTest {

    @SuppressWarnings("unused")
    private final LogRecorder logging = new LogRecorder().record(ContinuedTask.class, Level.FINER);

    private JenkinsRule j;

    @BeforeEach
    void beforeEach(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void basics() throws Exception {
        FreeStyleProject p = j.createFreeStyleProject();
        Label label = Label.get("test");
        p.setAssignedLabel(label);
        final AtomicInteger cntA = new AtomicInteger();
        final AtomicInteger cntB = new AtomicInteger();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
                assertEquals(1, cntB.get());
                return true;
            }
        });
        QueueTaskFuture<FreeStyleBuild> b1 = p.scheduleBuild2(0);
        j.jenkins.getQueue().schedule(new TestTask(cntA, false), 0);
        j.jenkins.getQueue().schedule(new TestTask(cntB, true), 0);
        // cntB task ought to run first, then b1 and the cntA task in either order
        j.createSlave(label);
        j.assertBuildStatusSuccess(b1);
        j.waitUntilNoActivity();
        assertEquals(1, cntA.get());
        assertEquals(1, cntB.get());
    }

    @Test
    void counterTracksContinuedTaskBuildableLifecycle() throws Exception {
        int initial = ContinuedTask.Scheduler.continuedBuildableCount();
        long id = j.jenkins.getQueue().schedule(new TestTask(new AtomicInteger(), true), 0).getId();
        j.jenkins.getQueue().maintain();
        waitForCounter(initial + 1);
        assertEquals(initial + 1, ContinuedTask.Scheduler.continuedBuildableCount());

        cancelById(id);
        j.jenkins.getQueue().maintain();
        waitForCounter(initial);
        assertEquals(initial, ContinuedTask.Scheduler.continuedBuildableCount());
    }

    @Test
    void counterIgnoresNonContinuedTasks() throws Exception {
        int initial = ContinuedTask.Scheduler.continuedBuildableCount();
        long id = j.jenkins.getQueue().schedule(new LabelledMockTask(new AtomicInteger()), 0).getId();
        j.jenkins.getQueue().maintain();
        // Give the listener a window to fire if it were buggy.
        Thread.sleep(200);
        assertEquals(initial, ContinuedTask.Scheduler.continuedBuildableCount());
        cancelById(id);
    }

    @Test
    void counterClampsAtZeroOnSpuriousLeaveBuildable() throws Exception {
        // Drive the counter to 1, capture a real BuildableItem, then cancel so the counter goes
        // back to 0. The retained item reference lets us simulate a duplicate/stray
        // onLeaveBuildable callback, which must clamp at 0 rather than going negative.
        long id = j.jenkins.getQueue().schedule(new TestTask(new AtomicInteger(), true), 0).getId();
        j.jenkins.getQueue().maintain();
        waitForCounter(1);
        Queue.BuildableItem captured = (Queue.BuildableItem) j.jenkins.getQueue().getItem(id);
        cancelById(id);
        j.jenkins.getQueue().maintain();
        waitForCounter(0);
        assertEquals(0, ContinuedTask.Scheduler.continuedBuildableCount());

        // Spurious extra callback: counter would go to -1 without the clamp.
        ContinuedTask.Scheduler.CountingListener listener =
                ExtensionList.lookupSingleton(ContinuedTask.Scheduler.CountingListener.class);
        listener.onLeaveBuildable(captured);
        assertEquals(0, ContinuedTask.Scheduler.continuedBuildableCount());

        // And again — still clamped.
        listener.onLeaveBuildable(captured);
        assertEquals(0, ContinuedTask.Scheduler.continuedBuildableCount());
    }

    @Test
    void counterIgnoresLeaveBuildableForNonContinuedTaskAtZero() throws Exception {
        // Schedule, capture, cancel — same pattern as above, but with a non-ContinuedTask.
        // The leave callback must not warn (item isn't a ContinuedTask) and the counter must
        // remain at 0.
        assertEquals(0, ContinuedTask.Scheduler.continuedBuildableCount());
        long id = j.jenkins.getQueue().schedule(new LabelledMockTask(new AtomicInteger()), 0).getId();
        j.jenkins.getQueue().maintain();
        Queue.BuildableItem captured = (Queue.BuildableItem) j.jenkins.getQueue().getItem(id);
        cancelById(id);

        ContinuedTask.Scheduler.CountingListener listener =
                ExtensionList.lookupSingleton(ContinuedTask.Scheduler.CountingListener.class);
        listener.onLeaveBuildable(captured);
        assertEquals(0, ContinuedTask.Scheduler.continuedBuildableCount());
    }

    private void cancelById(long id) {
        Queue.Item current = j.jenkins.getQueue().getItem(id);
        if (current != null) {
            j.jenkins.getQueue().cancel(current);
        }
    }

    private static void waitForCounter(int target) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (ContinuedTask.Scheduler.continuedBuildableCount() == target) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static final class TestTask extends MockTask implements ContinuedTask {
        private final boolean continued;

        TestTask(AtomicInteger cnt, boolean continued) {
            super(cnt);
            this.continued = continued;
        }

        @Override
        public boolean isContinued() {
            return continued;
        }

        @Override
        public Label getAssignedLabel() {
            return Label.get("test");
        }

        @Override
        public String toString() {
            return "TestTask:" + continued;
        }
    }

    /** Non-ContinuedTask pinned to a label with no agent, so it stays in the queue. */
    private static final class LabelledMockTask extends MockTask {
        LabelledMockTask(AtomicInteger cnt) {
            super(cnt);
        }

        @Override
        public Label getAssignedLabel() {
            return Label.get("nonexistent-counter-test-label");
        }
    }

}
