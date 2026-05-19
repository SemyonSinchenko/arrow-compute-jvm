package io.github.semyonsinchenko.arrowcompute.bench;

public interface BenchmarkMetadataProvider {
    String layer();

    String question();

    String baseline();

    String type();

    String benchmarkId();

    int rows();

    int nullPercent();

    default String outputAllocationPolicy() {
        return "preallocated";
    }

    default BenchmarkMetadata metadata() {
        BenchmarkSuiteValidator.validateClassMetadata(getClass());
        if (question() == null || question().isBlank()) {
            throw new BenchmarkPolicyViolationException(
                    "BMK-METADATA-002",
                    "question must be non-empty"
            );
        }
        if (baseline() == null || baseline().isBlank()) {
            throw new BenchmarkPolicyViolationException(
                    "BMK-METADATA-003",
                    "baseline must be non-empty"
            );
        }
        return new BenchmarkMetadata(
                getClass().getSimpleName(),
                benchmarkId(),
                layer(),
                type(),
                rows(),
                nullPercent(),
                question(),
                baseline(),
                outputAllocationPolicy()
        );
    }
}
