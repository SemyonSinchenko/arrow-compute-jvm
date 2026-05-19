package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import java.nio.charset.StandardCharsets;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StartsWithDispatchTest {
    @Test
    @DisplayName("dispatch routes VarCharVector to BitVector")
    void eval_routesSupportedCombination() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("startswith-dispatch-ok", 0, Long.MAX_VALUE);
             var input = new VarCharVector("input", child);
             var out = new BitVector("out", child)) {
            input.allocateNew();
            out.allocateNew(3);

            input.setSafe(0, "alpha".getBytes(StandardCharsets.UTF_8));
            input.setSafe(1, "beta".getBytes(StandardCharsets.UTF_8));
            input.setSafe(2, "alphabet".getBytes(StandardCharsets.UTF_8));
            input.setValueCount(3);

            StartsWithDispatch.eval(input, "alpha".getBytes(StandardCharsets.UTF_8), out);

            assertEquals(1, out.get(0));
            assertEquals(0, out.get(1));
            assertEquals(1, out.get(2));
        }
    }

    @Test
    @DisplayName("dispatch rejects unsupported combination")
    void eval_rejectsUnsupportedCombination() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("startswith-dispatch-bad", 0, Long.MAX_VALUE);
             var input = new IntVector("input", child);
             var out = new BitVector("out", child)) {
            input.allocateNew(1);
            out.allocateNew(1);
            input.set(0, 1);
            input.setValueCount(1);

            assertThrows(UnsupportedOperationException.class,
                    () -> StartsWithDispatch.eval(input, "a".getBytes(StandardCharsets.UTF_8), out));
        }
    }
}
