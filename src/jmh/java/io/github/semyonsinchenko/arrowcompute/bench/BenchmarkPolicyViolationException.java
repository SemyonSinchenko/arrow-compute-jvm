package io.github.semyonsinchenko.arrowcompute.bench;

public final class BenchmarkPolicyViolationException extends RuntimeException {
    private final String errorCode;
    private final String errorMessage;

    public BenchmarkPolicyViolationException(String errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public BenchmarkPolicyViolationException(String errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
