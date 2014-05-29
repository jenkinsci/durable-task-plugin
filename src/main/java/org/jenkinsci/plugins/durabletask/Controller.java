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

import hudson.FilePath;
import java.io.IOException;
import java.io.OutputStream;
import javax.annotation.CheckForNull;

/**
 * Defines how to control the execution of a task after it has started.
 * Expected to be XStream serializable.
 */
public abstract class Controller {

    /**
     * Obtains any new task log output.
     * Could use a serializable field to keep track of how much output has been previously written.
     * @param workspace the workspace in use
     * @param sink where to send new log output
     * @return true if something was written and the controller should be resaved, false if everything is idle
     */
    public abstract boolean writeLog(FilePath workspace, OutputStream sink) throws IOException, InterruptedException;

    /**
     * Checks whether the task has finished.
     * @param workspace the workspace in use
     * @return an exit code (zero is successful), or null if the task appears to still be running
     */
    public abstract @CheckForNull Integer exitStatus(FilePath workspace) throws IOException, InterruptedException;

    /**
     * Tries to stop any running task.
     * @param workspace the workspace in use
     */
    public abstract void stop(FilePath workspace) throws IOException, InterruptedException;

    /**
     * Cleans up after a task is done.
     * Should delete any temporary files created by {@link #launch}.
     * @param workspace the workspace in use
     */
    public abstract void cleanup(FilePath workspace) throws IOException, InterruptedException;

}
