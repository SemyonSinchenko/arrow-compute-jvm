package io.github.semyonsinchenko.arrowcompute.compute.wrapper.validonly;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DivInt32Test {
    @Test
    void eval_inactiveNullDivisorDoesNotThrow() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("div-int32-inactive", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(4);
            right.allocateNew(4);
            out.allocateNew(4);

            left.set(0, 10);
            right.set(0, 2);

            left.set(1, 20);
            right.setNull(1);

            left.set(2, 8);
            right.set(2, 4);

            left.set(3, 11);
            right.setNull(3);

            left.setValueCount(4);
            right.setValueCount(4);

            DivInt32.eval(left, right, out);

            assertEquals(4, out.getValueCount());
            assertEquals(5, out.get(0));
            assertEquals(false, out.isNull(0));
            assertEquals(true, out.isNull(1));
            assertEquals(2, out.get(2));
            assertEquals(true, out.isNull(3));
        }
    }

    @Test
    void eval_activeDivisorZeroThrowsWithRowIndex() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("div-int32-zero", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(3);
            right.allocateNew(3);
            out.allocateNew(3);

            left.set(0, 7);
            right.set(0, 1);
            left.set(1, 9);
            right.set(1, 0);
            left.set(2, 6);
            right.set(2, 2);
            left.setValueCount(3);
            right.setValueCount(3);
            out.setValueCount(1);

            var ex = assertThrows(ArithmeticException.class, () -> DivInt32.eval(left, right, out));
            assertEquals("division by zero at row=1", ex.getMessage());
            assertEquals(1, out.getValueCount());
        }
    }

    @Test
    void eval_activeMinValueOverMinusOneThrowsWithRowIndex() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("div-int32-overflow", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(3);
            right.allocateNew(3);
            out.allocateNew(3);

            left.set(0, 3);
            right.set(0, 1);
            left.set(1, Integer.MIN_VALUE);
            right.set(1, -1);
            left.set(2, 12);
            right.set(2, 3);
            left.setValueCount(3);
            right.setValueCount(3);
            out.setValueCount(2);

            var ex = assertThrows(ArithmeticException.class, () -> DivInt32.eval(left, right, out));
            assertEquals("arithmetic overflow at row=1", ex.getMessage());
            assertEquals(2, out.getValueCount());
        }
    }

    @Test
    void eval_computesAllValidAndNullableProfiles() {
        assertProfile(64, 0, 0);
        assertProfile(64, 10, 30);
        assertProfile(64, 30, 10);
    }

    private void assertProfile(int n, int leftNullPercent, int rightNullPercent) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("div-int32-profile", 0, Long.MAX_VALUE);
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
                boolean lValid = (i % 100) >= leftNullPercent;
                boolean rValid = ((i + 19) % 100) >= rightNullPercent;
                int lv = i * 31 - 999;
                int rv = (i % 17) + 1;
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

            DivInt32.eval(left, right, out);
            assertEquals(n, out.getValueCount());

            for (int i = 0; i < n; i++) {
                boolean expectedValid = leftValid[i] && rightValid[i];
                assertEquals(expectedValid, !out.isNull(i), "validity row=" + i);
                if (expectedValid) {
                    assertEquals(leftVals[i] / rightVals[i], out.get(i), "value row=" + i);
                }
            }
        }
    }
}
