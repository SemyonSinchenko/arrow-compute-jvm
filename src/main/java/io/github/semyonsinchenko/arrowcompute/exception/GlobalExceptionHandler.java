package io.github.semyonsinchenko.arrowcompute.exception;

public interface GlobalExceptionHandler {
    ErrorResponse handleBusinessException(RuntimeException exception);

    ErrorResponse handleValidationException(IllegalArgumentException exception);

    ErrorResponse handleSystemException(RuntimeException exception);
}
