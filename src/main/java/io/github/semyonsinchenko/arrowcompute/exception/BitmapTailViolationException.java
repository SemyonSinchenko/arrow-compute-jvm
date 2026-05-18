package io.github.semyonsinchenko.arrowcompute.exception;

public final class BitmapTailViolationException extends RuntimeException {
    private final String errorCode;
    private final String errorMessage;

    public BitmapTailViolationException(String message) {
        this("BITMAP_TAIL_VIOLATION", message);
    }

    public BitmapTailViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    public BitmapTailViolationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.errorMessage = message;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
