package io.github.semyonsinchenko.arrowcompute.compute;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComputeSumTest {
    @Test
    void sum_executesInt64Flow() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compute-sum", 0, Long.MAX_VALUE);
             var input = new BigIntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(5);
            out.allocateNew(1);

            input.set(0, 10L);
            input.setNull(1);
            input.set(2, 7L);
            input.set(3, -2L);
            input.setNull(4);
            input.setValueCount(5);

            Compute.sum(input, out);

            assertEquals(1, out.getValueCount());
            assertEquals(false, out.isNull(0));
            assertEquals(15L, out.get(0));
        }
    }
}
