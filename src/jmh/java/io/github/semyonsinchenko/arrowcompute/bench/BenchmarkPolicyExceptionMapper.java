package io.github.semyonsinchenko.arrowcompute.bench;

import java.time.Instant;

public final class BenchmarkPolicyExceptionMapper {
    private BenchmarkPolicyExceptionMapper() {
    }

    public static BenchmarkErrorResponse map(BenchmarkPolicyViolationException ex) {
        return new BenchmarkErrorResponse(
                ex.errorCode(),
                ex.errorMessage(),
                "policy",
                Instant.now().toString()
        );
    }

    public static BenchmarkErrorResponse map(IllegalArgumentException ex) {
        return new BenchmarkErrorResponse(
                "BMK-PARAM-001",
                ex.getMessage() == null ? "invalid benchmark parameters" : ex.getMessage(),
                "validation",
                Instant.now().toString()
        );
    }
}
