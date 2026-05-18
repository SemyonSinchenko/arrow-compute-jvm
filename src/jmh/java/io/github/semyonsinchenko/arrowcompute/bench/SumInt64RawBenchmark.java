package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.SumInt64Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Question: is SumInt64Raw.noNulls faster than a naive MemorySegment loop?
 * Baseline: naiveMemorySegment.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SumInt64RawBenchmark {
    @Param({"4096", "16384", "65536", "262144"})
    public int rows;

    private Arena arena;
    private MemorySegment input;

    @Setup
    public void setUp() {
        arena = Arena.ofConfined();
        input = arena.allocate((long) rows * Long.BYTES);
        for (int i = 0; i < rows; i++) {
            input.set(SumInt64Raw.INT64_LE, (long) i * Long.BYTES, i * 37L - 1234L);
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void rawNoNulls(Blackhole bh) {
        bh.consume(SumInt64Raw.noNulls(input, rows));
    }

    @Benchmark
    public void naiveMemorySegment(Blackhole bh) {
        long sum = 0L;
        for (int i = 0; i < rows; i++) {
            sum += input.get(SumInt64Raw.INT64_LE, (long) i * Long.BYTES);
        }
        bh.consume(sum);
    }
}
