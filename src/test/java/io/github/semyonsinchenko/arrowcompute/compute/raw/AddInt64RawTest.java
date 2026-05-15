package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AddInt64RawTest {
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
    void computeAll_handlesZeroTinySpeciesAndTail() {
        assertCase(0);
        assertCase(1);
        int species = AddInt64Raw.SPECIES.length();
        assertCase(Math.max(1, species - 1));
        assertCase(species + 3);
    }

    @Test
    void computeAll_preservesWraparound() {
        var left = longs(2);
        var right = longs(2);
        var out = longs(2);
        left.set(AddInt64Raw.INT64_LE, 0L, Long.MAX_VALUE);
        right.set(AddInt64Raw.INT64_LE, 0L, 1L);
        left.set(AddInt64Raw.INT64_LE, Long.BYTES, Long.MIN_VALUE);
        right.set(AddInt64Raw.INT64_LE, Long.BYTES, -1L);

        AddInt64Raw.computeAll(left, right, out, 2);

        assertEquals(Long.MIN_VALUE, out.get(AddInt64Raw.INT64_LE, 0L));
        assertEquals(Long.MAX_VALUE, out.get(AddInt64Raw.INT64_LE, Long.BYTES));
    }

    private void assertCase(int n) {
        var left = longs(Math.max(1, n));
        var right = longs(Math.max(1, n));
        var out = longs(Math.max(1, n));
        for (int i = 0; i < n; i++) {
            long off = (long) i * Long.BYTES;
            long lv = i * 19L - 77L;
            long rv = 901L - i * 5L;
            left.set(AddInt64Raw.INT64_LE, off, lv);
            right.set(AddInt64Raw.INT64_LE, off, rv);
        }
        AddInt64Raw.computeAll(left, right, out, n);
        for (int i = 0; i < n; i++) {
            long off = (long) i * Long.BYTES;
            long expected = (i * 19L - 77L) + (901L - i * 5L);
            assertEquals(expected, out.get(AddInt64Raw.INT64_LE, off));
        }
    }

    private MemorySegment longs(int n) {
        return arena.allocate((long) n * Long.BYTES);
    }
}
