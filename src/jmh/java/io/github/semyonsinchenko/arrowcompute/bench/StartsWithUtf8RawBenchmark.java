package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.raw.StartsWithUtf8Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
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
public class StartsWithUtf8RawBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;
    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"2", "8", "16", "32"})
    public int needleLength;

    private Arena arena;
    private MemorySegment offsets;
    private MemorySegment data;
    private MemorySegment outBits;
    private byte[] needle;

    @Setup
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        arena = Arena.ofConfined();
        byte[] base = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8);
        needle = new byte[needleLength];
        for (int i = 0; i < needleLength; i++) {
            needle[i] = base[i % base.length];
        }

        int rowLen = needleLength + 16;
        long dataBytes = (long) rows * rowLen;
        offsets = arena.allocate((long) (rows + 1) * Integer.BYTES);
        data = arena.allocate(Math.max(1L, dataBytes));
        outBits = arena.allocate((rows + 7L) >>> 3);

        offsets.set(StartsWithUtf8Raw.INT32_LE, 0, 0);
        int off = 0;
        for (int i = 0; i < rows; i++) {
            boolean match = (i & 1) == 0;
            for (int j = 0; j < rowLen; j++) {
                byte b = (byte) ('a' + ((i + j) % 26));
                if (j < needleLength) {
                    b = match ? needle[j] : (byte) (needle[j] ^ 0x01);
                }
                data.set(StartsWithUtf8Raw.BYTE, off + j, b);
            }
            off += rowLen;
            offsets.set(StartsWithUtf8Raw.INT32_LE, (long) (i + 1) * Integer.BYTES, off);
        }
    }

    @TearDown
    public void tearDown() {
        arena.close();
    }

    @Benchmark
    public void vectorApi(Blackhole bh) {
        StartsWithUtf8Raw.computeAll(offsets, data, needle, outBits, rows);
        bh.consume(outBits);
    }

    @Benchmark
    public void naiveMemorySegment(Blackhole bh) {
        int outBytes = (rows + 7) >>> 3;
        outBits.asSlice(0, outBytes).fill((byte) 0);
        for (int row = 0; row < rows; row++) {
            long start = Integer.toUnsignedLong(offsets.get(StartsWithUtf8Raw.INT32_LE, (long) row * Integer.BYTES));
            long end = Integer.toUnsignedLong(offsets.get(StartsWithUtf8Raw.INT32_LE, (long) (row + 1) * Integer.BYTES));
            long len = end - start;
            if (len < needle.length) {
                continue;
            }
            boolean matched = true;
            for (int j = 0; j < needle.length; j++) {
                if (data.get(StartsWithUtf8Raw.BYTE, start + j) != needle[j]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                long byteIndex = row >>> 3;
                int cur = Byte.toUnsignedInt(outBits.get(StartsWithUtf8Raw.BYTE, byteIndex));
                outBits.set(StartsWithUtf8Raw.BYTE, byteIndex, (byte) (cur | (1 << (row & 7))));
            }
        }
        bh.consume(outBits);
    }

    @Override
    public String layer() { return "raw-vector"; }
    @Override
    public String question() { return "Is Vector API doing its job?"; }
    @Override
    public String baseline() { return "naive MemorySegment loop"; }
    @Override
    public String type() { return "utf8-startswith"; }
    @Override
    public String benchmarkId() { return "startswith-utf8-raw-vector"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return 0; }
}
