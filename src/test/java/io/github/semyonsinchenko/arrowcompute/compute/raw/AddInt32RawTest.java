package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AddInt32RawTest {
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
    void computeAll_handlesZeroAndTinySizes() {
        var left0 = ints(1);
        var right0 = ints(1);
        var out0 = ints(1);
        out0.set(AddInt32Raw.INT32_LE, 0, 123456);

        AddInt32Raw.computeAll(left0, right0, out0, 0);
        assertEquals(123456, out0.get(AddInt32Raw.INT32_LE, 0));

        var left1 = ints(1);
        var right1 = ints(1);
        var out1 = ints(1);
        left1.set(AddInt32Raw.INT32_LE, 0, 3);
        right1.set(AddInt32Raw.INT32_LE, 0, -7);

        AddInt32Raw.computeAll(left1, right1, out1, 1);
        assertEquals(-4, out1.get(AddInt32Raw.INT32_LE, 0));
    }

    @Test
    void computeAll_handlesSpeciesAndTailBoundaries() {
        int species = AddInt32Raw.SPECIES.length();
        assertCase(Math.max(1, species - 1));
        assertCase(species);
        assertCase(species + 3);
    }

    @Test
    void computeAll_handlesSignedAndOverflowCases() {
        int[] leftValues = {
                Integer.MAX_VALUE,
                Integer.MIN_VALUE,
                -7,
                9,
                0,
                1_234_567_890
        };
        int[] rightValues = {
                1,
                -1,
                -13,
                -20,
                -1,
                1_234_567_890
        };

        int n = leftValues.length;
        var left = ints(n);
        var right = ints(n);
        var out = ints(n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(AddInt32Raw.INT32_LE, off, leftValues[i]);
            right.set(AddInt32Raw.INT32_LE, off, rightValues[i]);
        }

        AddInt32Raw.computeAll(left, right, out, n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            int expected = leftValues[i] + rightValues[i];
            assertEquals(expected, out.get(AddInt32Raw.INT32_LE, off), "mismatch at row=" + i);
        }

        assertEquals(Integer.MIN_VALUE, out.get(AddInt32Raw.INT32_LE, 0L));
        assertEquals(Integer.MAX_VALUE, out.get(AddInt32Raw.INT32_LE, (long) Integer.BYTES));
    }

    private void assertCase(int n) {
        var left = ints(n);
        var right = ints(n);
        var out = ints(n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(AddInt32Raw.INT32_LE, off, i * 11 - 37);
            right.set(AddInt32Raw.INT32_LE, off, 99 - i * 3);
        }

        AddInt32Raw.computeAll(left, right, out, n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            int expected = (i * 11 - 37) + (99 - i * 3);
            assertEquals(expected, out.get(AddInt32Raw.INT32_LE, off), "mismatch at row=" + i + " for n=" + n);
        }
    }

    private MemorySegment ints(int n) {
        return arena.allocate((long) n * Integer.BYTES);
    }
}
