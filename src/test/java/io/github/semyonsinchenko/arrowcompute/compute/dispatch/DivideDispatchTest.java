package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DivideDispatchTest {
    @Test
    void eval_routesInt32Triples() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("divide-dispatch-ok", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(2);
            right.allocateNew(2);
            out.allocateNew(2);
            left.set(0, 20);
            left.set(1, -21);
            right.set(0, 4);
            right.set(1, -3);
            left.setValueCount(2);
            right.setValueCount(2);

            DivideDispatch.eval(left, right, out);

            assertEquals(5, out.get(0));
            assertEquals(7, out.get(1));
        }
    }

    @Test
    void eval_rejectsUnsupportedCombinations() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("divide-dispatch-unsupported", 0, Long.MAX_VALUE);
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

            assertThrows(UnsupportedOperationException.class, () -> DivideDispatch.eval(left, right, out));
        }
    }
}
