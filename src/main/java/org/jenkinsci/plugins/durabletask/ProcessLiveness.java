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
import hudson.os.PosixAPI;
import hudson.os.PosixException;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.security.MasterToSlaveCallable;
import jnr.posix.POSIX;
import jnr.posix.POSIXFactory;
import jnr.posix.POSIXHandler;
import jnr.posix.util.DefaultPOSIXHandler;

/**
 * Utility class to track whether a given process is still alive.
 */
final class ProcessLiveness {

    private static final Logger LOGGER = Logger.getLogger(ProcessLiveness.class.getName());

    private static final Map<Launcher,Boolean> workingLaunchers = Collections.synchronizedMap(new WeakHashMap<Launcher,Boolean>());

    /**
     * Determines whether a process is believed to still be alive.
     * @param channel a connection to the machine on which it would be running
     * @param pid a process ID
     * @param launcher a way to start processes
     * @return true if it is apparently still alive (or we cannot tell); false if it is believed to not be running
     */
    public static boolean isAlive(VirtualChannel channel, int pid, Launcher launcher) throws IOException, InterruptedException {
        Boolean working = workingLaunchers.get(launcher);
        if (working == null) {
            // Check to see if our logic correctly reports that an unlikely PID is not running.
            working = !_isAlive(channel, 9999, launcher);
            workingLaunchers.put(launcher, working);
            if (working) {
                LOGGER.log(Level.FINE, "{0} on {1} appears to be working", new Object[] {launcher, channel});
            } else {
                LOGGER.log(Level.WARNING, "{0} on {1} does not seem able to determine whether processes are alive or not", new Object[] {launcher, channel});
                // TODO Channel.toString should report slave name, but would be nice to also report OS
            }
        }
        if (!working) {
            return true;
        }
        return _isAlive(channel, pid, launcher);
    }

    private static boolean _isAlive(VirtualChannel channel, int pid, Launcher launcher) throws IOException, InterruptedException {
        if (launcher instanceof Launcher.LocalLauncher || launcher instanceof Launcher.RemoteLauncher) {
            try {
                return channel.call(new Liveness(pid));
            } catch (IllegalStateException x) {
                LOGGER.log(Level.WARNING, "cannot determine liveness of " + pid, x);
            } catch (PosixException x) {
                LOGGER.log(Level.WARNING, "cannot determine liveness of " + pid, x);
            }
            return true;
        } else {
            // Using a special launcher; let it decide how to do this.
            // TODO perhaps this should be a method in Launcher, with the following fallback in DecoratedLauncher:
            return launcher.launch().cmds("ps", "-o", "pid=", Integer.toString(pid)).quiet(true).join() == 0;
        }
    }

    private static final class Liveness extends MasterToSlaveCallable<Boolean,RuntimeException> {
        private static final POSIX jnr = PosixAPI.jnr();
        private final int pid;
        Liveness(int pid) {
            this.pid = pid;
        }
        @Override public Boolean call() throws RuntimeException {
            try { // loadPOSIX sends problems to stderr, useless if we are on a slave
                Method loadNativePOSIX = POSIXFactory.class.getDeclaredMethod("loadNativePOSIX", POSIXHandler.class);
                loadNativePOSIX.setAccessible(true);
                loadNativePOSIX.invoke(null, new DefaultPOSIXHandler());
            } catch (NoSuchMethodException x) {
                LOGGER.log(Level.WARNING, "loadNativePOSIX no longer exists?", x);
            } catch (Exception x) {
                throw new IllegalStateException(x);
            }
            // jnr.getpgid() (on Linux at least) always returns -1. JNR bug? libc would not support overloads.
            if (jnr.getpgid(0) == -1) {
                throw new IllegalStateException("getpgid does not seem to work on this platform");
            }
            return jnr.getpgid(pid) != -1;
        }
    }

    private ProcessLiveness() {}

}
