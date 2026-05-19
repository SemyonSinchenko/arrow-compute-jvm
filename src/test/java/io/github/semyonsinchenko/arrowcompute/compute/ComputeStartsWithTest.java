package io.github.semyonsinchenko.arrowcompute.compute;

import java.nio.charset.StandardCharsets;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ComputeStartsWithTest {
    @Test
    @DisplayName("compute facade executes startsWith flow")
    void startsWith_executesUtf8Flow() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compute-startswith", 0, Long.MAX_VALUE);
             var input = new VarCharVector("input", child);
             var out = new BitVector("out", child)) {
            input.allocateNew();
            out.allocateNew(4);

            input.setSafe(0, "prefix-1".getBytes(StandardCharsets.UTF_8));
            input.setSafe(1, "x-prefix".getBytes(StandardCharsets.UTF_8));
            input.setNull(2);
            input.setSafe(3, "prefix-3".getBytes(StandardCharsets.UTF_8));
            input.setValueCount(4);

            Compute.startsWith(input, "prefix".getBytes(StandardCharsets.UTF_8), out);

            assertEquals(4, out.getValueCount());
            assertEquals(1, out.get(0));
            assertEquals(0, out.get(1));
            assertEquals(true, out.isNull(2));
            assertEquals(1, out.get(3));
        }
    }
}
