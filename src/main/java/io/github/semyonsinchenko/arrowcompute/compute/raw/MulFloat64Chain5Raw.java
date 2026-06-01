package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Left-associative float64 chain multiply raw kernel for x^5.
 *
 * <p>Inputs: one float64 data segment and one output data segment. Null policy: none (raw layer).
 * Associativity rule: strict left-associative chain. Lifecycle/aliasing: caller owns segment
 * lifetime and supplies non-overlapping input/output segments.</p>
 */
public final class MulFloat64Chain5Raw {
    public static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    public static final ValueLayout.OfDouble FLOAT64_LE =
            ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private MulFloat64Chain5Raw() {
    }

    public static void computeAll(MemorySegment x, MemorySegment out, int n) {
        int i = 0;
        int upper = SPECIES.loopBound(n);
        for (; i < upper; i += SPECIES.length()) {
            long off = (long) i * Double.BYTES;
            var v = DoubleVector.fromMemorySegment(SPECIES, x, off, BYTE_ORDER);
            v.mul(v).mul(v).mul(v).mul(v).intoMemorySegment(out, off, BYTE_ORDER);
        }

        for (; i < n; i++) {
            long off = (long) i * Double.BYTES;
            double v = x.get(FLOAT64_LE, off);
            out.set(FLOAT64_LE, off, (((v * v) * v) * v) * v);
        }
    }
}
