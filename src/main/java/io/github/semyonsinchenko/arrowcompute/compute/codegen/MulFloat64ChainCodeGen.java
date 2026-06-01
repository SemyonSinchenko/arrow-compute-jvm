package io.github.semyonsinchenko.arrowcompute.compute.codegen;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Janino source generator/loader for left-associative MulFloat64 x^k chain probes.
 *
 * <p>Supported k set: 5, 20, 50. Null policy: none at raw layer. Associativity: strict
 * left-associative multiply chain in SIMD and scalar tail bodies. Lifecycle/aliasing: caller owns
 * backing Arrow buffers for all MemorySegment inputs/outputs.</p>
 */
public final class MulFloat64ChainCodeGen {
    private static final MethodType EXPECTED_TYPE =
            MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class);
    private static final AtomicLong NONCE = new AtomicLong();

    private final JaninoLoader loader;

    public MulFloat64ChainCodeGen() {
        this(new JaninoLoader());
    }

    MulFloat64ChainCodeGen(JaninoLoader loader) {
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public MethodHandle loadComputeAllHandle(int k) {
        validateK(k);
        String className = "io.github.semyonsinchenko.arrowcompute.compute.codegen.MulFloat64Chain"
                + k
                + "Dynamic"
                + NONCE.incrementAndGet();
        String source = buildSource(k, className);

        var handle = loader.compileToHandle(source, className, "computeAll", EXPECTED_TYPE);
        if (!EXPECTED_TYPE.equals(handle.type())) {
            throw new CodeGenProbeException("CODEGEN-SIGNATURE-CHAIN-001", "Dynamic chain kernel method handle type mismatch");
        }
        return handle;
    }

    public String buildSource(int k, String className) {
        validateK(k);
        Objects.requireNonNull(className, "className must not be null");

        int dot = className.lastIndexOf('.');
        if (dot <= 0 || dot == className.length() - 1) {
            throw new CodeGenProbeException("CODEGEN-SOURCE-CHAIN-001", "Dynamic class name must be fully-qualified");
        }
        String pkg = className.substring(0, dot);
        String simpleClass = className.substring(dot + 1);

        String vectorExpr = vectorChainExpression("v", k);
        String scalarExpr = scalarChainExpression("v", k);

        return """
                package %s;

                import java.lang.foreign.MemorySegment;
                import java.lang.foreign.ValueLayout;
                import java.nio.ByteOrder;
                import jdk.incubator.vector.DoubleVector;
                import jdk.incubator.vector.VectorSpecies;

                public final class %s {
                    public static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
                    public static final ValueLayout.OfDouble FLOAT64_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
                    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

                    private %s() {
                    }

                    public static void computeAll(MemorySegment x, MemorySegment out, int n) {
                        int i = 0;
                        int upper = SPECIES.loopBound(n);

                        for (; i < upper; i += SPECIES.length()) {
                            long off = (long) i * Double.BYTES;
                            DoubleVector v = DoubleVector.fromMemorySegment(SPECIES, x, off, BYTE_ORDER);
                            %s.intoMemorySegment(out, off, BYTE_ORDER);
                        }

                        for (; i < n; i++) {
                            long off = (long) i * Double.BYTES;
                            double v = x.get(FLOAT64_LE, off);
                            out.set(FLOAT64_LE, off, %s);
                        }
                    }
                }
                """.formatted(pkg, simpleClass, simpleClass, vectorExpr, scalarExpr);
    }

    private static String vectorChainExpression(String variable, int k) {
        String expr = variable;
        for (int i = 1; i < k; i++) {
            expr = expr + ".mul(" + variable + ")";
        }
        return expr;
    }

    private static String scalarChainExpression(String variable, int k) {
        String expr = variable;
        for (int i = 1; i < k; i++) {
            expr = "(" + expr + " * " + variable + ")";
        }
        return expr;
    }

    private static void validateK(int k) {
        if (k != 5 && k != 20 && k != 50) {
            throw new CodeGenProbeException("CODEGEN-K-001", "Unsupported chain depth k=" + k + "; expected one of [5, 20, 50]");
        }
    }
}
