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
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
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

        private static boolean isContinued(Queue.Task task) {
            return task instanceof ContinuedTask && ((ContinuedTask) task).isContinued();
        }

        @Override public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            if (isContinued(item.task)) {
                LOGGER.finer(() -> item.task + " is a continued task, so we are not blocking it");
                return null;
            }
            BuildableContinuedTasks.initialize();
            for (ContinuedItem continued : BuildableContinuedTasks.values()) {
                Queue.Task task = continued.task.get();
                if (task == null || !isContinued(task)) {
                    BuildableContinuedTasks.remove(continued.id);
                    continue;
                }
                Label label = continued.label;
                if (label == null || label.matches(node)) { // conservative; might actually go to a different node
                    LOGGER.fine(() -> "blocking " + item.task + " in favor of " + continued.fullDisplayName);
                    return new HoldOnPlease(continued.fullDisplayName);
                } else {
                    LOGGER.finer(() -> continued.fullDisplayName + "’s label " + label + " does not match " + node);
                }
            }
            LOGGER.finer(() -> "no reason to block " + item.task);
            return null;
        }

        private static final class BuildableContinuedTasks {

            private static final ConcurrentMap<Long, ContinuedItem> ITEMS = new ConcurrentHashMap<>();
            private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

            private static synchronized void initialize() {
                if (INITIALIZED.compareAndSet(false, true)) {
                    for (Queue.BuildableItem item : Queue.getInstance().getBuildableItems()) {
                        addIfContinued(item);
                    }
                }
            }

            private static Iterable<ContinuedItem> values() {
                return ITEMS.values();
            }

            private static synchronized void addIfContinued(Queue.BuildableItem item) {
                if (isContinued(item.task)) {
                    ITEMS.put(item.getId(), new ContinuedItem(item));
                }
            }

            private static synchronized void remove(long id) {
                ITEMS.remove(id);
            }

        }

        private static final class ContinuedItem {

            private final long id;
            private final WeakReference<Queue.Task> task;
            private final String fullDisplayName;
            private final Label label;

            ContinuedItem(Queue.BuildableItem item) {
                id = item.getId();
                task = new WeakReference<>(item.task);
                fullDisplayName = item.task.getFullDisplayName();
                label = item.task.getAssignedLabel();
            }

        }

        private static final class HoldOnPlease extends CauseOfBlockage {

            private final String fullDisplayName;

            HoldOnPlease(String fullDisplayName) {
                this.fullDisplayName = fullDisplayName;
            }

            @Override public String getShortDescription() {
                return Messages.ContinuedTask__should_be_allowed_to_run_first(fullDisplayName);
            }

        }

        @Restricted(NoExternalUse.class) // implementation
        @Extension public static final class Listener extends QueueListener {

            @Override public void onEnterBuildable(Queue.BuildableItem bi) {
                BuildableContinuedTasks.addIfContinued(bi);
            }

            @Override public void onEnterBlocked(Queue.BlockedItem bi) {
                BuildableContinuedTasks.remove(bi.getId());
            }

            @Override public void onLeaveBuildable(Queue.BuildableItem bi) {
                BuildableContinuedTasks.remove(bi.getId());
            }

            @Override public void onLeft(Queue.LeftItem li) {
                BuildableContinuedTasks.remove(li.getId());
            }

        }

    }

}
