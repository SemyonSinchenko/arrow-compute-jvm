package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddInt32;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
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

import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AddInt32DispatchBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;

    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"0", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private IntVector left;
    private IntVector right;
    private BufferRefs refs;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("add-int32-path", 0, Long.MAX_VALUE);
        left = new IntVector("left", child);
        right = new IntVector("right", child);
        left.allocateNew(rows);
        right.allocateNew(rows);

        for (int i = 0; i < rows; i++) {
            int lv = i * 13 - 97;
            int rv = 1003 - i * 7;
            boolean lValid = BenchmarkSupport.isValidAt(i, nullPercent, 0);
            boolean rValid = BenchmarkSupport.isValidAt(i, nullPercent, 17);
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

        refs = BufferRefs.retain(left, right);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        right.close();
        left.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        try (var out = new IntVector("out", child)) {
            out.allocateNew(rows);
            AddInt32.eval(left, right, out);
            bh.consume(out);
        }
    }

    @Override
    public String layer() { return "dispatch"; }
    @Override
    public String question() { return "Is dispatch overhead acceptable?"; }
    @Override
    public String baseline() { return "wrapper"; }
    @Override
    public String type() { return "int32-add"; }
    @Override
    public String benchmarkId() { return "add-int32-layer"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
}
