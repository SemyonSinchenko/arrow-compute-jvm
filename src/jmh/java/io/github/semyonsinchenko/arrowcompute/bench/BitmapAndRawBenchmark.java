package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.memory.Bitmap;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
public class BitmapAndRawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    private Arena arena;
    private MemorySegment left;
    private MemorySegment right;
    private MemorySegment out;
    private int bytes;

    @Setup
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        arena = Arena.ofConfined();
        bytes = (rows + 7) >>> 3;
        left = arena.allocate(bytes);
        right = arena.allocate(bytes);
        out = arena.allocate(bytes);
        var rnd = new Random(SEED + rows);
        for (int i = 0; i < bytes; i++) {
            left.set(ValueLayout.JAVA_BYTE, i, (byte) rnd.nextInt());
            right.set(ValueLayout.JAVA_BYTE, i, (byte) rnd.nextInt());
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void bitmapAndWordWise(Blackhole bh) {
        Bitmap.and(left, right, out, rows);
        bh.consume(out);
    }

    @Benchmark
    public void bitmapAndScalarBaseline(Blackhole bh) {
        for (int i = 0; i < rows; i++) {
            int b = i >>> 3;
            int bit = i & 7;
            int l = (Byte.toUnsignedInt(left.get(ValueLayout.JAVA_BYTE, b)) >>> bit) & 1;
            int r = (Byte.toUnsignedInt(right.get(ValueLayout.JAVA_BYTE, b)) >>> bit) & 1;
            int ov = Byte.toUnsignedInt(out.get(ValueLayout.JAVA_BYTE, b));
            if ((l & r) != 0) {
                out.set(ValueLayout.JAVA_BYTE, b, (byte) (ov | (1 << bit)));
            } else {
                out.set(ValueLayout.JAVA_BYTE, b, (byte) (ov & ~(1 << bit)));
            }
        }
        bh.consume(out);
    }

    @Override
    public String layer() { return "raw-vector"; }
    @Override
    public String question() { return "Is Vector API doing its job?"; }
    @Override
    public String baseline() { return "scalar bitmap loop"; }
    @Override
    public String type() { return "bitmap-and"; }
    @Override
    public String benchmarkId() { return "bitmap-and-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
