package org.jenkinsci.plugins.durabletask;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.jenkinsci.remoting.RoleChecker;

import hudson.Platform;
import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;

public final class AgentInfo implements Serializable {
    private static final long serialVersionUID = 7599995179651071957L;
    private final OsType os;
    private final String binaryPath;
    private final String architecture;
    private boolean binaryCompatible;
    private boolean binaryCached;
    private boolean cachingAvailable;

    public enum OsType {
        DARWIN("darwin"),
        LINUX("linux"),
        WINDOWS("win"),
        FREEBSD("freebsd"),
        ZOS("zos"),
        UNKNOWN("unknown");

        private final String binaryName;
        OsType(final String binaryName) {
            this.binaryName = binaryName;
        }
        public String getNameForBinary() {
            return binaryName;
        }
    }

    public AgentInfo(OsType os, String architecture, boolean binaryCompatible, String binaryPath, boolean cachingAvailable) {
        this.os = os;
        this.architecture = architecture;
        this.binaryPath = binaryPath;
        this.binaryCompatible = binaryCompatible;
        this.binaryCached = false;
        this.cachingAvailable = cachingAvailable;
    }

    public OsType getOs() {
        return os;
    }

    public String getArchitecture() {
        return architecture;
    }

    public String getBinaryPath() {
        return binaryPath;
    }

    public void setBinaryAvailability(boolean isCached) {
        binaryCached = isCached;
    }

    public boolean isBinaryCompatible() {
        return binaryCompatible;
    }

    public boolean isBinaryCached() {
        return binaryCached;
    }

    public boolean isCachingAvailable() {
        return cachingAvailable;
    }

    public static final class GetAgentInfo implements FileCallable<AgentInfo> {
        private static final long serialVersionUID = 1L;
        private static final String BINARY_PREFIX = "durable_task_monitor_";
        private static final String CACHE_PATH = "caches/durable-task/";
        private static final String NOT_SUPPORTED = "NOT_SUPPORTED";
        // Version makes sure we don't use an out-of-date cached binary
        private String binaryVersion;

        GetAgentInfo(String pluginVersion) {
            this.binaryVersion = pluginVersion;
        }

        @Override
        public AgentInfo invoke(File nodeRoot, VirtualChannel virtualChannel) throws IOException, InterruptedException {
            OsType os;
            if (Platform.isDarwin()) {
                os = OsType.DARWIN;
            } else if (Platform.current() == Platform.WINDOWS) {
                os = OsType.WINDOWS;
            } else if (Platform.current() == Platform.UNIX) {
                String osName = System.getProperty("os.name");
                if (osName.equals("linux")) {
                    os = OsType.LINUX;
                } else if (osName.equals("z/OS")) {
                    os = OsType.ZOS;
                } else if (osName.equals("FreeBSD")) {
                    os = OsType.FREEBSD;
                } else {
                    os = OsType.UNKNOWN;
                }
            } else {
                os = OsType.UNKNOWN;
            }

            String arch = System.getProperty("os.arch");
            String archType = "";
            if (os == OsType.DARWIN) {
                if (arch.contains("aarch") || arch.contains("arm")) {
                    archType = "arm";
                } else if (arch.contains("amd")) {
                    archType = "amd"; // Default Value
                } else {
                    archType = NOT_SUPPORTED;
                }
            }

            // Note: This will only determine the architecture bits of the JVM.
            String bits = System.getProperty("sun.arch.data.model");
            if (bits.equals("64") || bits.equals("32")) {
                archType += bits;
            } else {
                archType += NOT_SUPPORTED;
            }

            boolean binaryCompatible;
            if ((os == OsType.DARWIN) || (os == OsType.LINUX) || (os == OsType.WINDOWS) && !archType.contains(NOT_SUPPORTED)) {
                binaryCompatible = true;
            } else {
                binaryCompatible = false;
            }

            String extension = "";
            if (os == OsType.WINDOWS) {
                extension = ".exe";
            }

            String binaryName = BINARY_PREFIX + binaryVersion + "_" + os.getNameForBinary() + "_" + archType + extension;
            String binaryPath;
            boolean isCached;
            boolean cachingAvailable;
            try {
                Path cachePath = Paths.get(nodeRoot.toPath().toString(), CACHE_PATH);
                Files.createDirectories(cachePath);
                File binaryFile = new File(cachePath.toFile(), binaryName);
                binaryPath = binaryFile.toPath().toString();
                isCached = binaryFile.exists();
                cachingAvailable = true;
            } catch (Exception e) {
                // when the jenkins agent cache path is not accessible
                binaryPath = binaryName;
                isCached = false;
                cachingAvailable = false;
            }
            AgentInfo agentInfo = new AgentInfo(os, archType, binaryCompatible, binaryPath, cachingAvailable);
            agentInfo.setBinaryAvailability(isCached);
            return agentInfo;
        }

        @Override
        public void checkRoles(RoleChecker roleChecker) throws SecurityException {

        }
    }
}
