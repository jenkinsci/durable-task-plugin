package org.jenkinsci.plugins.durabletask;

import org.jenkinsci.test.acceptance.docker.DockerContainer;
import org.jenkinsci.test.acceptance.docker.DockerFixture;

@DockerFixture(id = "pwsh", ports = 22)
public class PowerShellCoreFixture extends DockerContainer {
    public static final String PWSH_JAVA_LOCATION = "/usr/lib/jvm/java-11-openjdk-amd64/bin/java";
}
