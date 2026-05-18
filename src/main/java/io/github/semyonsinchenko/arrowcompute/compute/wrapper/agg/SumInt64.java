package io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg;

import io.github.semyonsinchenko.arrowcompute.compute.raw.SumInt64Raw;
import io.github.semyonsinchenko.arrowcompute.memory.BufferRefs;
import io.github.semyonsinchenko.arrowcompute.memory.Checks;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVectorHelper;

/**
 * SumInt64 wrapper.
 *
 * <p>Input: BigIntVector. Output: one-row BigIntVector scalar. Null policy: skip-nulls only,
 * fixed to skip_nulls=true and min_count=1. Overflow semantics: Java long wraparound.
 * Output validity rule: row 0 is null for all-null input, otherwise valid. Aliasing/lifetime
 * assumptions: input/output are non-overlapping and MemorySegment views remain inside retain scope.</p>
 */
public final class SumInt64 {
    private static final long INT64_BYTES = Long.BYTES;

    private SumInt64() {
    }

    public static void eval(BigIntVector input, BigIntVector out) {
        int n = input.getValueCount();
        Checks.outputCapacity(out, 1);
        Checks.zeroSliceOffset(input, out);

        try (var refs = BufferRefs.retain(input, out)) {
            if (n == 0 || input.getNullCount() == n) {
                out.setNull(0);
                out.setValueCount(1);
                return;
            }

            long dataBytes = (long) n * INT64_BYTES;
            var inputData = SegmentViews.data(input, dataBytes);

            long sum;
            if (input.getNullCount() == 0) {
                sum = SumInt64Raw.noNulls(inputData, n);
            } else {
                long validityBytes = BitVectorHelper.getValidityBufferSize(n);
                var inputValidity = SegmentViews.validity(input, validityBytes);
                sum = SumInt64Raw.skipNulls(inputData, inputValidity, n);
            }

            out.set(0, sum);
            out.setValueCount(1);
        }
    }
}
