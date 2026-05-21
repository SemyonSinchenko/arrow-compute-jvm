package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg.SumInt64;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Question: what is overhead from wrapper and facade for int64 sum?
 * Baseline: wrapper for facade overhead.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SumInt64DispatchBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private BigIntVector input;
    private BufferRefs refs;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("sum-int64-path", 0, Long.MAX_VALUE);
        input = new BigIntVector("input", child);
        input.allocateNew(rows);

        for (int i = 0; i < rows; i++) {
            boolean valid = BenchmarkSupport.isValidAt(i, nullPercent, 0);
            if (valid) {
                input.set(i, i * 19L - 333L);
            } else {
                input.setNull(i);
            }
        }
        input.setValueCount(rows);

        refs = BufferRefs.retain(input);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        input.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void wrapperEvalThin(Blackhole bh) {
        try (var out = new BigIntVector("out", child)) {
            out.allocateNew(1);
            SumInt64.eval(input, out);
            bh.consume(out);
        }
    }

    @Benchmark
    public void dispatchSmoke(Blackhole bh) {
        if (rows != 1048576 || nullPercent != 0) {
            return;
        }
        try (var out = new BigIntVector("out", child)) {
            out.allocateNew(1);
            Compute.sum(input, out);
            bh.consume(out);
        }
    }

    @Override
    public String layer() { return "dispatch"; }
    @Override
    public String question() { return "Is dispatch overhead acceptable?"; }
    @Override
    public String baseline() { return "wrapper"; }
    @Override
    public String type() { return "int64-sum"; }
    @Override
    public String benchmarkId() { return "sum-int64-layer"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
}
