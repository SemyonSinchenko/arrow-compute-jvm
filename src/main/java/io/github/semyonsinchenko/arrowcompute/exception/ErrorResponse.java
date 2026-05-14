package io.github.semyonsinchenko.arrowcompute.exception;

public record ErrorResponse(String errorCode, String errorMessage, String context) {
}
