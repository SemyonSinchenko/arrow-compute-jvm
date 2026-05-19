package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.raw.StartsWithUtf8Raw;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.StartsWithUtf8;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.VarCharVector;
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
public class StartsWithUtf8PathBenchmark {
    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"2", "8", "16", "32"})
    public int needleLength;

    @Param({"0", "10"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private VarCharVector input;
    private BitVector out;
    private byte[] needle;

    private BufferRefs refs;
    private MemorySegment offsetsSeg;
    private MemorySegment dataSeg;
    private MemorySegment outBitsSeg;

    @Setup(Level.Trial)
    public void setUp() {
        root = new RootAllocator();
        child = root.newChildAllocator("startswith-path", 0, Long.MAX_VALUE);
        input = new VarCharVector("input", child);
        out = new BitVector("out", child);
        input.allocateNew();
        out.allocateNew(rows);

        byte[] base = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8);
        needle = new byte[needleLength];
        for (int i = 0; i < needleLength; i++) {
            needle[i] = base[i % base.length];
        }

        int rowLen = needleLength + 16;
        for (int i = 0; i < rows; i++) {
            boolean isNull = (i % 100) < nullPercent;
            if (isNull) {
                input.setNull(i);
                continue;
            }
            byte[] row = new byte[rowLen];
            boolean match = (i & 1) == 0;
            for (int j = 0; j < rowLen; j++) {
                byte b = (byte) ('a' + ((i + j) % 26));
                if (j < needleLength) {
                    b = match ? needle[j] : (byte) (needle[j] ^ 0x01);
                }
                row[j] = b;
            }
            input.setSafe(i, row);
        }
        input.setValueCount(rows);

        refs = BufferRefs.retain(input, out);
        long offsetsBytes = (long) (rows + 1) * Integer.BYTES;
        long outBitsBytes = (rows + 7L) >>> 3;
        offsetsSeg = SegmentViews.fromArrowBuf(input.getOffsetBuffer(), offsetsBytes);
        dataSeg = SegmentViews.fromArrowBuf(input.getDataBuffer(), input.getDataBuffer().capacity());
        outBitsSeg = SegmentViews.fromArrowBuf(out.getDataBuffer(), outBitsBytes);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        out.close();
        input.close();
        child.close();
        root.close();
    }

    @Setup(Level.Invocation)
    public void clearOut() {
        out.setValueCount(0);
    }

    @Benchmark
    public void rawComputeAll(Blackhole bh) {
        StartsWithUtf8Raw.computeAll(offsetsSeg, dataSeg, needle, outBitsSeg, rows);
        bh.consume(outBitsSeg);
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        StartsWithUtf8.eval(input, needle, out);
        bh.consume(out);
    }

    @Benchmark
    public void apiComputeStartsWith(Blackhole bh) {
        Compute.startsWith(input, needle, out);
        bh.consume(out);
    }
}
