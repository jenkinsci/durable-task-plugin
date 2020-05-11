/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
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
import hudson.ExtensionPoint;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;

/**
 * A task which may be run asynchronously on a build node and withstand disconnection of the slave agent.
 * Should have a descriptor, and a {@code config.jelly} for form data binding.
 */
public abstract class DurableTask extends AbstractDescribableImpl<DurableTask> implements ExtensionPoint {

    private static final Logger LOGGER = Logger.getLogger(DurableTask.class.getName());

    /**
     * Optional information about the run that has triggered this task
     */
    protected transient Run<?, ?> run;

    /**
     * @since TODO
     */
    public void setRun(Run<?, ?> run) {
        this.run = run;
    }

    /**
     * @since TODO
     */
    public Run<?, ?> getRun() {
        return run;
    }

    @Override public DurableTaskDescriptor getDescriptor() {
        return (DurableTaskDescriptor) super.getDescriptor();
    }

    /**
     * Launches a durable task.
     * @param env basic environment variables to use during launch
     * @param workspace the workspace to use
     * @param launcher a way to start processes
     * @param listener log output for the build
     * @return a way to check up on the task’s subsequent status
     */
    public abstract Controller launch(EnvVars env, FilePath workspace, Launcher launcher, TaskListener listener) throws IOException, InterruptedException;

    /**
     * Requests that standard output of the task be captured rather than streamed.
     * If you use {@link Controller#watch}, standard output will not be sent to {@link Handler#output}; it will be included in {@link Handler#exited} instead.
     * Otherwise (using polling mode), standard output will not be sent to {@link Controller#writeLog}; call {@link Controller#getOutput} to collect.
     * Standard error should still be streamed to the log.
     * Should be called prior to {@link #launch} to take effect.
     * @throws UnsupportedOperationException if this implementation does not support that mode
     */
    public void captureOutput() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("Capturing of output is not implemented in " + getClass().getName());
    }

    /**
     * Requests that a specified charset be used to transcode process output.
     * The encoding of {@link Controller#writeLog} and {@link Controller#getOutput} is then presumed to be UTF-8.
     * If not called, no translation is performed.
     * @param cs the character set in which process output is expected to be
     */
    public void charset(@Nonnull Charset cs) {
        LOGGER.log(Level.WARNING, "The charset method should be overridden in {0}", getClass().getName());
    }

    /**
     * Requests that the node’s system charset be used to transcode process output.
     * The encoding of {@link Controller#writeLog} and {@link Controller#getOutput} is then presumed to be UTF-8.
     * If not called, no translation is performed.
     */
    public void defaultCharset() {
        LOGGER.log(Level.WARNING, "The defaultCharset method should be overridden in {0}", getClass().getName());
    }

}
