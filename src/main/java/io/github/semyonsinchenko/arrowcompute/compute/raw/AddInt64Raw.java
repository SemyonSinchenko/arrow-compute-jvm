package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * AddInt64 raw kernel.
 *
 * <p>Inputs: int64 vector/vector contiguous buffers. Null policy: none (raw layer).
 * Overflow semantics: Java long wraparound. Output validity: not handled here.
 * Aliasing assumptions: caller provides non-overlapping input/output segments.</p>
 */
public final class AddInt64Raw {
    public static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
    public static final ValueLayout.OfLong INT64_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private AddInt64Raw() {
    }

    public static void computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n) {
        int i = 0;
        int upper = SPECIES.loopBound(n);

        for (; i < upper; i += SPECIES.length()) {
            long off = (long) i * Long.BYTES;
            var x = LongVector.fromMemorySegment(SPECIES, left, off, BYTE_ORDER);
            var y = LongVector.fromMemorySegment(SPECIES, right, off, BYTE_ORDER);
            x.add(y).intoMemorySegment(out, off, BYTE_ORDER);
        }

        for (; i < n; i++) {
            long off = (long) i * Long.BYTES;
            long x = left.get(INT64_LE, off);
            long y = right.get(INT64_LE, off);
            out.set(INT64_LE, off, x + y);
        }
    }
}
