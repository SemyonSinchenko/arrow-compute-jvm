package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.raw.AddInt32Raw;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddInt32;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
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

import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AddInt32PathBenchmark {
    @Param({"4096", "16384", "65536"})
    public int rows;

    @Param({"0", "1", "10", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private IntVector left;
    private IntVector right;
    private IntVector out;

    private BufferRefs refs;
    private MemorySegment leftData;
    private MemorySegment rightData;
    private MemorySegment outData;

    @Setup(Level.Trial)
    public void setUp() {
        root = new RootAllocator();
        child = root.newChildAllocator("add-int32-path", 0, Long.MAX_VALUE);
        left = new IntVector("left", child);
        right = new IntVector("right", child);
        out = new IntVector("out", child);
        left.allocateNew(rows);
        right.allocateNew(rows);
        out.allocateNew(rows);

        for (int i = 0; i < rows; i++) {
            int lv = i * 13 - 97;
            int rv = 1003 - i * 7;
            boolean lValid = (i % 100) >= nullPercent;
            boolean rValid = ((i + 17) % 100) >= nullPercent;
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
        long byteSize = (long) rows * Integer.BYTES;
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
        AddInt32Raw.computeAll(leftData, rightData, outData, rows);
        bh.consume(outData);
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        AddInt32.eval(left, right, out);
        bh.consume(out);
    }

    @Benchmark
    public void apiComputeAdd(Blackhole bh) {
        Compute.add(left, right, out);
        bh.consume(out);
    }
}
