package io.github.semyonsinchenko.arrowcompute.exception;

public interface GlobalExceptionHandler {
    ErrorResponse handleBuildConstraintException(BuildConstraintException exception);

    ErrorResponse handleBuildValidationException(BuildValidationException exception);
}
