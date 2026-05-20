package io.github.semyonsinchenko.arrowcompute.bench;

import java.util.Set;

public final class BenchmarkSuiteValidator {
    private static final Set<Integer> ALLOWED_ROWS = Set.of(1024, 16384, 65536, 1048576);
    private static final Set<Integer> WRAPPER_DISPATCH_NULLS = Set.of(0, 30);
    private static final Set<Integer> NON_AGG_WRAPPER_DISPATCH_NULLS = Set.of(0, 30);
    private BenchmarkSuiteValidator() {
    }

    public static void validateClassMetadata(Class<?> benchmarkClass) {
        String name = benchmarkClass.getSimpleName();
        boolean containsLayer = name.contains("Dispatch")
                || name.contains("Infra");
        if (!containsLayer) {
            throw new BenchmarkPolicyViolationException(
                    "BMK-NAMING-001",
                    "class " + name + " must encode layer scenario in name"
            );
        }
    }

    public static void validateParams(String benchmarkId, int rows, int nullPercent) {
        if (!ALLOWED_ROWS.contains(rows)) {
            throw new IllegalArgumentException("benchmarkId=" + benchmarkId + " unsupported rows=" + rows);
        }
        if (benchmarkId.contains("infra")) {
            if (nullPercent != 0) {
                throw new IllegalArgumentException("benchmarkId=" + benchmarkId + " unsupported nullPercent=" + nullPercent);
            }
            return;
        }
        if (benchmarkId.contains("wrapper") || benchmarkId.contains("dispatch") || benchmarkId.contains("layer")) {
            boolean isAgg = benchmarkId.contains("sum-int64");
            if (isAgg) {
                if (!WRAPPER_DISPATCH_NULLS.contains(nullPercent)) {
                    throw new IllegalArgumentException("benchmarkId=" + benchmarkId + " unsupported nullPercent=" + nullPercent);
                }
            } else if (!NON_AGG_WRAPPER_DISPATCH_NULLS.contains(nullPercent)) {
                throw new IllegalArgumentException("benchmarkId=" + benchmarkId + " unsupported nullPercent=" + nullPercent);
            }
            return;
        }
        throw new IllegalArgumentException("benchmarkId=" + benchmarkId + " unsupported benchmark category");
    }

    public static void validateSeed(long seed) {
        if (seed != BenchmarkProfiles.REQUIRED_SEED) {
            throw new BenchmarkPolicyViolationException(
                    "BMK-SEED-004",
                    "seed must be 0xC0FFEEL"
            );
        }
    }
}
