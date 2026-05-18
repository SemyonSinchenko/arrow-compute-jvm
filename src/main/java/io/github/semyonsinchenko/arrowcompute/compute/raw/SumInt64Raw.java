package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * SumInt64 raw kernel.
 *
 * <p>Input: int64 contiguous data buffer and optional validity buffer. Null policy: no-nulls or
 * skip-nulls path selected by wrapper. Overflow semantics: Java long wraparound. Output validity:
 * not handled in raw layer. Aliasing assumptions: caller provides valid non-overlapping segments.</p>
 */
public final class SumInt64Raw {
    public static final ValueLayout.OfLong INT64_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private SumInt64Raw() {
    }

    public static long noNulls(MemorySegment input, int n) {
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            long off = (long) i * Long.BYTES;
            sum += input.get(INT64_LE, off);
        }
        return sum;
    }

    public static long skipNulls(MemorySegment input, MemorySegment validity, int n) {
        long sum = 0L;
        for (int i = 0; i < n; i++) {
            long byteIndex = i >>> 3;
            int bitMask = 1 << (i & 7);
            int bits = Byte.toUnsignedInt(validity.get(ValueLayout.JAVA_BYTE, byteIndex));
            if ((bits & bitMask) == 0) {
                continue;
            }
            long off = (long) i * Long.BYTES;
            sum += input.get(INT64_LE, off);
        }
        return sum;
    }
}
