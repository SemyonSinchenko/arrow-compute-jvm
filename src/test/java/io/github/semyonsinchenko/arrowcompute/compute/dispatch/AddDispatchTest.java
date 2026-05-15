package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AddDispatchTest {
    @Test
    void eval_routesInt32Triples() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("add-dispatch-ok", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(3);
            right.allocateNew(3);
            out.allocateNew(3);
            left.set(0, 1);
            left.set(1, 2);
            left.set(2, 3);
            right.set(0, 10);
            right.set(1, 20);
            right.set(2, 30);
            left.setValueCount(3);
            right.setValueCount(3);

            AddDispatch.eval(left, right, out);

            assertEquals(3, out.getValueCount());
            assertEquals(11, out.get(0));
            assertEquals(22, out.get(1));
            assertEquals(33, out.get(2));
        }
    }

    @Test
    void eval_rejectsUnsupportedCombinations() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("add-dispatch-unsupported", 0, Long.MAX_VALUE);
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

            assertThrows(UnsupportedOperationException.class, () -> AddDispatch.eval(left, right, out));
        }
    }

    @Test
    void eval_routesInt64AndFloat64Triples() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("add-dispatch-more", 0, Long.MAX_VALUE);
             var left64 = new BigIntVector("left64", child);
             var right64 = new BigIntVector("right64", child);
             var out64 = new BigIntVector("out64", child);
             var leftF = new Float8Vector("leftF", child);
             var rightF = new Float8Vector("rightF", child);
             var outF = new Float8Vector("outF", child)) {
            left64.allocateNew(2);
            right64.allocateNew(2);
            out64.allocateNew(2);
            left64.set(0, 10L);
            left64.set(1, 20L);
            right64.set(0, 1L);
            right64.set(1, 2L);
            left64.setValueCount(2);
            right64.setValueCount(2);
            AddDispatch.eval(left64, right64, out64);
            assertEquals(11L, out64.get(0));
            assertEquals(22L, out64.get(1));

            leftF.allocateNew(2);
            rightF.allocateNew(2);
            outF.allocateNew(2);
            leftF.set(0, 1.5d);
            leftF.set(1, 2.25d);
            rightF.set(0, 0.5d);
            rightF.set(1, 0.75d);
            leftF.setValueCount(2);
            rightF.setValueCount(2);
            AddDispatch.eval(leftF, rightF, outF);
            assertEquals(2.0d, outF.get(0));
            assertEquals(3.0d, outF.get(1));
        }
    }
}
