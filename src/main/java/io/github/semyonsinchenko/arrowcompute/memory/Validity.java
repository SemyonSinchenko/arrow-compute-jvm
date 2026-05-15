package io.github.semyonsinchenko.arrowcompute.memory;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.FieldVector;

/**
 * Wrapper-level validity propagation helpers.
 */
public final class Validity {
    private Validity() {
    }

    public static void markAllValid(FieldVector out, int n) {
        Objects.requireNonNull(out, "out vector must not be null");
        validateRowCount(n);

        var validityBuffer = out.getValidityBuffer();
        int validityBytes = (int) BitVectorHelper.getValidityBufferSize(n);
        for (int i = 0; i < validityBytes; i++) {
            validityBuffer.setByte(i, (byte) 0xFF);
        }

        if ((n & 7) != 0 && validityBytes > 0) {
            int lastByteIndex = validityBytes - 1;
            int tailBits = n & 7;
            int tailMask = (1 << tailBits) - 1;
            validityBuffer.setByte(lastByteIndex, (byte) tailMask);
        }
    }

    public static void propagateUnary(FieldVector input, FieldVector out, int n) {
        Objects.requireNonNull(input, "input vector must not be null");
        Objects.requireNonNull(out, "out vector must not be null");
        validateRowCount(n);

        int validityBytes = (int) BitVectorHelper.getValidityBufferSize(n);
        if (validityBytes == 0) {
            return;
        }

        var inSeg = SegmentViews.validity(input, validityBytes);
        var outSeg = SegmentViews.validity(out, validityBytes);
        MemorySegment.copy(inSeg, 0, outSeg, 0, validityBytes);
    }

    public static void propagateBinary(FieldVector left, FieldVector right, FieldVector out, int n) {
        Objects.requireNonNull(left, "left vector must not be null");
        Objects.requireNonNull(right, "right vector must not be null");
        Objects.requireNonNull(out, "out vector must not be null");
        validateRowCount(n);

        int validityBytes = (int) BitVectorHelper.getValidityBufferSize(n);
        if (validityBytes == 0) {
            return;
        }

        var leftSeg = SegmentViews.validity(left, validityBytes);
        var rightSeg = SegmentViews.validity(right, validityBytes);
        var outSeg = SegmentViews.validity(out, validityBytes);
        Bitmap.and(leftSeg, rightSeg, outSeg, n);
    }

    private static void validateRowCount(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("row count must be >= 0");
        }
    }
}
