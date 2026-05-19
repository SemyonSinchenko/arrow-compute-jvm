package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.CompareInt32GreaterRaw;
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
public class BooleanPackingRawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;
    private static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    private Arena arena;
    private MemorySegment left;
    private MemorySegment right;
    private MemorySegment outBits;
    private MemorySegment tmpBytePerBool;

    @Setup
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        arena = Arena.ofConfined();
        long dataBytes = (long) rows * Integer.BYTES;
        int bitmapBytes = (rows + 7) >>> 3;
        left = arena.allocate(dataBytes);
        right = arena.allocate(dataBytes);
        outBits = arena.allocate(bitmapBytes);
        tmpBytePerBool = arena.allocate(rows);
        var rnd = new Random(SEED + rows);
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
    public void packedWriteKernel(Blackhole bh) {
        CompareInt32GreaterRaw.computeAll(left, right, outBits, rows);
        bh.consume(outBits);
    }

    @Benchmark
    public void bytePerBoolThenPackBaseline(Blackhole bh) {
        for (int i = 0; i < rows; i++) {
            long off = (long) i * Integer.BYTES;
            int l = left.get(INT32_LE, off);
            int r = right.get(INT32_LE, off);
            tmpBytePerBool.set(ValueLayout.JAVA_BYTE, i, (byte) (l > r ? 1 : 0));
        }

        int outBytes = (rows + 7) >>> 3;
        for (int i = 0; i < outBytes; i++) {
            outBits.set(ValueLayout.JAVA_BYTE, i, (byte) 0);
        }

        for (int i = 0; i < rows; i++) {
            if (tmpBytePerBool.get(ValueLayout.JAVA_BYTE, i) != 0) {
                int b = i >>> 3;
                int bit = i & 7;
                int v = Byte.toUnsignedInt(outBits.get(ValueLayout.JAVA_BYTE, b));
                outBits.set(ValueLayout.JAVA_BYTE, b, (byte) (v | (1 << bit)));
            }
        }
        bh.consume(outBits);
    }

    @Override
    public String layer() { return "raw-vector"; }
    @Override
    public String question() { return "Is Vector API doing its job?"; }
    @Override
    public String baseline() { return "byte-per-bool then pack"; }
    @Override
    public String type() { return "int32-compare-gt-pack"; }
    @Override
    public String benchmarkId() { return "boolean-packing-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
