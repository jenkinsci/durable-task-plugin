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
import hudson.init.InitMilestone;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.QueueTaskDispatcher;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

/**
 * Marker for tasks which should perhaps “jump ahead” in the queue because they continue an earlier task.
 * Ensures that this task gets scheduled ahead of regular stuff.
 * Use judiciously; an appropriate use case is a task which is intended to be the direct continuation of one currently running
 * or which was running in a previous Jenkins session and is not logically finished.
 * @see ContinuableExecutable
 */
public interface ContinuedTask extends Queue.Task {

    /**
     * Is this task a continuation of an earlier task or a new task.
     * <p>
     * <strong>Implementations must not change the value after it has been initially accessed.</strong>
     * <p>
     * This method is used to identify those tasks that are a continuation of a task execution either currently running
     * or in a previous Jenkins session.
     * <p>
     * There are two ways to start a chain of {@link ContinuableExecutable}s:
     * <ul>
     *     <li>
     *         A regular {@link Queue.Task} that does not implement this interface return a
     *         {@link ContinuableExecutable} from {@link Queue.Task#createExecutable()} (because if a {@link Queue.Task}
     *         does not implement {@link ContinuedTask} then that is equivalent to {@link #isContinued()} ==
     *         {@code false}).
     *     </li>
     *     <li>
     *         A {@link ContinuedTask} with {@link #isContinued()} == {@code false} returns a
     *         {@link ContinuableExecutable} from {@link #createExecutable()}.
     *     </li>
     * </ul>
     * The chain of {@link ContinuableExecutable}s will continue for as long as either:
     * <ul>
     *     <li>
     *         {@link ContinuableExecutable#run()} completes normally and
     *         Each {@link ContinuableExecutable#willContinue()} is {@code true} on/after completion.
     *     </li>
     *     <li>
     *         {@link ContinuableExecutable#run()} did not complete normally (i.e. JVM shutdown) and there is a
     *         {@link ContinuedTask} instance in the {@link Queue} where {@link #isContinued()} ==
     *         {@code true} that will continue the chain.
     *     </li>
     * </ul>
     * After a restart of Jenkins when the plugin implementing ContinuedTask identifies that there are some tasks that
     * may already be in progress it will need to resubmit those tasks to the Queue (before
     * {@link InitMilestone#COMPLETED}). Any resubmitted tasks should return {@code true}.
     * <p>
     * The {@link Scheduler} will block any non-continued tasks from executing as long as there are continued tasks
     * queued to execute. In this way the continuations will be able to resume with priority.
     *
     * @return {@code true} if and only if this {@link ContinuedTask} is a continuation of either a currently running
     * {@link ContinuedTask} or a {@link ContinuedTask} from a previous Jenkins session.
     */
    boolean isContinued();

    @Restricted(DoNotUse.class) // implementation
    @Extension class Scheduler extends QueueTaskDispatcher {

        private static boolean isContinued(Queue.Task task) {
            return task instanceof ContinuedTask && ((ContinuedTask) task).isContinued();
        }

        @Override public CauseOfBlockage canTake(Node node, Queue.BuildableItem item) {
            if (isContinued(item.task)) {
                return null;
            }
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null || jenkins.getInitLevel().compareTo(InitMilestone.COMPLETED) < 0) {
                return new PleaseWait();
            }
            for (Queue.BuildableItem other : Queue.getInstance().getBuildableItems()) {
                if (isContinued(other.task)) {
                    Label label = other.task.getAssignedLabel();
                    if (label == null || label.matches(node)) { // conservative; might actually go to a different node
                        Logger.getLogger(ContinuedTask.class.getName()).log(Level.FINE, "blocking {0} in favor of {1}", new Object[] {item.task, other.task});
                        return new HoldOnPlease(other.task);
                    }
                }
            }
            return null;
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

        private static final class PleaseWait extends CauseOfBlockage {

            PleaseWait() {
            }

            @Override public String getShortDescription() {
                return Messages.ContinuedTask__should_be_allowed_to_restore_first();
            }

        }

    }

}
