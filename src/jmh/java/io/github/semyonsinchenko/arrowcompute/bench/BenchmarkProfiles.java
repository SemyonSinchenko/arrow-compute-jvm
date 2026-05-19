package io.github.semyonsinchenko.arrowcompute.bench;

final class BenchmarkProfiles {
    static final long REQUIRED_SEED = 0xC0FFEEL;

    static final String[] ROWS_STANDARD = {"1024", "16384", "65536", "1048576"};
    static final String[] NULLS_WRAPPER_DISPATCH = {"0", "1", "10", "30"};
    static final String[] NULLS_AGG_WRAPPER_DISPATCH = {"0", "1", "10", "30", "100"};

    private BenchmarkProfiles() {
    }
}
