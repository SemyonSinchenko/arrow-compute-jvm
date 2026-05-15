package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MulInt32Test {
    @Test
    void eval_computesAndPropagatesValidity() {
        assertProfile(2048, 0.0, 0.0);
        assertProfile(2048, 0.01, 0.01);
        assertProfile(2048, 0.30, 0.30);
        assertProfile(512, 1.0, 1.0);
    }

    private void assertProfile(int n, double leftNullRate, double rightNullRate) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("mul-int32-wrapper", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);

            var leftValid = new boolean[n];
            var rightValid = new boolean[n];
            var leftVals = new int[n];
            var rightVals = new int[n];

            for (int i = 0; i < n; i++) {
                boolean lValid = ((i % 100) / 100.0) >= leftNullRate;
                boolean rValid = ((i % 100) / 100.0) >= rightNullRate;
                int lv = i - 101;
                int rv = 79 - i;
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

            MulInt32.eval(left, right, out);
            assertEquals(n, out.getValueCount());

            for (int i = 0; i < n; i++) {
                boolean expectedValid = leftValid[i] && rightValid[i];
                assertEquals(expectedValid, !out.isNull(i), "validity row=" + i);
                if (expectedValid) {
                    assertEquals(leftVals[i] * rightVals[i], out.get(i), "value row=" + i);
                }
            }
        }
    }
}
