package io.github.semyonsinchenko.arrowcompute.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Word-wise Arrow validity bitmap operations (LSB-first, 1=valid).
 */
public final class Bitmap {
    private static final ValueLayout.OfLong LONG_LE = ValueLayout.JAVA_LONG_UNALIGNED;

    private Bitmap() {
    }

    public static void and(MemorySegment leftBitmap, MemorySegment rightBitmap, MemorySegment outBitmap, int n) {
        applyBinary(leftBitmap, rightBitmap, outBitmap, n, BinaryOp.AND);
    }

    public static void or(MemorySegment leftBitmap, MemorySegment rightBitmap, MemorySegment outBitmap, int n) {
        applyBinary(leftBitmap, rightBitmap, outBitmap, n, BinaryOp.OR);
    }

    public static void andNot(MemorySegment leftBitmap, MemorySegment rightBitmap, MemorySegment outBitmap, int n) {
        applyBinary(leftBitmap, rightBitmap, outBitmap, n, BinaryOp.AND_NOT);
    }

    public static void not(MemorySegment inputBitmap, MemorySegment outBitmap, int n) {
        Objects.requireNonNull(inputBitmap, "inputBitmap must not be null");
        Objects.requireNonNull(outBitmap, "outBitmap must not be null");
        validateRowCount(n);

        int fullWords = n >>> 6;
        int tailBits = n & 63;

        for (int i = 0; i < fullWords; i++) {
            long off = (long) i * Long.BYTES;
            outBitmap.set(LONG_LE, off, ~inputBitmap.get(LONG_LE, off));
        }
        applyUnaryTail(inputBitmap, outBitmap, fullWords, tailBits);
    }

    public static int countSetBits(MemorySegment bitmap, int n) {
        Objects.requireNonNull(bitmap, "bitmap must not be null");
        validateRowCount(n);

        int fullWords = n >>> 6;
        int tailBits = n & 63;
        int count = 0;

        for (int i = 0; i < fullWords; i++) {
            long off = (long) i * Long.BYTES;
            count += Long.bitCount(bitmap.get(LONG_LE, off));
        }
        count += countTailBits(bitmap, fullWords, tailBits);
        return count;
    }

    private static void applyBinary(
            MemorySegment left,
            MemorySegment right,
            MemorySegment out,
            int n,
            BinaryOp op
    ) {
        Objects.requireNonNull(left, "leftBitmap must not be null");
        Objects.requireNonNull(right, "rightBitmap must not be null");
        Objects.requireNonNull(out, "outBitmap must not be null");
        validateRowCount(n);

        int fullWords = n >>> 6;
        int tailBits = n & 63;

        for (int i = 0; i < fullWords; i++) {
            long off = (long) i * Long.BYTES;
            long l = left.get(LONG_LE, off);
            long r = right.get(LONG_LE, off);
            out.set(LONG_LE, off, op.apply(l, r));
        }

        applyBinaryTail(left, right, out, fullWords, tailBits, op);
    }

    private static void validateRowCount(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("row count must be >= 0");
        }
    }

    private static void applyUnaryTail(MemorySegment input, MemorySegment out, int fullWords, int tailBits) {
        if (tailBits == 0) {
            return;
        }
        int bytes = (tailBits + 7) >>> 3;
        int bitRemainder = tailBits & 7;
        long base = (long) fullWords * Long.BYTES;
        for (int i = 0; i < bytes; i++) {
            int in = Byte.toUnsignedInt(input.get(ValueLayout.JAVA_BYTE, base + i));
            int v = ~in;
            if (i == bytes - 1 && bitRemainder != 0) {
                int mask = (1 << bitRemainder) - 1;
                v &= mask;
            }
            out.set(ValueLayout.JAVA_BYTE, base + i, (byte) v);
        }
    }

    private static int countTailBits(MemorySegment bitmap, int fullWords, int tailBits) {
        if (tailBits == 0) {
            return 0;
        }
        int bytes = (tailBits + 7) >>> 3;
        int bitRemainder = tailBits & 7;
        long base = (long) fullWords * Long.BYTES;
        int count = 0;
        for (int i = 0; i < bytes; i++) {
            int b = Byte.toUnsignedInt(bitmap.get(ValueLayout.JAVA_BYTE, base + i));
            if (i == bytes - 1 && bitRemainder != 0) {
                b &= (1 << bitRemainder) - 1;
            }
            count += Integer.bitCount(b);
        }
        return count;
    }

    private static void applyBinaryTail(
            MemorySegment left,
            MemorySegment right,
            MemorySegment out,
            int fullWords,
            int tailBits,
            BinaryOp op
    ) {
        if (tailBits == 0) {
            return;
        }
        int bytes = (tailBits + 7) >>> 3;
        int bitRemainder = tailBits & 7;
        long base = (long) fullWords * Long.BYTES;
        for (int i = 0; i < bytes; i++) {
            int l = Byte.toUnsignedInt(left.get(ValueLayout.JAVA_BYTE, base + i));
            int r = Byte.toUnsignedInt(right.get(ValueLayout.JAVA_BYTE, base + i));
            int v = (int) op.apply(l, r);
            if (i == bytes - 1 && bitRemainder != 0) {
                v &= (1 << bitRemainder) - 1;
            }
            out.set(ValueLayout.JAVA_BYTE, base + i, (byte) v);
        }
    }

    private enum BinaryOp {
        AND {
            @Override
            long apply(long left, long right) {
                return left & right;
            }
        },
        OR {
            @Override
            long apply(long left, long right) {
                return left | right;
            }
        },
        AND_NOT {
            @Override
            long apply(long left, long right) {
                return left & ~right;
            }
        };

        abstract long apply(long left, long right);
    }
}
