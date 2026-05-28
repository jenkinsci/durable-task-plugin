/*
 * The MIT License
 *
 * Copyright (c) 2013-2014, CloudBees, Inc.
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

import hudson.Extension;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueListener;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Marker for tasks which should perhaps “jump ahead” in the queue because they continue an earlier task.
 * Ensures that this task gets scheduled ahead of regular stuff.
 * Use judiciously; an appropriate use case is a task which is intended to be the direct continuation of one currently running
 * or which was running in a previous Jenkins session and is not logically finished.
 * @see ContinuableExecutable
 */
public interface ContinuedTask extends Queue.Task {

    /**
     * True if the task should actually be consider continued now.
     */
    boolean isContinued();

    @Restricted(NoExternalUse.class) // implementation
    @Extension class Scheduler extends QueueTaskDispatcher {

        private static final Logger LOGGER = Logger.getLogger(ContinuedTask.class.getName());

        /**
         * Number of {@link ContinuedTask} instances currently in the buildable queue.
         * Tracked by {@link CountingListener} on {@code instanceof ContinuedTask} (stable over the
         * buildable lifecycle), not on the dynamic {@link ContinuedTask#isContinued()} value.
         * Drift only causes the fast path to miss; it never produces an incorrect block decision.
         */
        private static final AtomicInteger continuedBuildableCount = new AtomicInteger();

        private static boolean isContinued(Queue.Task task) {
            return task instanceof ContinuedTask && ((ContinuedTask) task).isContinued();
        }

        @Override public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            // as this method can be a hostspot, do not use a Supplier in logging statements
            // rather guard the logging statement inside an if
            final boolean isFiner = LOGGER.isLoggable(Level.FINER);
            if (isContinued(item.task)) {
                if (isFiner) {
                    LOGGER.finer(item.task + " is a continued task, so we are not blocking it");
                }
                return null;
            }
            if (continuedBuildableCount.get() == 0) {
                return null;
            }
            final boolean isFine = LOGGER.isLoggable(Level.FINE);
            for (Queue.BuildableItem other : Queue.getInstance().getBuildableItems()) {
                if (isContinued(other.task)) {
                    Label label = other.task.getAssignedLabel();
                    if (label == null || label.matches(node)) { // conservative; might actually go to a different node
                        if (isFine) {
                            LOGGER.fine("blocking " + item.task + " in favor of " + other.task);
                        }
                        return new HoldOnPlease(other.task);
                    } else if (isFiner) {
                        LOGGER.finer(other.task + "’s label " + label + " does not match " + node);
                    }
                } else if (isFiner) {
                    LOGGER.finer(other.task + " is not continued, so it would not block " + item.task);
                }
            }
            if (isFiner) {
                LOGGER.finer("no reason to block " + item.task);
            }
            return null;
        }

        static int continuedBuildableCount() {
            return continuedBuildableCount.get();
        }

        @Extension
        public static final class CountingListener extends QueueListener {
            @Override public void onEnterBuildable(Queue.BuildableItem item) {
                if (item.task instanceof ContinuedTask) {
                    continuedBuildableCount.incrementAndGet();
                }
            }
            @Override public void onLeaveBuildable(Queue.BuildableItem item) {
                if (item.task instanceof ContinuedTask) {
                    int previous = continuedBuildableCount.getAndUpdate(c -> c > 0 ? c - 1 : 0);
                    if (previous <= 0) {
                        // Indicates a missed onEnterBuildable or a stray callback. Clamp at 0
                        // rather than letting the counter drift negative — a negative counter
                        // would keep the fast path armed forever and silently lose the win.
                        LOGGER.warning(() -> "ContinuedTask buildable counter underflow on leave of " + item.task + "; clamped at 0");
                    }
                }
            }
        }

        private static final class HoldOnPlease extends CauseOfBlockage {

            private final Queue.Task task;

            HoldOnPlease(Queue.Task task) {
                this.task = task;
            }

            @Override public String getShortDescription() {
                return Messages.ContinuedTask__should_be_allowed_to_run_first(task.getFullDisplayName());
            }

        }

    }

}
