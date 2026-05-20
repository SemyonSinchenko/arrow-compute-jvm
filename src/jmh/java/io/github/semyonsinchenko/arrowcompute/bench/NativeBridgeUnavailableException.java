package io.github.semyonsinchenko.arrowcompute.bench;

public final class NativeBridgeUnavailableException extends RuntimeException {
    private final String errorCode;
    private final String errorMessage;

    public NativeBridgeUnavailableException(String errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public NativeBridgeUnavailableException(String errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public NativeBridgeUnavailableException(String errorMessage) {
        this("NATIVE-BRIDGE-001", errorMessage);
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
