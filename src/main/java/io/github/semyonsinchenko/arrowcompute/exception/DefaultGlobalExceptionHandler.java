package io.github.semyonsinchenko.arrowcompute.exception;

public final class DefaultGlobalExceptionHandler implements GlobalExceptionHandler {
    @Override
    public ErrorResponse handleBusinessException(RuntimeException exception) {
        if (exception instanceof BuildConstraintException constraint) {
            return new ErrorResponse(constraint.errorCode(), constraint.errorMessage(), "business");
        }
        if (exception instanceof StartsWithBusinessException startsWith) {
            return new ErrorResponse(startsWith.errorCode(), startsWith.errorMessage(), "business");
        }
        return new ErrorResponse("BUSINESS_ERROR", safeMessage(exception), "business");
    }

    @Override
    public ErrorResponse handleValidationException(IllegalArgumentException exception) {
        return new ErrorResponse("VALIDATION_ERROR", safeMessage(exception), "validation");
    }

    public ErrorResponse handleSystemException(RuntimeException exception) {
        if (exception instanceof SystemException system) {
            return new ErrorResponse(system.errorCode(), system.errorMessage(), "system");
        }
        return new ErrorResponse("SYSTEM_ERROR", safeMessage(exception), "system");
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null ? "unexpected runtime failure" : message;
    }
}
