package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class AggregateDispatchTest {
    @Test
    void sum_routesBigIntVectors() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("aggregate-dispatch-ok", 0, Long.MAX_VALUE);
             var input = new BigIntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(3);
            out.allocateNew(1);
            input.set(0, 2L);
            input.set(1, 8L);
            input.set(2, -5L);
            input.setValueCount(3);

            AggregateDispatch.sum(input, out);

            assertEquals(1, out.getValueCount());
            assertEquals(5L, out.get(0));
        }
    }

    @Test
    void sum_rejectsUnsupportedTypeCombinations() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("aggregate-dispatch-unsupported", 0, Long.MAX_VALUE);
             var input = new IntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(1);
            out.allocateNew(1);
            input.set(0, 1);
            input.setValueCount(1);

            assertThrows(UnsupportedOperationException.class, () -> AggregateDispatch.sum(input, out));
        }
    }
}
