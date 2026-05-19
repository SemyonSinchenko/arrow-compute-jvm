package io.github.semyonsinchenko.arrowcompute.bench;

import org.apache.arrow.vector.ValueVector;

final class BenchmarkSupport {
    private BenchmarkSupport() {
    }

    static void validateTrial(BenchmarkMetadataProvider provider, long seed) {
        BenchmarkSuiteValidator.validateSeed(seed);
        BenchmarkSuiteValidator.validateParams(provider.benchmarkId(), provider.rows(), provider.nullPercent());
        provider.metadata();
    }

    static boolean isValidAt(int row, int nullPercent, int shift) {
        return ((row + shift) % 100) >= nullPercent;
    }

    static void clearOut(ValueVector out) {
        out.setValueCount(0);
    }
}
