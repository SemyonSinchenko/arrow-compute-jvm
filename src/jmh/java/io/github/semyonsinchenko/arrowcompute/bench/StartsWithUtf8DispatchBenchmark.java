package io.github.semyonsinchenko.arrowcompute.bench;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.StartsWithUtf8;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
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
public class StartsWithUtf8DispatchBenchmark implements BenchmarkMetadataProvider {
    private static final long SEED = BenchmarkProfiles.REQUIRED_SEED;
    @Param({"1024", "16384", "65536", "1048576"})
    public int rows;

    @Param({"2", "8", "16", "32"})
    public int needleLength;

    @Param({"0", "30"})
    public int nullPercent;

    private RootAllocator root;
    private BufferAllocator child;
    private VarCharVector input;
    private byte[] needle;

    private BufferRefs refs;

    @Setup(Level.Trial)
    public void setUp() {
        BenchmarkSupport.validateTrial(this, SEED);
        root = new RootAllocator();
        child = root.newChildAllocator("startswith-path", 0, Long.MAX_VALUE);
        input = new VarCharVector("input", child);
        input.allocateNew();

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

        refs = BufferRefs.retain(input);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        refs.close();
        input.close();
        child.close();
        root.close();
    }

    @Benchmark
    public void wrapperEval(Blackhole bh) {
        try (var out = new BitVector("out", child)) {
            out.allocateNew(rows);
            StartsWithUtf8.eval(input, needle, out);
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
    public String type() { return "utf8-startswith"; }
    @Override
    public String benchmarkId() { return "startswith-utf8-layer"; }
    @Override
    public int rows() { return rows; }
    @Override
    public int nullPercent() { return nullPercent; }
}
