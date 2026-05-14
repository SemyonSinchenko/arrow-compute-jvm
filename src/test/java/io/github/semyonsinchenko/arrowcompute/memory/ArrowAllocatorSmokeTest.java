package io.github.semyonsinchenko.arrowcompute.memory;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ArrowAllocatorSmokeTest {
    private static final String ALLOCATOR_NAME = "smoke-allocator";
    private static final int SAMPLE_SIZE = 4;

    @Test
    @DisplayName("allocator smoke validates and closes resources")
    void smoke_should_allocate_validate_and_close_resources() {
        assertAllocatorDebugModeEnabled();

        try (var root = new RootAllocator();
             var child = root.newChildAllocator(ALLOCATOR_NAME, 0, Long.MAX_VALUE);
             var vector = new IntVector("ints", child)) {

            vector.allocateNew(SAMPLE_SIZE);
            vector.set(0, 11);
            vector.set(1, 22);
            vector.set(2, 33);
            vector.set(3, 44);
            vector.setValueCount(SAMPLE_SIZE);

            vector.validate();
            vector.validateFull();
            assertEquals(SAMPLE_SIZE, vector.getValueCount());
            assertEquals(22, vector.get(1));
        }
    }

    private static void assertAllocatorDebugModeEnabled() {
        var debug = System.getProperty("arrow.memory.debug.allocator");
        assertTrue(
                "true".equals(debug),
                "Rule[allocator-debug]: expected -Darrow.memory.debug.allocator=true in test JVM"
        );
    }
}
