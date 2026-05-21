package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import io.github.semyonsinchenko.arrowcompute.compute.raw.AddInt64Raw;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import io.github.semyonsinchenko.arrowcompute.memory.Validity;
import org.apache.arrow.vector.BigIntVector;

/**
 * AddInt64 wrapper.
 *
 * <p>Inputs: BigIntVector, BigIntVector. Output: BigIntVector. Null policy: null-propagating,
 * data computed for all rows. Overflow semantics: Java long wraparound. Output validity rule: left
 * & right when nullable. Caller-owned lifetime: wrapper does not retain buffers; caller keeps
 * vectors live for the full call. Aliasing assumption: inputs and output are non-overlapping.
 * Input mutation: never.</p>
 */
public final class AddInt64 {
    private static final long INT64_BYTES = Long.BYTES;

    private AddInt64() {
    }

    public static void eval(BigIntVector left, BigIntVector right, BigIntVector out) {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(left, right, out);

        if (left.getNullCount() != 0 || right.getNullCount() != 0) {
            Validity.propagateBinary(left, right, out, n);
        }

        if (n > 0) {
            long byteSize = (long) n * INT64_BYTES;
            var leftData = SegmentViews.data(left, byteSize);
            var rightData = SegmentViews.data(right, byteSize);
            var outData = SegmentViews.data(out, byteSize);
            AddInt64Raw.computeAll(leftData, rightData, outData, n);
        }
        out.setValueCount(n);
    }
}
