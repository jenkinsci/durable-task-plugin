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

import com.sun.jna.Library;
import com.sun.jna.Native;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.os.PosixAPI;
import hudson.os.PosixException;
import hudson.remoting.VirtualChannel;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.security.MasterToSlaveCallable;
import jnr.constants.platform.WaitFlags;
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
            return launcher.launch().cmds("ps", "-o", "pid=", Integer.toString(pid)).quiet(true).start().joinWithTimeout(1, TimeUnit.MINUTES, launcher.getListener())== 0;
        }
    }

    /**
     * Worker-side implementation of process liveness check.
     *
     * @throws IllegalStateException when we are unable to find a functional
     * process-liveness checking mechanism.
     */
    private static final class Liveness extends MasterToSlaveCallable<Boolean,RuntimeException> {
        private final int pid;
        Liveness(int pid) {
            this.pid = pid;
        }
        @Override public Boolean call() throws IllegalStateException {
            final String fallback_msg = "jnr doesn't have waitpid support; "+
                "falling back to the JNA LibC implementation";

            // Try jnr's waitpid
            // We need to painstakingly extract the function via reflection to
            // avoid class-loading errors in the event that the available jnr
            // library doesn't support waitpid.
            //
            // This silliness is equivalent to,
            //     int[] code = new int[1];
            //     return jnr.waitpid(pid, code, WaitFlags.WNOHANG.intValue()) != -1;
            //
            // TODO: Replace by the direct call once Jenkins core is updated to 2.64+
            // which has https://github.com/jenkinsci/jenkins/pull/2904.

            try {
                ClassLoader loader = getClass().getClassLoader();
                Object jnr = PosixAPI.jnr();

                // Find waitpid
                Method waitpidMethod = jnr.getClass().getDeclaredMethod("waitpid", int.class, int[].class, int.class);

                // Find WaitFlags.WNOHANG.intValue()
                Class WaitFlags = loader.loadClass("jnr.constants.platform.WaitFlags");
                Method intValue = WaitFlags.getDeclaredMethod("intValue");
                Integer WNOHANG = (Integer) intValue.invoke(Enum.valueOf(WaitFlags, "WNOHANG"));

                int[] code = new int[1];
                waitpidMethod.setAccessible(true); // the implementations are package-local
                Integer ret = (Integer) waitpidMethod.invoke(jnr, pid, code, WNOHANG);
                return ret != -1;
            } catch (InvocationTargetException x) {
                LOGGER.log(Level.WARNING, fallback_msg, x);
            } catch (IllegalAccessException x) {
                LOGGER.log(Level.WARNING, fallback_msg, x);
            } catch (ClassNotFoundException x) {
                LOGGER.log(Level.WARNING, fallback_msg, x);
            } catch (NoSuchMethodException x) {
                LOGGER.log(Level.WARNING, fallback_msg, x);
            } catch (SecurityException x) {
                LOGGER.log(Level.WARNING, fallback_msg, x);
            }

            // JNR-POSIX used to fail on FreeBSD at least, so try JNA as well.
            LibC libc = LibC.INSTANCE;
            if (libc.getpgid(0) == -1) {
                throw new IllegalStateException("getpgid does not seem to work on this platform");
            }
            return libc.getpgid(pid) != -1;
        }
    }

    private interface LibC extends Library {
        /**
         * Get the process group ID for a process.
         * From <a href="http://pubs.opengroup.org/onlinepubs/9699919799/functions/getpgid.html">Open Group Base Specifications Issue 7</a>:
         * <blockquote>
         * The getpgid() function shall return the process group ID of the process whose process ID is equal to pid.
         * If pid is equal to 0, getpgid() shall return the process group ID of the calling process.
         * Upon successful completion, getpgid() shall return a process group ID. Otherwise, it shall return (pid_t)-1 and set errno to indicate the error.
         * The getpgid() function shall fail if: […] There is no process with a process ID equal to pid. […]
         * </blockquote>
         */
        int getpgid(int pid);
        LibC INSTANCE = (LibC) Native.loadLibrary("c", LibC.class);
    }

    private ProcessLiveness() {}

}
