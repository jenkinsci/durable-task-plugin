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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.OneOffExecutor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.EphemeralNode;
import hudson.util.TimeUnit2;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Retention strategy that allows a cloud slave to run only a single build before disconnecting.
 * A {@link ContinuableExecutable} does not trigger termination.
 */
public final class OnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(OnceRetentionStrategy.class.getName());

    private transient boolean terminating;
    
    private int idleMinutes;

    /**
     * Creates the retention strategy.
     * @param idleMinutes number of minutes of idleness after which to kill the slave; serves a backup in case the strategy fails to detect the end of a task
     */
    public OnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
        this.idleMinutes = idleMinutes;
    }

    @Override
    public long check(final AbstractCloudComputer c) {
        // When the slave is idle we should disable accepting tasks and check to see if it is already trying to
        // terminate. If it's not already trying to terminate then lets terminate manually.
        if (c.isIdle() && !disabled) {
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleMinutes)) {
                LOGGER.log(Level.FINE, "Disconnecting {0}", c.getName());
                done(c);
            }
        }

        // Return one because we want to check every minute if idle.
        return 1;
    }

    @Override public void start(AbstractCloudComputer c) {
        if (c.getNode() instanceof EphemeralNode) {
            throw new IllegalStateException("May not use OnceRetentionStrategy on an EphemeralNode: " + c);
        }
        super.start(c);
    }

    @Override public void taskAccepted(Executor executor, Queue.Task task) {}

    @Override public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        final AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();
        if (executor instanceof OneOffExecutor) {
            LOGGER.log(Level.FINE, "not terminating {0} because {1} was a flyweight task", new Object[] {c.getName(), exec});
            return;
        }
        if (exec instanceof ContinuableExecutable && ((ContinuableExecutable) exec).willContinue()) {
            LOGGER.log(Level.FINE, "not terminating {0} because {1} says it will be continued", new Object[] {c.getName(), exec});
            return;
        }
        LOGGER.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[] {c.getName(), exec});
        done(c);
    }

    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="not a real Callable")
    private void done(final AbstractCloudComputer<?> c) {
        c.setAcceptingTasks(false); // just in case
        synchronized (this) {
            if (terminating) {
                return;
            }
            terminating = true;
        }
        Computer.threadPoolForRemoting.submit(new Runnable() {
            @Override
            public void run() {
                Queue.withLock(new Runnable() {
                    @Override public void run() {
                        try {
                            AbstractCloudSlave node = c.getNode();
                            if (node != null) {
                                node.terminate();
                            }
                        } catch (InterruptedException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized (OnceRetentionStrategy.this) {
                                terminating = false;
                            }
                        } catch (IOException e) {
                            LOGGER.log(Level.WARNING, "Failed to terminate " + c.getName(), e);
                            synchronized (OnceRetentionStrategy.this) {
                                terminating = false;
                            }
                        }
                    }
                });
            }
        });
    }

}
