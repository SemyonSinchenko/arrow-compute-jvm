package io.github.semyonsinchenko.arrowcompute.compute.codegen;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * Probe-only source holder and loader entrypoint for a runtime-compiled MulFloat64 kernel.
 *
 * <p>Operation scope: keep one immutable source string aligned with MulFloat64Raw semantics and
 * expose one MethodHandle loader path. Null policy: same as raw kernel (none at raw layer).
 * Lifecycle/aliasing: caller owns input/output buffers backing MemorySegment args. Non-goals:
 * template engines, expression trees, registries, and dispatch integration.</p>
 */
public final class MulFloat64CodeGen {
    public static final String DYNAMIC_CLASS_NAME = "io.github.semyonsinchenko.arrowcompute.compute.codegen.MulFloat64RawDynamic";

    public static final String MUL_FLOAT64_SOURCE = """
            package io.github.semyonsinchenko.arrowcompute.compute.codegen;

            import java.lang.foreign.MemorySegment;
            import java.lang.foreign.ValueLayout;
            import java.nio.ByteOrder;
            import jdk.incubator.vector.DoubleVector;
            import jdk.incubator.vector.VectorSpecies;

            public final class MulFloat64RawDynamic {
                public static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
                public static final ValueLayout.OfDouble FLOAT64_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
                public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

                private MulFloat64RawDynamic() {
                }

                public static void computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n) {
                    int i = 0;
                    int upper = SPECIES.loopBound(n);

                    for (; i < upper; i += SPECIES.length()) {
                        long off = (long) i * Double.BYTES;
                        DoubleVector x = DoubleVector.fromMemorySegment(SPECIES, left, off, BYTE_ORDER);
                        DoubleVector y = DoubleVector.fromMemorySegment(SPECIES, right, off, BYTE_ORDER);
                        x.mul(y).intoMemorySegment(out, off, BYTE_ORDER);
                    }

                    for (; i < n; i++) {
                        long off = (long) i * Double.BYTES;
                        double x = left.get(FLOAT64_LE, off);
                        double y = right.get(FLOAT64_LE, off);
                        out.set(FLOAT64_LE, off, x * y);
                    }
                }
            }
            """;

    private static final MethodType EXPECTED_TYPE = MethodType.methodType(
            void.class,
            MemorySegment.class,
            MemorySegment.class,
            MemorySegment.class,
            int.class
    );

    private final JaninoLoader loader;

    public MulFloat64CodeGen() {
        this(new JaninoLoader());
    }

    MulFloat64CodeGen(JaninoLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public MethodHandle loadComputeAllHandle() {
        if (!MUL_FLOAT64_SOURCE.contains("class MulFloat64RawDynamic")) {
            throw new CodeGenProbeException(
                    "CODEGEN-SOURCE-001",
                    "Dynamic kernel source is missing expected class declaration"
            );
        }
        if (!MUL_FLOAT64_SOURCE.contains("computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n)")) {
            throw new CodeGenProbeException(
                    "CODEGEN-SOURCE-002",
                    "Dynamic kernel source is missing expected computeAll signature"
            );
        }

        var handle = loader.compileToHandle(MUL_FLOAT64_SOURCE, DYNAMIC_CLASS_NAME, "computeAll");
        if (!EXPECTED_TYPE.equals(handle.type())) {
            throw new CodeGenProbeException(
                    "CODEGEN-SIGNATURE-001",
                    "Dynamic kernel method handle type mismatch"
            );
        }
        return handle;
    }
}
