package io.github.semyonsinchenko.arrowcompute.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadmeCommandWriter {
    public void documentBuildAndBenchmarkCommands(Path readmePath, List<String> requiredCommands) {
        var content = """
                # arrow-compute

                Java 25 build baseline for Arrow compute JVM scaffolding.

                ## Build and verification commands

                - `./gradlew clean test`: compile and run smoke-level tests.
                - `./gradlew check`: enforce build contract checks and test execution.
                - `./gradlew jmh`: run benchmark harness wiring.

                ## JVM constraints

                - Shared flags: `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED`
                - Test JVM property: `-Darrow.memory.debug.allocator=true`
                """;

        try {
            Files.writeString(readmePath, content);
        } catch (IOException e) {
            throw new IllegalStateException("Rule[readme]: failed writing README", e);
        }
    }
}
