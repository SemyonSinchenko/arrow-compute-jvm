package io.github.semyonsinchenko.arrowcompute.memory;

import java.util.Objects;
import org.apache.arrow.vector.FieldVector;

/**
 * Central unchecked exception factory for wrapper boundary checks.
 */
public final class Errors {
    private Errors() {
    }

    public static IllegalArgumentException sizeMismatch(String op, int leftN, int rightN) {
        return new IllegalArgumentException(
                "%s valueCount mismatch: left=%d, right=%d".formatted(Objects.requireNonNull(op, "op"), leftN, rightN)
        );
    }

    public static IllegalArgumentException outputCapacity(String vectorName, int n, int capacity) {
        return new IllegalArgumentException(
                "output capacity too small for %s: required=%d, capacity=%d"
                        .formatted(Objects.requireNonNull(vectorName, "vectorName"), n, capacity)
        );
    }

    public static IllegalArgumentException sliceOffset(String vectorName, int offset) {
        return new IllegalArgumentException(
                "non-zero slice offset for %s: offset=%d".formatted(Objects.requireNonNull(vectorName, "vectorName"), offset)
        );
    }

    public static UnsupportedOperationException unsupported(String op, FieldVector left, FieldVector right, FieldVector out) {
        Objects.requireNonNull(op, "op");
        var leftType = typeName(left);
        var rightType = typeName(right);
        var outType = typeName(out);
        return new UnsupportedOperationException(
                "unsupported %s combination: left=%s, right=%s, out=%s".formatted(op, leftType, rightType, outType)
        );
    }

    public static ArithmeticException divByZero(int rowIndex) {
        return new ArithmeticException("division by zero at row=" + rowIndex);
    }

    public static ArithmeticException overflow(int rowIndex) {
        return new ArithmeticException("arithmetic overflow at row=" + rowIndex);
    }

    private static String typeName(FieldVector vector) {
        return vector == null ? "null" : vector.getMinorType().name();
    }
}
