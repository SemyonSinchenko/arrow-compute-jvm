package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Raw;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.MulFloat64;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import java.lang.foreign.MemorySegment;
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
public class MulFloat64PathBenchmark {
    private static final long SEED = 0xC0FFEEL;

    @Param({"4096", "16384", "65536"})
    public int rows;

    @Param({"0", "1", "10", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private Float8Vector left;
    private Float8Vector right;
    private Float8Vector out;
    private BufferRefs refs;
    private MemorySegment leftData;
    private MemorySegment rightData;
    private MemorySegment outData;

    @Setup(Level.Trial)
    public void setUp() {
        var rnd = new Random(SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("mul-float64-path", 0, Long.MAX_VALUE);
        left = new Float8Vector("left", child);
        right = new Float8Vector("right", child);
        out = new Float8Vector("out", child);
        left.allocateNew(rows);
        right.allocateNew(rows);
        out.allocateNew(rows);
        for (int i = 0; i < rows; i++) {
            boolean lValid = (i % 100) >= nullPercent;
            boolean rValid = ((i + 13) % 100) >= nullPercent;
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

        refs = BufferRefs.retain(left, right, out);
        long byteSize = (long) rows * Double.BYTES;
        leftData = SegmentViews.data(left, byteSize);
        rightData = SegmentViews.data(right, byteSize);
        outData = SegmentViews.data(out, byteSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        out.close();
        right.close();
        left.close();
        child.close();
        root.close();
    }

    @Setup(Level.Invocation)
    public void clearOut() {
        out.setValueCount(0);
    }

    @Benchmark
    public void rawComputeAll(Blackhole bh) {
        MulFloat64Raw.computeAll(leftData, rightData, outData, rows);
        bh.consume(outData);
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        MulFloat64.eval(left, right, out);
        bh.consume(out);
    }

    @Benchmark
    public void apiComputeMul(Blackhole bh) {
        Compute.mul(left, right, out);
        bh.consume(out);
    }
}
