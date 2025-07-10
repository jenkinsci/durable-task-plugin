package org.jenkinsci.plugins.durabletask.fixtures;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.util.List;

public class PowerShellCoreFixture extends GenericContainer<PowerShellCoreFixture> {
    public static final String PWSH_JAVA_LOCATION = "/usr/lib/jvm/java-17-openjdk-amd64/bin/java";

    public PowerShellCoreFixture() {
        super(new ImageFromDockerfile("pwsh", false)
                .withFileFromClasspath("Dockerfile", "org/jenkinsci/plugins/durabletask/fixtures/PowerShellCoreFixture/Dockerfile"));
        setExposedPorts(List.of(22));
    }
}

