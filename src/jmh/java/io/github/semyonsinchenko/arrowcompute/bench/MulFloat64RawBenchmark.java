package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
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
public class MulFloat64RawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;
    private static final ValueLayout.OfDouble FLOAT64_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

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
        long bytes = (long) rows * Double.BYTES;
        left = arena.allocate(bytes);
        right = arena.allocate(bytes);
        out = arena.allocate(bytes);

        var rnd = new Random(SEED);
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Double.BYTES;
            left.set(FLOAT64_LE, off, rnd.nextDouble() * 2000.0d - 1000.0d);
            right.set(FLOAT64_LE, off, rnd.nextDouble() * 2000.0d - 1000.0d);
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void vectorApi(Blackhole bh) {
        MulFloat64Raw.computeAll(left, right, out, rows);
        bh.consume(out);
    }

    @Benchmark
    public void naiveMemorySegment(Blackhole bh) {
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Double.BYTES;
            double x = left.get(FLOAT64_LE, off);
            double y = right.get(FLOAT64_LE, off);
            out.set(FLOAT64_LE, off, x * y);
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
    public String type() { return "float64-mul"; }
    @Override
    public String benchmarkId() { return "mul-float64-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
