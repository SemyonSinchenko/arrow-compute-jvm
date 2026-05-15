package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MulDispatchTest {
    @Test
    void eval_routesInt32AndFloat64Triples() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("mul-dispatch-ok", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child);
             var leftF = new Float8Vector("leftF", child);
             var rightF = new Float8Vector("rightF", child);
             var outF = new Float8Vector("outF", child)) {
            left.allocateNew(2);
            right.allocateNew(2);
            out.allocateNew(2);
            left.set(0, 3);
            left.set(1, -5);
            right.set(0, 4);
            right.set(1, 7);
            left.setValueCount(2);
            right.setValueCount(2);
            MulDispatch.eval(left, right, out);
            assertEquals(12, out.get(0));
            assertEquals(-35, out.get(1));

            leftF.allocateNew(2);
            rightF.allocateNew(2);
            outF.allocateNew(2);
            leftF.set(0, 1.5d);
            leftF.set(1, -2.0d);
            rightF.set(0, 4.0d);
            rightF.set(1, 8.5d);
            leftF.setValueCount(2);
            rightF.setValueCount(2);
            MulDispatch.eval(leftF, rightF, outF);
            assertEquals(6.0d, outF.get(0));
            assertEquals(-17.0d, outF.get(1));
        }
    }

    @Test
    void eval_rejectsUnsupportedCombinations() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("mul-dispatch-unsupported", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new VarCharVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(1);
            right.allocateNew();
            out.allocateNew(1);
            left.set(0, 1);
            right.setSafe(0, "x".getBytes());
            left.setValueCount(1);
            right.setValueCount(1);

            assertThrows(UnsupportedOperationException.class, () -> MulDispatch.eval(left, right, out));
        }
    }
}
