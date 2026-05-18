package io.github.semyonsinchenko.arrowcompute.compute;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComputeDivideTest {
    @Test
    void divide_executesInt32Flow() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compute-divide", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            left.allocateNew(4);
            right.allocateNew(4);
            out.allocateNew(4);

            left.set(0, 18);
            right.set(0, 3);
            left.set(1, -40);
            right.set(1, 8);
            left.set(2, 15);
            right.setNull(2);
            left.set(3, 81);
            right.set(3, 9);

            left.setValueCount(4);
            right.setValueCount(4);

            Compute.divide(left, right, out);

            assertEquals(4, out.getValueCount());
            assertEquals(6, out.get(0));
            assertEquals(-5, out.get(1));
            assertEquals(true, out.isNull(2));
            assertEquals(9, out.get(3));
        }
    }
}
