package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import io.github.semyonsinchenko.arrowcompute.compute.raw.CompareInt32GreaterRaw;
import io.github.semyonsinchenko.arrowcompute.exception.BitmapTailViolationException;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import io.github.semyonsinchenko.arrowcompute.memory.Validity;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.IntVector;

/**
 * CompareInt32Greater wrapper.
 *
 * <p>Operation: left > right. Inputs: IntVector, IntVector. Output: BitVector (value bitmap +
 * validity bitmap). Null policy: null-propagating, validity is left & right. Error behavior:
 * IllegalArgumentException for shape/capacity/slice violations; BitmapTailViolationException for
 * explicit tail-integrity failures. Tail policy: validate pre-finalization and finalize with
 * setValueCount(n). Caller-owned lifetime: wrapper does not retain buffers; caller keeps vectors
 * live for the full call. Aliasing assumption: inputs and output do not overlap.</p>
 */
public final class CompareInt32Greater {
    private CompareInt32Greater() {
    }

    public static void eval(IntVector left, IntVector right, BitVector out) {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(left, right, out);

        if (left.getNullCount() != 0 || right.getNullCount() != 0) {
            Validity.propagateBinary(left, right, out, n);
        }

        if (n > 0) {
            long dataBytes = (long) n * Integer.BYTES;
            int bitmapBytes = (int) BitVectorHelper.getValidityBufferSize(n);
            var leftData = SegmentViews.data(left, dataBytes);
            var rightData = SegmentViews.data(right, dataBytes);
            var outValues = SegmentViews.data(out, bitmapBytes);
            CompareInt32GreaterRaw.computeAll(leftData, rightData, outValues, n);
            validateTailClear(outValues, n);
        }

        out.setValueCount(n);
    }

    private static void validateTailClear(java.lang.foreign.MemorySegment outValues, int n) {
        int bitRemainder = n & 7;
        if (bitRemainder == 0) {
            return;
        }
        int bitmapBytes = (int) BitVectorHelper.getValidityBufferSize(n);
        long last = bitmapBytes - 1L;
        int lastByte = Byte.toUnsignedInt(outValues.get(java.lang.foreign.ValueLayout.JAVA_BYTE, last));
        int outOfRangeMask = ~((1 << bitRemainder) - 1) & 0xFF;
        if ((lastByte & outOfRangeMask) != 0) {
            throw new BitmapTailViolationException(
                    "BITMAP_TAIL_VIOLATION",
                    "bitmap tail contains out-of-range set bits for rowCount=" + n
            );
        }
    }
}
