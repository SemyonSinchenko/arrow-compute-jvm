package io.github.semyonsinchenko.arrowcompute.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
public class BuildInfraBaselineInfraBenchmark implements BenchmarkMetadataProvider {
    @Setup
    public void setUp() {
        BenchmarkSuiteValidator.validateParams(benchmarkId(), rows(), nullPercent());
        metadata();
    }

    @Benchmark
    public int baselineIncrement() {
        int x = 0;
        for (int i = 0; i < 64; i++) {
            x += i;
        }
        return x;
    }

    @Override
    public String layer() { return "infra"; }
    @Override
    public String question() { return "Is JMH build/run wiring healthy?"; }
    @Override
    public String baseline() { return "self baseline"; }
    @Override
    public String type() { return "infra-baseline"; }
    @Override
    public String benchmarkId() { return "build-infra"; }
    @Override
    public int rows() { return 1024; }
    @Override
    public int nullPercent() { return 0; }
}
