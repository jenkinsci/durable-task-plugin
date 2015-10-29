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

import hudson.Launcher;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.IOException;
import jenkins.security.MasterToSlaveCallable;

/**
 * Utility class to track whether a given process is still alive.
 */
final class ProcessLiveness {

    /**
     * Determines whether a process is believed to still be alive.
     * @param channel a connection to the machine on which it would be running
     * @param pid a process ID
     * @param launcher a way to start processes
     * @return true if it is apparently still alive (or we cannot tell); false if it is believed to not be running
     */
    public static boolean isAlive(VirtualChannel channel, int pid, Launcher launcher) throws IOException, InterruptedException {
        if (launcher instanceof Launcher.LocalLauncher || launcher instanceof Launcher.RemoteLauncher) {
            return channel.call(new Liveness(pid));
        } else {
            // Using a special launcher; let it decide how to do this.
            // TODO perhaps this should be a method in Launcher, with the following fallback in DecoratedLauncher:
            return launcher.launch().cmds("ps", "-o", "pid=", Integer.toString(pid)).quiet(true).join() == 0;
        }
    }

    private static final class Liveness extends MasterToSlaveCallable<Boolean,RuntimeException> {
        private final int pid;
        Liveness(int pid) {
            this.pid = pid;
        }
        @Override public Boolean call() throws RuntimeException {
            File proc = new File("/proc");
            if (!proc.isDirectory()) {
                // procfs not in use here? Give up.
                return true;
            }
            return new File(proc, Integer.toString(pid)).isDirectory();
        }
    }

    private ProcessLiveness() {}

}
