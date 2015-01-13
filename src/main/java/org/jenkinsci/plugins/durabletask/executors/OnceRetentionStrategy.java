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

import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.CloudRetentionStrategy;
import hudson.slaves.EphemeralNode;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Retention strategy that allows a cloud slave to run only a single build before disconnecting.
 * A {@link ContinuableExecutable} does not trigger termination.
 */
public final class OnceRetentionStrategy extends CloudRetentionStrategy implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(OnceRetentionStrategy.class.getName());
    ReentrantLock checkLock = new ReentrantLock(false);

    /**
     * Creates the retention strategy.
     * @param idleMinutes number of minutes of idleness after which to kill the slave; serves a backup in case the strategy fails to detect the end of a task
     */
    public OnceRetentionStrategy(int idleMinutes) {
        super(idleMinutes);
    }

    @Override public void start(AbstractCloudComputer c) {
        if (c.getNode() instanceof EphemeralNode) {
            throw new IllegalStateException("May not use OnceRetentionStrategy on an EphemeralNode: " + c);
        }
        super.start(c);
    }
    
    @Override
    public long check(AbstractCloudComputer cloudComputer) {
        if (! checkLock.tryLock()) {
            return 1;
        } else {
            try {
                return super.check(cloudComputer);
            } finally {
                checkLock.unlock();
            }
        }
    }

    @Override public void taskAccepted(Executor executor, Queue.Task task) {}

    @Override public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        done(executor);
    }

    @Override public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        done(executor);
    }

    private void done(Executor executor) {
        AbstractCloudComputer<?> c = (AbstractCloudComputer) executor.getOwner();
        Queue.Executable exec = executor.getCurrentExecutable();
        if (exec instanceof ContinuableExecutable && ((ContinuableExecutable) exec).willContinue()) {
            LOGGER.log(Level.FINE, "not terminating {0} because {1} says it will be continued", new Object[] {c.getName(), exec});
            return;
        }
        LOGGER.log(Level.FINE, "terminating {0} since {1} seems to be finished", new Object[] {c.getName(), exec});
        c.setAcceptingTasks(false); // just in case
        // Best to kill them off ASAP; otherwise NodeProvisioner does nothing until ComputerRetentionWork has run, causing poor throughput:
        if (!checkLock.tryLock()) {
            LOGGER.log(Level.WARNING, "Unable to obtain lock on computer {0}", new Object[]{c.getName()});
            return; // We were unable to obtain the lock.
        }
        try {
            c.getNode().terminate();
        } catch (InterruptedException x) {
            LOGGER.log(Level.WARNING, null, x);
        } catch (IOException x) {
            LOGGER.log(Level.WARNING, null, x);
        } finally {
            checkLock.unlock();
        }
        // TODO calling NodeProvisioner.suggestReviewNow here does not seem to help push things along at all
    }

}
