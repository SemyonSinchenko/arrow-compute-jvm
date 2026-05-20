package io.github.semyonsinchenko.arrowcompute.bench;

import java.time.Instant;

public final class GlobalExceptionHandler {
    public BenchmarkErrorResponse handleBusinessException(RuntimeException exception) {
        if (exception instanceof NativeBridgeUnavailableException unavailable) {
            return new BenchmarkErrorResponse(
                    unavailable.errorCode(),
                    unavailable.errorMessage(),
                    "business",
                    Instant.now().toString()
            );
        }
        return new BenchmarkErrorResponse("BUSINESS_ERROR", safeMessage(exception), "business", Instant.now().toString());
    }

    public BenchmarkErrorResponse handleValidationException(IllegalArgumentException exception) {
        return new BenchmarkErrorResponse("VALIDATION_ERROR", safeMessage(exception), "validation", Instant.now().toString());
    }

    public BenchmarkErrorResponse handleSystemException(RuntimeException exception) {
        return new BenchmarkErrorResponse("SYSTEM_ERROR", safeMessage(exception), "system", Instant.now().toString());
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? "unexpected runtime failure" : message;
    }
}
