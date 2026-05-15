package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import org.apache.arrow.memory.RootAllocator;
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
}
