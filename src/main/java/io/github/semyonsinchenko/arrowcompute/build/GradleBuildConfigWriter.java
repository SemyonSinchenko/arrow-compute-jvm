package io.github.semyonsinchenko.arrowcompute.build;

import java.util.List;

public final class GradleBuildConfigWriter {
    public void configureToolchain() {
        // Managed by Gradle Kotlin DSL baseline in build.gradle.kts.
    }

    public void configureDependencies(String arrowVersion) {
        if (arrowVersion == null || arrowVersion.isBlank()) {
            throw new IllegalArgumentException("Rule[arrow-version]: arrowVersion must be non-blank");
        }
    }

    public void configureJvmFlags(List<String> sharedJvmArgs) {
        if (sharedJvmArgs == null || sharedJvmArgs.isEmpty()) {
            throw new IllegalArgumentException("Rule[jvm-flags]: shared JVM args must be configured");
        }
    }
}
