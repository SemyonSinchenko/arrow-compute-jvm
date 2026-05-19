package io.github.semyonsinchenko.arrowcompute.bench;

public final class BenchmarkPolicyExceptionMapper {
    private BenchmarkPolicyExceptionMapper() {
    }

    public static String handlePolicyViolation(BenchmarkPolicyViolationException ex) {
        return "benchmark-policy-error"
                + " benchmarkId=unknown"
                + " ruleId=" + ex.errorCode()
                + " message=\"" + ex.errorMessage() + "\""
                + " remediation=fix-benchmark-metadata-or-params";
    }

    public static String handleIllegalArgument(IllegalArgumentException ex) {
        return "benchmark-policy-error"
                + " benchmarkId=unknown"
                + " ruleId=BMK-PARAM-001"
                + " message=\"" + ex.getMessage() + "\""
                + " remediation=use-supported-rows-and-null-profile";
    }
}
