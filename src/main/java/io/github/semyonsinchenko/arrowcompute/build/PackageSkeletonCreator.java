package io.github.semyonsinchenko.arrowcompute.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PackageSkeletonCreator {
    private final Path basePath;

    public PackageSkeletonCreator(Path basePath) {
        this.basePath = basePath;
    }

    public void createPackageSkeleton(List<String> packages) {
        for (var pkg : packages) {
            var path = basePath.resolve(pkg.replace('.', '/'));
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new IllegalStateException("Rule[package-layout]: failed creating " + path, e);
            }
        }
    }

    public void createTestSourceSkeleton(Path testRootPackagePath) {
        try {
            Files.createDirectories(testRootPackagePath);
        } catch (IOException e) {
            throw new IllegalStateException("Rule[test-layout]: failed creating " + testRootPackagePath, e);
        }
    }
}
