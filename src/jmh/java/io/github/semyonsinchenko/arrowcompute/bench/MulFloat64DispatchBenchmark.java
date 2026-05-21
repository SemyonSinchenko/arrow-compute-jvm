package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.MulFloat64;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
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

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MulFloat64DispatchBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private Float8Vector left;
    private Float8Vector right;
    private Float8Vector reusedOut;
    private BufferRefs refs;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        var rnd = new Random(SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("mul-float64-path", 0, Long.MAX_VALUE);
        left = new Float8Vector("left", child);
        right = new Float8Vector("right", child);
        left.allocateNew(rows);
        right.allocateNew(rows);
        for (int i = 0; i < rows; i++) {
            boolean lValid = BenchmarkSupport.isValidAt(i, nullPercent, 0);
            boolean rValid = BenchmarkSupport.isValidAt(i, nullPercent, 13);
            double lv = rnd.nextDouble() * 1000.0d - 500.0d;
            double rv = rnd.nextDouble() * 1000.0d - 500.0d;
            if (lValid) {
                left.set(i, lv);
            } else {
                left.setNull(i);
            }
            if (rValid) {
                right.set(i, rv);
            } else {
                right.setNull(i);
            }
        }
        left.setValueCount(rows);
        right.setValueCount(rows);

        refs = BufferRefs.retain(left, right);

        reusedOut = new Float8Vector("reusedOut", child);
        reusedOut.allocateNew(rows);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        reusedOut.close();
        right.close();
        left.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void wrapperEvalNewOutput(Blackhole bh) {
        try (var out = new Float8Vector("out", child)) {
            out.allocateNew(rows);
            MulFloat64.eval(left, right, out);
            bh.consume(out);
        }
    }

    @Benchmark
    public void wrapperEvalReusedOutput(Blackhole bh) {
        MulFloat64.eval(left, right, reusedOut);
        bh.consume(reusedOut);
    }

    @Benchmark
    public void dispatchSmoke(Blackhole bh) {
        if (rows != 1048576 || nullPercent != 0) {
            return;
        }
        try (var out = new Float8Vector("out", child)) {
            out.allocateNew(rows);
            Compute.mul(left, right, out);
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
    public String type() { return "float64-mul"; }
    @Override
    public String benchmarkId() { return "mul-float64-layer"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
}
