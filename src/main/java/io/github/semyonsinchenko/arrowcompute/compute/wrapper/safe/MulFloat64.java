package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Raw;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import io.github.semyonsinchenko.arrowcompute.memory.Validity;
import org.apache.arrow.vector.Float8Vector;

/**
 * MulFloat64 wrapper.
 *
 * <p>Inputs: Float8Vector, Float8Vector. Output: Float8Vector. Null policy: null-propagating,
 * data computed for all rows. IEEE behavior: NaN and Infinity follow Java/IEEE-754 semantics.
 * Output validity rule: left & right when nullable. Caller-owned lifetime: wrapper does not
 * retain buffers; caller keeps vectors live for the full call. Aliasing assumption: inputs and
 * output are non-overlapping. Input mutation: never.</p>
 */
public final class MulFloat64 {
    private static final long FLOAT64_BYTES = Double.BYTES;

    private MulFloat64() {
    }

    public static void eval(Float8Vector left, Float8Vector right, Float8Vector out) {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(left, right, out);

        if (left.getNullCount() != 0 || right.getNullCount() != 0) {
            Validity.propagateBinary(left, right, out, n);
        }

        if (n > 0) {
            long byteSize = (long) n * FLOAT64_BYTES;
            var leftData = SegmentViews.data(left, byteSize);
            var rightData = SegmentViews.data(right, byteSize);
            var outData = SegmentViews.data(out, byteSize);
            MulFloat64Raw.computeAll(leftData, rightData, outData, n);
        }
        out.setValueCount(n);
    }
}
