package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.codegen.MulFloat64CodeGen;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import java.lang.invoke.MethodHandle;
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
public class MulFloat64CodeGenBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private Float8Vector left;
    private Float8Vector right;
    private Float8Vector reusedOut;
    private BufferRefs refs;
    private MethodHandle computeAllHandle;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        computeAllHandle = new MulFloat64CodeGen().loadComputeAllHandle();

        var rnd = new Random(SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("mul-float64-codegen", 0, Long.MAX_VALUE);

        left = new Float8Vector("left", child);
        right = new Float8Vector("right", child);
        reusedOut = new Float8Vector("reusedOut", child);

        left.allocateNew(rows);
        right.allocateNew(rows);
        reusedOut.allocateNew(rows);

        for (int i = 0; i < rows; i++) {
            left.set(i, rnd.nextDouble() * 1000.0d - 500.0d);
            right.set(i, rnd.nextDouble() * 1000.0d - 500.0d);
        }

        left.setValueCount(rows);
        right.setValueCount(rows);
        refs = BufferRefs.retain(left, right, reusedOut);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        reusedOut.close();
        right.close();
        left.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void wrapperEvalReusedOutput(Blackhole bh) throws Throwable {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(reusedOut, n);
        Checks.zeroSliceOffset(left, right, reusedOut);

        long byteSize = (long) n * Double.BYTES;
        var leftData = SegmentViews.data(left, byteSize);
        var rightData = SegmentViews.data(right, byteSize);
        var outData = SegmentViews.data(reusedOut, byteSize);
        computeAllHandle.invokeExact(leftData, rightData, outData, n);
        reusedOut.setValueCount(n);
        bh.consume(reusedOut);
    }

    @Override
    public String layer() { return "codegen"; }
    @Override
    public String question() { return "Does Janino accept VectorAPI imports?"; }
    @Override
    public String baseline() { return "raw"; }
    @Override
    public String type() { return "float64-mul"; }
    @Override
    public String benchmarkId() { return "mul-float64-codegen"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
    @Override
    public String outputAllocationPolicy() { return "reused"; }
    @Override
    public String scenarioLabel() { return "codegen-reused-output"; }
}
