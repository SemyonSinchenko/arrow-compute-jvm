package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * DivInt32 raw kernel.
 *
 * <p>Operation: checked-wrapper-owned int32 division. Physical types: int32 data buffers.
 * Null policy: none in raw layer; wrapper chooses noNulls or validOnly path.
 * Checked behavior: divide traps are prechecked by wrapper and never validated here.
 * Output validity rule: not handled in raw layer. Aliasing/lifetime assumptions:
 * non-overlapping segments and wrapper-bounded segment lifetime.</p>
 */
public final class DivInt32Raw {
    public static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private DivInt32Raw() {
    }

    public static void noNulls(MemorySegment left, MemorySegment right, MemorySegment out, int n) {
        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            int l = left.get(INT32_LE, off);
            int r = right.get(INT32_LE, off);
            out.set(INT32_LE, off, l / r);
        }
    }

    public static void validOnly(MemorySegment left, MemorySegment right, MemorySegment out, MemorySegment activeValidity, int n) {
        for (int i = 0; i < n; i++) {
            if (!isActive(activeValidity, i)) {
                continue;
            }
            long off = (long) i * Integer.BYTES;
            int l = left.get(INT32_LE, off);
            int r = right.get(INT32_LE, off);
            out.set(INT32_LE, off, l / r);
        }
    }

    private static boolean isActive(MemorySegment validity, int row) {
        long byteIndex = row >>> 3;
        int bitMask = 1 << (row & 7);
        int bits = Byte.toUnsignedInt(validity.get(ValueLayout.JAVA_BYTE, byteIndex));
        return (bits & bitMask) != 0;
    }
}
