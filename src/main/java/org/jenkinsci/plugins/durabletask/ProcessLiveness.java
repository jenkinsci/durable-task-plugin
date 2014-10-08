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

package org.jenkinsci.plugins.durabletask;

import hudson.remoting.Callable;
import hudson.remoting.VirtualChannel;
import hudson.util.ProcessTree;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class to track whether a given process is still alive.
 * Since loading a complete {@link ProcessTree} may be expensive, this is done only once per {@link #CACHE_EXPIRY}.
 * Might be more efficient and reliable to use JNA to look up this information with a direct system call,
 * but this would be a longer-term project with more platform-specific code.
 */
final class ProcessLiveness {

    /** By default, one minute. */
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(1);

    private static final Logger LOGGER = Logger.getLogger(ProcessLiveness.class.getName());

    private static final class ProcessTreeCache {
        ProcessTree tree;
        long lastChecked;
        ProcessTreeCache() {}
    }

    private static final Map<VirtualChannel,ProcessTreeCache> processTrees = new WeakHashMap<VirtualChannel,ProcessTreeCache>();

    /**
     * Determines whether a process is believed to still be alive.
     * @param channel a connection to the machine on which it would be running
     * @param pid a process ID
     * @return true if it is probably still alive (but might have recently died); false if it is believed to not be running
     */
    public static boolean isAlive(VirtualChannel channel, int pid) throws IOException, InterruptedException {
        ProcessTreeCache cache;
        synchronized (processTrees) {
            cache = processTrees.get(channel);
            if (cache == null) {
                cache = new ProcessTreeCache();
                processTrees.put(channel, cache);
            }
        }
        long now = System.currentTimeMillis();
        synchronized (cache) {
            if (cache.tree == null || now - cache.lastChecked > CACHE_EXPIRY) {
                LOGGER.log(Level.FINE, "(re)loading process tree on {0}", channel);
                cache.tree = channel.call(new LoadProcessTree());
                cache.lastChecked = now;
            }
            return cache.tree.get(pid) != null;
        }
    }

    private static final class LoadProcessTree implements Callable<ProcessTree,RuntimeException> {
        @Override public ProcessTree call() throws RuntimeException {
            return ProcessTree.get();
        }
    }

    /**
     * Clears any cache for a given machine.
     * Should be done when a new process has been started.
     * @param channel a connection to a machine
     */
    public static void reset(VirtualChannel channel) {
        synchronized (processTrees) {
            processTrees.remove(channel);
        }
    }

    private ProcessLiveness() {}

}
