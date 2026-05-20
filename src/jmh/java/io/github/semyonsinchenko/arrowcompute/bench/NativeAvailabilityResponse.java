package io.github.semyonsinchenko.arrowcompute.bench;

public record NativeAvailabilityResponse(boolean available, String backend, String reason) {
}
