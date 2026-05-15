package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MulInt32RawTest {
    private Arena arena;

    @BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    void computeAll_handlesBoundariesAndWraparound() {
        int species = MulInt32Raw.SPECIES.length();
        assertCase(1);
        assertCase(species);
        assertCase(species + 1);

        var left = ints(1);
        var right = ints(1);
        var out = ints(1);
        left.set(MulInt32Raw.INT32_LE, 0L, Integer.MIN_VALUE);
        right.set(MulInt32Raw.INT32_LE, 0L, -1);
        MulInt32Raw.computeAll(left, right, out, 1);
        assertEquals(Integer.MIN_VALUE, out.get(MulInt32Raw.INT32_LE, 0L));
    }

    private void assertCase(int n) {
        var left = ints(n);
        var right = ints(n);
        var out = ints(n);
        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(MulInt32Raw.INT32_LE, off, i - 31);
            right.set(MulInt32Raw.INT32_LE, off, 17 - i);
        }
        MulInt32Raw.computeAll(left, right, out, n);
        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            assertEquals((i - 31) * (17 - i), out.get(MulInt32Raw.INT32_LE, off), "row=" + i);
        }
    }

    private MemorySegment ints(int n) {
        return arena.allocate((long) n * Integer.BYTES);
    }
}
