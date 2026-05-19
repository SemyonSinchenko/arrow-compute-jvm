package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.DivInt32Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Random;
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

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DivInt32RawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    private Arena arena;
    private MemorySegment left;
    private MemorySegment right;
    private MemorySegment out;

    @Setup
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        arena = Arena.ofConfined();
        long bytes = (long) rows * Integer.BYTES;
        left = arena.allocate(bytes);
        right = arena.allocate(bytes);
        out = arena.allocate(bytes);

        var rnd = new Random(SEED);
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            int l = rnd.nextInt();
            int r = (rnd.nextInt() & Integer.MAX_VALUE) + 1;
            left.set(DivInt32Raw.INT32_LE, off, l);
            right.set(DivInt32Raw.INT32_LE, off, r);
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void rawNoNulls(Blackhole bh) {
        DivInt32Raw.noNulls(left, right, out, rows);
        bh.consume(out);
    }

    @Benchmark
    public void naiveMemorySegment(Blackhole bh) {
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            int l = left.get(DivInt32Raw.INT32_LE, off);
            int r = right.get(DivInt32Raw.INT32_LE, off);
            out.set(DivInt32Raw.INT32_LE, off, l / r);
        }
        bh.consume(out);
    }

    @Override
    public String layer() { return "raw-vector"; }
    @Override
    public String question() { return "Is Vector API doing its job?"; }
    @Override
    public String baseline() { return "naive MemorySegment loop"; }
    @Override
    public String type() { return "int32-div"; }
    @Override
    public String benchmarkId() { return "div-int32-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
