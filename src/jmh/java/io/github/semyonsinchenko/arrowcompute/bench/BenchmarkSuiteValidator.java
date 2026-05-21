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

        if (name.endsWith("DispatchBenchmark")) {
            boolean hasWrapperEvalNewOutput = hasSingleArgMethodNamed(benchmarkClass, "wrapperEvalNewOutput");
            boolean hasWrapperEvalReusedOutput = hasSingleArgMethodNamed(benchmarkClass, "wrapperEvalReusedOutput");
            boolean hasWrapperEvalThin = hasSingleArgMethodNamed(benchmarkClass, "wrapperEvalThin");

            if (hasWrapperEvalThin) {
                throw new BenchmarkPolicyViolationException(
                        "BENCH_POLICY_AMBIGUOUS_WRAPPER_LANE",
                        "benchmarkId=" + name + " uses retired lane name wrapperEvalThin"
                );
            }
            if (!hasWrapperEvalNewOutput) {
                throw new BenchmarkPolicyViolationException(
                        "BENCH_POLICY_MISSING_NEW_OUTPUT_LANE",
                        "benchmarkId=" + name + " missing wrapperEvalNewOutput lane"
                );
            }
            if (!hasWrapperEvalReusedOutput) {
                throw new BenchmarkPolicyViolationException(
                        "BENCH_POLICY_MISSING_REUSED_OUTPUT_LANE",
                        "benchmarkId=" + name + " missing wrapperEvalReusedOutput lane"
                );
            }
        }
    }

    private static boolean hasSingleArgMethodNamed(Class<?> benchmarkClass, String methodName) {
        return java.util.Arrays.stream(benchmarkClass.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals(methodName) && m.getParameterCount() == 1);
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
