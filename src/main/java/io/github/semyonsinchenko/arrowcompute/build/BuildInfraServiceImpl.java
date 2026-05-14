package io.github.semyonsinchenko.arrowcompute.build;

import io.github.semyonsinchenko.arrowcompute.exception.BuildConstraintException;

import java.nio.file.Path;
import java.util.List;

public final class BuildInfraServiceImpl implements BuildInfraService {
    private static final String PROJECT_NAME = "arrow-compute";
    private static final String ROOT_PACKAGE = "io.github.semyonsinchenko.arrowcompute";
    private static final String ARROW_VERSION = "16.1.0";

    private final GradleBuildConfigWriter gradleBuildConfigWriter;
    private final PackageSkeletonCreator packageSkeletonCreator;
    private final ReadmeCommandWriter readmeCommandWriter;
    private final SmokeTestGenerator smokeTestGenerator;
    private final BuildVerifier buildVerifier;

    public BuildInfraServiceImpl(
            GradleBuildConfigWriter gradleBuildConfigWriter,
            PackageSkeletonCreator packageSkeletonCreator,
            ReadmeCommandWriter readmeCommandWriter,
            SmokeTestGenerator smokeTestGenerator,
            BuildVerifier buildVerifier
    ) {
        this.gradleBuildConfigWriter = gradleBuildConfigWriter;
        this.packageSkeletonCreator = packageSkeletonCreator;
        this.readmeCommandWriter = readmeCommandWriter;
        this.smokeTestGenerator = smokeTestGenerator;
        this.buildVerifier = buildVerifier;
    }

    @Override
    public BuildInfraResponse provision(BuildInfraRequest request) {
        if (request == null) {
            throw new BuildConstraintException("constraint.request.missing", "BuildInfraRequest must be provided");
        }

        gradleBuildConfigWriter.configureToolchain();
        gradleBuildConfigWriter.configureDependencies(ARROW_VERSION);
        gradleBuildConfigWriter.configureJvmFlags(List.of(
                "--add-modules",
                "jdk.incubator.vector",
                "--enable-native-access=ALL-UNNAMED"
        ));

        packageSkeletonCreator.createPackageSkeleton(List.of(
                "compute",
                "compute.dispatch",
                "compute.wrapper.safe",
                "compute.wrapper.validonly",
                "compute.wrapper.agg",
                "compute.wrapper.slow",
                "compute.raw",
                "memory"
        ));
        packageSkeletonCreator.createTestSourceSkeleton(
                Path.of("src/test/java/" + ROOT_PACKAGE.replace('.', '/'))
        );

        smokeTestGenerator.ensureSmokeTestExists();
        readmeCommandWriter.documentBuildAndBenchmarkCommands(
                Path.of("README.md"),
                List.of("./gradlew clean test", "./gradlew check", "./gradlew jmh")
        );

        var verificationResults = buildVerifier.verify(request.includeJmh());
        return new BuildInfraResponse(true, request.includeJmh(), true, verificationResults);
    }
}
