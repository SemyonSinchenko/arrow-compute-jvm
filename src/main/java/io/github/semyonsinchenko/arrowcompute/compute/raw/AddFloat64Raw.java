package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * AddFloat64 raw kernel.
 *
 * <p>Inputs: float64 vector/vector contiguous buffers. Null policy: none (raw layer).
 * IEEE behavior: natural Java/IEEE-754 add semantics including NaN and Infinity.
 * Output validity: not handled here. Aliasing assumptions: caller provides non-overlapping
 * input/output segments.</p>
 */
public final class AddFloat64Raw {
    public static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    public static final ValueLayout.OfDouble FLOAT64_LE = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private AddFloat64Raw() {
    }

    public static void computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n) {
        int i = 0;
        int upper = SPECIES.loopBound(n);

        for (; i < upper; i += SPECIES.length()) {
            long off = (long) i * Double.BYTES;
            var x = DoubleVector.fromMemorySegment(SPECIES, left, off, BYTE_ORDER);
            var y = DoubleVector.fromMemorySegment(SPECIES, right, off, BYTE_ORDER);
            x.add(y).intoMemorySegment(out, off, BYTE_ORDER);
        }

        for (; i < n; i++) {
            long off = (long) i * Double.BYTES;
            double x = left.get(FLOAT64_LE, off);
            double y = right.get(FLOAT64_LE, off);
            out.set(FLOAT64_LE, off, x + y);
        }
    }
}
