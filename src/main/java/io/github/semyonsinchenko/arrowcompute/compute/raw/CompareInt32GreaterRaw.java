package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * CompareInt32Greater raw kernel.
 *
 * <p>Operation: int32 greater-than (left > right). Physical types: int32 data buffers and bit-packed
 * boolean output data buffer. Null policy: none (raw layer). Error behavior: none. Output validity
 * rule: not handled here. Tail policy: writes only in-range bits and keeps out-of-range bits clear.
 * Aliasing assumptions: caller passes non-overlapping segments.</p>
 */
public final class CompareInt32GreaterRaw {
    private static final long INT_BYTES = Integer.BYTES;
    private static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private CompareInt32GreaterRaw() {
    }

    public static void computeAll(MemorySegment leftData, MemorySegment rightData, MemorySegment outValueBitmap, int n) {
        if (n == 0) {
            return;
        }

        int outBytes = (n + 7) >>> 3;
        for (int i = 0; i < outBytes; i++) {
            outValueBitmap.set(ValueLayout.JAVA_BYTE, i, (byte) 0);
        }

        for (int i = 0; i < n; i++) {
            long off = (long) i * INT_BYTES;
            int left = leftData.get(INT32_LE, off);
            int right = rightData.get(INT32_LE, off);
            if (left > right) {
                long byteOff = i >>> 3;
                int bit = i & 7;
                int base = Byte.toUnsignedInt(outValueBitmap.get(ValueLayout.JAVA_BYTE, byteOff));
                outValueBitmap.set(ValueLayout.JAVA_BYTE, byteOff, (byte) (base | (1 << bit)));
            }
        }
    }
}
