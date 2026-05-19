package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Objects;
import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * StartsWithUtf8 raw kernel.
 *
 * <p>Inputs: UTF-8 offset buffer, UTF-8 data buffer, scalar byte[] needle. Output: packed boolean
 * bits in Arrow bit order (LSB-first). Null policy: none (wrapper owns validity propagation).
 * Domain behavior: throws only for top-level argument and offset-shape violations. Aliasing
 * assumption: caller provides non-overlapping segments.</p>
 */
public final class StartsWithUtf8Raw {
    public static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    public static final ValueLayout.OfInt INT32_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    public static final ByteOrder BYTE_ORDER = ByteOrder.LITTLE_ENDIAN;

    private StartsWithUtf8Raw() {
    }

    public static void computeAll(MemorySegment offsets, MemorySegment data, byte[] needle, MemorySegment outBits, int n) {
        Objects.requireNonNull(offsets, "offsets must not be null");
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(needle, "needle must not be null");
        Objects.requireNonNull(outBits, "outBits must not be null");
        if (n < 0) {
            throw new IllegalArgumentException("row count must be >= 0");
        }
        if (n == 0) {
            return;
        }

        long requiredOffsetsBytes = (long) (n + 1) * Integer.BYTES;
        int outBytes = (n + 7) >>> 3;
        if (offsets.byteSize() < requiredOffsetsBytes) {
            throw new IllegalArgumentException("offsets segment too small");
        }
        if (outBits.byteSize() < outBytes) {
            throw new IllegalArgumentException("outBits segment too small");
        }

        outBits.asSlice(0, outBytes).fill((byte) 0);
        validateOffsets(offsets, data.byteSize(), n);

        if (needle.length == 0) {
            outBits.asSlice(0, outBytes).fill((byte) 0xFF);
            maskTail(outBits, n);
            return;
        }

        int needleLen = needle.length;
        int needleVectorUpper = SPECIES.loopBound(needleLen);

        for (int row = 0; row < n; row++) {
            long start = Integer.toUnsignedLong(offsets.get(INT32_LE, (long) row * Integer.BYTES));
            long end = Integer.toUnsignedLong(offsets.get(INT32_LE, (long) (row + 1) * Integer.BYTES));
            long len = end - start;
            if (len < needleLen) {
                continue;
            }

            boolean matched = true;
            int j = 0;
            for (; j < needleVectorUpper; j += SPECIES.length()) {
                var left = ByteVector.fromMemorySegment(SPECIES, data, start + j, BYTE_ORDER);
                var right = ByteVector.fromArray(SPECIES, needle, j);
                if (!left.eq(right).allTrue()) {
                    matched = false;
                    break;
                }
            }
            for (; matched && j < needleLen; j++) {
                if (data.get(BYTE, start + j) != needle[j]) {
                    matched = false;
                }
            }

            if (matched) {
                long outByteIndex = row >>> 3;
                int mask = 1 << (row & 7);
                int cur = Byte.toUnsignedInt(outBits.get(BYTE, outByteIndex));
                outBits.set(BYTE, outByteIndex, (byte) (cur | mask));
            }
        }

        maskTail(outBits, n);
    }

    private static void validateOffsets(MemorySegment offsets, long dataBytes, int n) {
        long prev = Integer.toUnsignedLong(offsets.get(INT32_LE, 0));
        if (prev > dataBytes) {
            throw new IllegalArgumentException("malformed offsets");
        }
        for (int i = 1; i <= n; i++) {
            long cur = Integer.toUnsignedLong(offsets.get(INT32_LE, (long) i * Integer.BYTES));
            if (cur < prev || cur > dataBytes) {
                throw new IllegalArgumentException("malformed offsets");
            }
            prev = cur;
        }
    }

    private static void maskTail(MemorySegment outBits, int n) {
        if ((n & 7) == 0) {
            return;
        }
        long lastByteIndex = (n - 1L) >>> 3;
        int keep = (1 << (n & 7)) - 1;
        int cur = Byte.toUnsignedInt(outBits.get(BYTE, lastByteIndex));
        outBits.set(BYTE, lastByteIndex, (byte) (cur & keep));
    }
}
