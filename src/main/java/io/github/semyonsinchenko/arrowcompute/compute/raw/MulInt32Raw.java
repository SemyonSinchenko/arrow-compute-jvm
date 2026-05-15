package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * MulInt32 raw kernel.
 *
 * <p>Inputs: int32 vector/vector contiguous buffers. Null policy: none (raw layer).
 * Overflow semantics: Java int wraparound. Output validity: not handled here.
 * Aliasing assumptions: caller provides non-overlapping input/output segments.</p>
 */
public final class MulInt32Raw {
    public static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;
    public static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private MulInt32Raw() {
    }

    public static void computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n) {
        int i = 0;
        int upper = SPECIES.loopBound(n);

        for (; i < upper; i += SPECIES.length()) {
            long off = (long) i * Integer.BYTES;
            var x = IntVector.fromMemorySegment(SPECIES, left, off, BYTE_ORDER);
            var y = IntVector.fromMemorySegment(SPECIES, right, off, BYTE_ORDER);
            x.mul(y).intoMemorySegment(out, off, BYTE_ORDER);
        }

        for (; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            int x = left.get(INT32_LE, off);
            int y = right.get(INT32_LE, off);
            out.set(INT32_LE, off, x * y);
        }
    }
}
