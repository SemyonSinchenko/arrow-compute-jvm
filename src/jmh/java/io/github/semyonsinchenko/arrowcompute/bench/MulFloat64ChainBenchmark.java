package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.Compute;
import io.github.semyonsinchenko.arrowcompute.compute.codegen.MulFloat64ChainCodeGen;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain20Raw;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain50Raw;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain5Raw;
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
public class MulFloat64ChainBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"5", "20", "50"})
    public int k;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private Float8Vector x;
    private Float8Vector out1;
    private Float8Vector out2;
    private Float8Vector reusedOut;
    private BufferRefs refs;
    private MethodHandle janinoHandle;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSuiteValidator.validateSeed(SEED);
        BenchmarkSuiteValidator.validateParams(benchmarkId(), rows, nullPercent);

        root = new RootAllocator();
        child = root.newChildAllocator("mul-float64-chain", 0, Long.MAX_VALUE);

        x = new Float8Vector("x", child);
        out1 = new Float8Vector("out1", child);
        out2 = new Float8Vector("out2", child);
        reusedOut = new Float8Vector("reusedOut", child);

        x.allocateNew(rows);
        out1.allocateNew(rows);
        out2.allocateNew(rows);
        reusedOut.allocateNew(rows);

        var rnd = new Random(SEED);
        for (int i = 0; i < rows; i++) {
            x.set(i, rnd.nextDouble() * 2.0d - 1.0d);
        }
        x.setValueCount(rows);
        refs = BufferRefs.retain(x, out1, out2, reusedOut);
        janinoHandle = new MulFloat64ChainCodeGen().loadComputeAllHandle(k);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        reusedOut.close();
        out2.close();
        out1.close();
        x.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void naiveChainReusedPingPong(Blackhole bh) {
        Float8Vector left = x;
        Float8Vector right = x;
        Float8Vector out = out1;

        Compute.mul(left, right, out);
        left = out;

        for (int i = 3; i <= k; i++) {
            out = (out == out1) ? out2 : out1;
            Compute.mul(left, x, out);
            left = out;
        }

        bh.consume(left);
    }

    @Benchmark
    public void naiveChainPerCallAlloc(Blackhole bh) {
        var temporaries = new java.util.ArrayList<Float8Vector>(k - 1);
        try {
            var first = new Float8Vector("tmp2", child);
            first.allocateNew(rows);
            temporaries.add(first);
            Compute.mul(x, x, first);

            Float8Vector left = first;
            for (int i = 3; i <= k; i++) {
                var tmp = new Float8Vector("tmp" + i, child);
                tmp.allocateNew(rows);
                temporaries.add(tmp);
                Compute.mul(left, x, tmp);
                left = tmp;
            }
            bh.consume(left);
        } finally {
            for (int i = temporaries.size() - 1; i >= 0; i--) {
                temporaries.get(i).close();
            }
        }
    }

    @Benchmark
    public void fusedJaninoReusedOutput(Blackhole bh) throws Throwable {
        Checks.outputCapacity(reusedOut, rows);
        Checks.zeroSliceOffset(x, reusedOut);
        long byteSize = (long) rows * Double.BYTES;
        var xData = SegmentViews.data(x, byteSize);
        var outData = SegmentViews.data(reusedOut, byteSize);
        janinoHandle.invokeExact(xData, outData, rows);
        reusedOut.setValueCount(rows);
        bh.consume(reusedOut);
    }

    @Benchmark
    public void fusedAotReusedOutput(Blackhole bh) {
        Checks.outputCapacity(reusedOut, rows);
        Checks.zeroSliceOffset(x, reusedOut);
        long byteSize = (long) rows * Double.BYTES;
        var xData = SegmentViews.data(x, byteSize);
        var outData = SegmentViews.data(reusedOut, byteSize);
        switch (k) {
            case 5 -> MulFloat64Chain5Raw.computeAll(xData, outData, rows);
            case 20 -> MulFloat64Chain20Raw.computeAll(xData, outData, rows);
            case 50 -> MulFloat64Chain50Raw.computeAll(xData, outData, rows);
            default -> throw new IllegalArgumentException("Unsupported chain depth k=" + k);
        }
        reusedOut.setValueCount(rows);
        bh.consume(reusedOut);
    }

    @Override
    public String layer() { return "codegen"; }

    @Override
    public String question() { return "Where are MulFloat64 chain fusion floor and ceiling in warmed JVM?"; }

    @Override
    public String baseline() { return "naive-chain"; }

    @Override
    public String type() { return "float64-mul-chain"; }

    @Override
    public String benchmarkId() { return "mul-float64-chain-codegen"; }

    @Override
    public int rows() { return rows; }

    @Override
    public int nullPercent() { return nullPercent; }

    @Override
    public String outputAllocationPolicy() { return "reused|per-call"; }

    @Override
    public String scenarioLabel() { return "A1/A2/B/C"; }
}
