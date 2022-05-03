/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
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
import hudson.Launcher;
import hudson.remoting.VirtualChannel;
import java.io.InputStream;
import java.io.Serializable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A remote handler which may be sent to an agent and handle process output and results.
 * If it needs to communicate with the Controller, you may use {@link VirtualChannel#export}.
 * @see Controller#watch
 */
public abstract class Handler implements Serializable { // TODO 2.107+ SerializableOnlyOverRemoting

    /**
     * Notification that new process output is available.
     * <p>Should only be called when at least one byte is available.
     * Whatever bytes are actually read will not be offered on the next call, if there is one; there is no need to close the stream.
     * <p>There is no guarantee that output is offered in the form of complete lines of text,
     * though in the typical case of line-oriented output it is likely that it will end in a newline.
     * <p>Buffering is the responsibility of the caller, and {@link InputStream#markSupported} may be false.
     * @param stream a way to read process output which has not already been handled
     * @throws Exception if anything goes wrong, this watch is deactivated
     */
    public abstract void output(@NonNull InputStream stream) throws Exception;

    /**
     * Notification that the process has exited or vanished.
     * {@link #output} should have been called with any final uncollected output.
     * <p>Any metadata associated with the process may be deleted after this call completes, rendering subsequent {@link Controller} calls unsatisfiable.
     * <p>Note that unlike {@link Controller#exitStatus(FilePath, Launcher)}, no specialized {@link Launcher} is available on the agent,
     * so if there are specialized techniques for determining process liveness they will not be considered here;
     * you still need to occasionally poll for an exit status from the controller.
     * @param code the exit code, if known (0 conventionally represents success); may be negative for anomalous conditions such as a missing process
     * @param output standard output captured, if {@link DurableTask#captureOutput} was called; else null
     * @throws Exception if anything goes wrong, this watch is deactivated
     */
    public abstract void exited(int code, @Nullable byte[] output) throws Exception;

}
