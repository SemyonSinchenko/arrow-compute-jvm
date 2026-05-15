package io.github.semyonsinchenko.arrowcompute.memory;

import java.util.Objects;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.FieldVector;

/**
 * Wrapper preconditions validated before retain and memory-segment creation.
 */
public final class Checks {
    private Checks() {
    }

    public static int sameValueCount(FieldVector left, FieldVector right) {
        Objects.requireNonNull(left, "left vector must not be null");
        Objects.requireNonNull(right, "right vector must not be null");
        int leftN = left.getValueCount();
        int rightN = right.getValueCount();
        if (leftN != rightN) {
            throw Errors.sizeMismatch("binary op", leftN, rightN);
        }
        return leftN;
    }

    public static void outputCapacity(FieldVector out, int n) {
        Objects.requireNonNull(out, "out vector must not be null");
        if (n < 0) {
            throw new IllegalArgumentException("row count must be >= 0");
        }
        int capacity = out.getValueCapacity();
        if (capacity < n) {
            throw Errors.outputCapacity(out.getName(), n, capacity);
        }
    }

    public static void zeroSliceOffset(FieldVector... vectors) {
        Objects.requireNonNull(vectors, "vectors must not be null");
        for (var vector : vectors) {
            Objects.requireNonNull(vector, "vector must not be null");
            if (vector instanceof BaseVariableWidthVector variable) {
                int offset = variable.getOffsetBuffer().getInt(0);
                if (offset != 0) {
                    throw Errors.sliceOffset(vector.getName(), offset);
                }
            }
        }
    }

    public static void matchingDecimalPrecisionScale(FieldVector left, FieldVector right) {
        Objects.requireNonNull(left, "left vector must not be null");
        Objects.requireNonNull(right, "right vector must not be null");
    }
}
