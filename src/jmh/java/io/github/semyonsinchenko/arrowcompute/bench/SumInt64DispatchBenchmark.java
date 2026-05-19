package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.raw.SumInt64Raw;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg.SumInt64;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVectorHelper;
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

/**
 * Question: what is overhead from wrapper and facade for int64 sum?
 * Baselines: raw for wrapper overhead, wrapper for facade overhead.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SumInt64DispatchBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0", "1", "10", "30", "100"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private BigIntVector input;
    private BigIntVector out;
    private BufferRefs refs;
    private MemorySegment inputData;
    private MemorySegment inputValidity;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("sum-int64-path", 0, Long.MAX_VALUE);
        input = new BigIntVector("input", child);
        out = new BigIntVector("out", child);
        input.allocateNew(rows);
        out.allocateNew(1);

        for (int i = 0; i < rows; i++) {
            boolean valid = BenchmarkSupport.isValidAt(i, nullPercent, 0);
            if (valid) {
                input.set(i, i * 19L - 333L);
            } else {
                input.setNull(i);
            }
        }
        input.setValueCount(rows);

        refs = BufferRefs.retain(input, out);
        inputData = SegmentViews.data(input, (long) rows * Long.BYTES);
        long validityBytes = BitVectorHelper.getValidityBufferSize(rows);
        inputValidity = SegmentViews.validity(input, validityBytes);
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
        BenchmarkSupport.clearOut(out);
    }

    @Benchmark
    public void raw(Blackhole bh) {
        long sum = nullPercent == 0
                ? SumInt64Raw.noNulls(inputData, rows)
                : SumInt64Raw.skipNulls(inputData, inputValidity, rows);
        bh.consume(sum);
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        SumInt64.eval(input, out);
        bh.consume(out);
    }

    @Benchmark
    public void apiComputeSum(Blackhole bh) {
        Compute.sum(input, out);
        bh.consume(out);
    }

    @Override
    public String layer() { return "dispatch"; }
    @Override
    public String question() { return "Is dispatch overhead acceptable?"; }
    @Override
    public String baseline() { return "wrapper"; }
    @Override
    public String type() { return "int64-sum"; }
    @Override
    public String benchmarkId() { return "sum-int64-layer"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
}
