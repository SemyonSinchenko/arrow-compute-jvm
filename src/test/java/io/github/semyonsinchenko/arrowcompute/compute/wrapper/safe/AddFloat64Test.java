package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AddFloat64Test {
    @Test
    void eval_propagatesValidityAndIsBitExactOnValidRows() {
        assertProfile(2048, 0.0, 0.0);
        assertProfile(2048, 0.01, 0.01);
        assertProfile(2048, 0.30, 0.30);
        assertProfile(512, 1.0, 1.0);
    }

    private void assertProfile(int n, double leftNullRate, double rightNullRate) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("add-float64-wrapper", 0, Long.MAX_VALUE);
             var left = new Float8Vector("left", child);
             var right = new Float8Vector("right", child);
             var out = new Float8Vector("out", child)) {
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);

            var leftValid = new boolean[n];
            var rightValid = new boolean[n];
            var leftVals = new double[n];
            var rightVals = new double[n];

            for (int i = 0; i < n; i++) {
                boolean lValid = ((i % 100) / 100.0) >= leftNullRate;
                boolean rValid = ((i % 100) / 100.0) >= rightNullRate;
                double lv = i % 9 == 0 ? Double.NaN : i * 0.5d - 31.0d;
                double rv = i % 11 == 0 ? Double.POSITIVE_INFINITY : 100.0d - i * 0.25d;
                leftValid[i] = lValid;
                rightValid[i] = rValid;
                leftVals[i] = lv;
                rightVals[i] = rv;

                if (lValid) {
                    left.set(i, lv);
                } else {
                    left.setNull(i);
                }
                if (rValid) {
                    right.set(i, rv);
                } else {
                    right.setNull(i);
                }
            }
            left.setValueCount(n);
            right.setValueCount(n);

            AddFloat64.eval(left, right, out);
            assertEquals(n, out.getValueCount());

            for (int i = 0; i < n; i++) {
                boolean expectedValid = leftValid[i] && rightValid[i];
                assertEquals(expectedValid, !out.isNull(i), "validity row=" + i);
                if (expectedValid) {
                    long expectedBits = Double.doubleToRawLongBits(leftVals[i] + rightVals[i]);
                    long actualBits = Double.doubleToRawLongBits(out.get(i));
                    assertEquals(expectedBits, actualBits, "value row=" + i);
                }
            }
        }
    }
}
