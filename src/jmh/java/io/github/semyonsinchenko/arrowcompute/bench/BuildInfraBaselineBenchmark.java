package io.github.semyonsinchenko.arrowcompute.bench;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class BuildInfraBaselineBenchmark {
    @Benchmark
    public int baselineIncrement() {
        int x = 0;
        for (int i = 0; i < 64; i++) {
            x += i;
        }
        return x;
    }
}
