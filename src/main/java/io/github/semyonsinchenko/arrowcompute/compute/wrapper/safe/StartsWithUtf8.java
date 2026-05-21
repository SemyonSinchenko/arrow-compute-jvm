package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import io.github.semyonsinchenko.arrowcompute.compute.raw.StartsWithUtf8Raw;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import io.github.semyonsinchenko.arrowcompute.memory.Validity;
import java.util.Objects;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.VarCharVector;

/**
 * StartsWithUtf8 wrapper.
 *
 * <p>Input: VarCharVector, scalar byte[] needle. Output: BitVector with packed boolean values.
 * Null policy: null-propagating, output values may be computed for all rows and output validity is
 * copied from input validity (or left unspecified when null_count == 0). Domain behavior: no
 * row-level exceptions. Output validity rule: unary propagation from input for nullable input.
 * Caller-owned lifetime: wrapper does not retain buffers; caller keeps vectors live for the full
 * call. Aliasing assumption: input and output do not overlap. Input mutation: never.</p>
 */
public final class StartsWithUtf8 {
    private StartsWithUtf8() {
    }

    public static void eval(VarCharVector input, byte[] needle, BitVector out) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(needle, "needle must not be null");
        Objects.requireNonNull(out, "out must not be null");

        int n = input.getValueCount();
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(input, out);

        if (input.getNullCount() != 0) {
            Validity.propagateUnary(input, out, n);
        }

        if (n > 0) {
            long offsetsBytes = (long) (n + 1) * Integer.BYTES;
            long outBitsBytes = BitVectorHelper.getValidityBufferSize(n);
            long dataBytes = input.getDataBuffer().capacity();

            var offsetsSeg = SegmentViews.fromArrowBuf(input.getOffsetBuffer(), offsetsBytes);
            var dataSeg = SegmentViews.fromArrowBuf(input.getDataBuffer(), dataBytes);
            var outBitsSeg = SegmentViews.fromArrowBuf(out.getDataBuffer(), outBitsBytes);
            StartsWithUtf8Raw.computeAll(offsetsSeg, dataSeg, needle, outBitsSeg, n);
        }

        out.setValueCount(n);
    }
}
