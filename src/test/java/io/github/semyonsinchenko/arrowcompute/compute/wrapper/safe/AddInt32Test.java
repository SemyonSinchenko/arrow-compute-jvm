package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AddInt32Test {
    @Test
    void eval_computesAndPropagatesValidityAcrossProfiles() {
        assertProfile(1024, 0.0, 0.0);
        assertProfile(1024, 0.1, 0.0);
        assertProfile(1024, 0.0, 0.1);
        assertProfile(2048, 0.01, 0.01);
        assertProfile(2048, 0.30, 0.30);
        assertProfile(512, 1.0, 1.0);
    }

    private void assertProfile(int n, double leftNullRate, double rightNullRate) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("add-int32-wrapper", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);

            var expectedLeft = new int[n];
            var expectedRight = new int[n];
            var leftValid = new boolean[n];
            var rightValid = new boolean[n];

            for (int i = 0; i < n; i++) {
                int lv = i * 31 - 101;
                int rv = 777 - i * 17;

                boolean lValid = ((i % 100) / 100.0) >= leftNullRate;
                boolean rValid = ((i % 100) / 100.0) >= rightNullRate;

                expectedLeft[i] = lv;
                expectedRight[i] = rv;
                leftValid[i] = lValid;
                rightValid[i] = rValid;

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

            AddInt32.eval(left, right, out);

            assertEquals(n, out.getValueCount());
            for (int i = 0; i < n; i++) {
                boolean outValid = !out.isNull(i);
                boolean expectedValid = leftValid[i] && rightValid[i];
                assertEquals(expectedValid, outValid, "validity mismatch at row=" + i);
                if (expectedValid) {
                    assertEquals(expectedLeft[i] + expectedRight[i], out.get(i), "value mismatch at row=" + i);
                }
                assertEquals(leftValid[i], !left.isNull(i), "left validity mutated at row=" + i);
                assertEquals(rightValid[i], !right.isNull(i), "right validity mutated at row=" + i);
                if (leftValid[i]) {
                    assertEquals(expectedLeft[i], left.get(i), "left value mutated at row=" + i);
                }
                if (rightValid[i]) {
                    assertEquals(expectedRight[i], right.get(i), "right value mutated at row=" + i);
                }
            }
        }
    }
}
