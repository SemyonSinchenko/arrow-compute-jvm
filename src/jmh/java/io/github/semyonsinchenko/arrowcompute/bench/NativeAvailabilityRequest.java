package io.github.semyonsinchenko.arrowcompute.bench;

public record NativeAvailabilityRequest(String preferredBackend, boolean failIfMissing) {
}
