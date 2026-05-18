package io.github.semyonsinchenko.arrowcompute.compute.wrapper.validonly;

import io.github.semyonsinchenko.arrowcompute.compute.raw.DivInt32Raw;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import io.github.semyonsinchenko.arrowcompute.memory.Validity;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.IntVector;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DivInt32 wrapper.
 *
 * <p>Inputs: IntVector, IntVector. Output: IntVector. Null policy: valid-only;
 * nullable output validity is left & right. Checked behavior: precheck-before-loop for
 * active rows only, with deterministic first-offender row errors. Output validity rule:
 * all-valid for no-null inputs, otherwise AND propagation. Aliasing/lifetime assumptions:
 * wrapper validates shape and keeps MemorySegment views inside retain scope.</p>
 */
public final class DivInt32 {
    private static final long INT32_BYTES = Integer.BYTES;

    private DivInt32() {
    }

    public static void eval(IntVector left, IntVector right, IntVector out) {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(left, right, out);

        try (var refs = BufferRefs.retain(left, right, out)) {
            if (left.getNullCount() == 0 && right.getNullCount() == 0) {
                Validity.markAllValid(out, n);
                if (n > 0) {
                    long dataBytes = (long) n * INT32_BYTES;
                    var leftData = SegmentViews.data(left, dataBytes);
                    var rightData = SegmentViews.data(right, dataBytes);
                    precheckAllRows(leftData, rightData, n);
                    var outData = SegmentViews.data(out, dataBytes);
                    DivInt32Raw.noNulls(leftData, rightData, outData, n);
                }
            } else {
                Validity.propagateBinary(left, right, out, n);
                if (n > 0) {
                    long dataBytes = (long) n * INT32_BYTES;
                    int validityBytes = (int) BitVectorHelper.getValidityBufferSize(n);
                    var leftData = SegmentViews.data(left, dataBytes);
                    var rightData = SegmentViews.data(right, dataBytes);
                    var activeValidity = SegmentViews.validity(out, validityBytes);
                    precheckActiveRows(leftData, rightData, activeValidity, n);
                    var outData = SegmentViews.data(out, dataBytes);
                    DivInt32Raw.validOnly(leftData, rightData, outData, activeValidity, n);
                }
            }

            out.setValueCount(n);
        }
    }

    public static void precheckActiveRows(MemorySegment leftData, MemorySegment rightData, MemorySegment activeValidity, int n) {
        for (int i = 0; i < n; i++) {
            if (!isActive(activeValidity, i)) {
                continue;
            }
            long off = (long) i * INT32_BYTES;
            int divisor = rightData.get(DivInt32Raw.INT32_LE, off);
            if (divisor == 0) {
                throw Errors.divByZero(i);
            }
            int dividend = leftData.get(DivInt32Raw.INT32_LE, off);
            if (dividend == Integer.MIN_VALUE && divisor == -1) {
                throw Errors.overflow(i);
            }
        }
    }

    private static void precheckAllRows(MemorySegment leftData, MemorySegment rightData, int n) {
        for (int i = 0; i < n; i++) {
            long off = (long) i * INT32_BYTES;
            int divisor = rightData.get(DivInt32Raw.INT32_LE, off);
            if (divisor == 0) {
                throw Errors.divByZero(i);
            }
            int dividend = leftData.get(DivInt32Raw.INT32_LE, off);
            if (dividend == Integer.MIN_VALUE && divisor == -1) {
                throw Errors.overflow(i);
            }
        }
    }

    private static boolean isActive(MemorySegment validity, int row) {
        long byteIndex = row >>> 3;
        int bitMask = 1 << (row & 7);
        int bits = Byte.toUnsignedInt(validity.get(ValueLayout.JAVA_BYTE, byteIndex));
        return (bits & bitMask) != 0;
    }
}
