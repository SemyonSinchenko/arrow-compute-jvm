package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.AddInt32Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Random;
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

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
/**
 * AddInt32Raw benchmark with an intentionally anti-vectorized scalar baseline.
 *
 * <p>WARNING: {@code naiveMemorySegment} is deliberately written to block SuperWord auto-vectorization
 * by introducing a loop-carried dependency that is value-neutral for output semantics.
 * This keeps the baseline scalar on purpose, but it is not zero-cost and can be measurably slower
 * (commonly around 5-15%) than a truly plain naive loop.</p>
 */
public class AddInt32RawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;
    private static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

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
            left.set(INT32_LE, off, rnd.nextInt());
            right.set(INT32_LE, off, rnd.nextInt());
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void vectorApi(Blackhole bh) {
        AddInt32Raw.computeAll(left, right, out, rows);
        bh.consume(out);
    }

    @Benchmark
    public void naiveMemorySegment(Blackhole bh) {
        // Intentionally anti-SuperWord: create a loop-carried scalar dependency without changing
        // the observable output of the addition kernel.
        int tail = 0;
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            int x = left.get(INT32_LE, off);
            int y = right.get(INT32_LE, off) ^ (tail & 0);
            int s = x + y;
            out.set(INT32_LE, off, s);
            tail = s;
        }
        bh.consume(tail);
        bh.consume(out);
    }

    @Override
    public String layer() { return "raw-vector"; }
    @Override
    public String question() { return "Is Vector API doing its job?"; }
    @Override
    public String baseline() { return "naive MemorySegment loop"; }
    @Override
    public String type() { return "int32-add"; }
    @Override
    public String benchmarkId() { return "add-int32-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
