package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class DivInt32RawTest {
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
    void noNulls_handlesNormalNegativeAndBoundaryValues() {
        int[] leftValues = {12, -9, Integer.MIN_VALUE, 0, 11};
        int[] rightValues = {3, -3, 2, 5, -2};
        int n = leftValues.length;

        var left = ints(n);
        var right = ints(n);
        var out = ints(n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(DivInt32Raw.INT32_LE, off, leftValues[i]);
            right.set(DivInt32Raw.INT32_LE, off, rightValues[i]);
        }

        DivInt32Raw.noNulls(left, right, out, n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            assertEquals(leftValues[i] / rightValues[i], out.get(DivInt32Raw.INT32_LE, off), "row=" + i);
        }
    }

    @Test
    void noNulls_handlesSpeciesTailBoundaries() {
        assertCase(1);
        assertCase(15);
        assertCase(16);
        assertCase(19);
    }

    @Test
    void validOnly_writesOnlyActiveRows() {
        int n = 6;
        var left = ints(n);
        var right = ints(n);
        var out = ints(n);
        var validity = bytes(1);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(DivInt32Raw.INT32_LE, off, 100 + i);
            right.set(DivInt32Raw.INT32_LE, off, 2 + i);
            out.set(DivInt32Raw.INT32_LE, off, -7777);
        }

        // active rows: 0, 2, 5
        validity.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, (byte) 0b0010_0101);

        DivInt32Raw.validOnly(left, right, out, validity, n);

        assertEquals(50, out.get(DivInt32Raw.INT32_LE, 0L));
        assertEquals(-7777, out.get(DivInt32Raw.INT32_LE, (long) Integer.BYTES));
        assertEquals(25, out.get(DivInt32Raw.INT32_LE, (long) 2 * Integer.BYTES));
        assertEquals(-7777, out.get(DivInt32Raw.INT32_LE, (long) 3 * Integer.BYTES));
        assertEquals(-7777, out.get(DivInt32Raw.INT32_LE, (long) 4 * Integer.BYTES));
        assertEquals(15, out.get(DivInt32Raw.INT32_LE, (long) 5 * Integer.BYTES));
    }

    private void assertCase(int n) {
        var left = ints(n);
        var right = ints(n);
        var out = ints(n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            left.set(DivInt32Raw.INT32_LE, off, i * 101 - 2000);
            right.set(DivInt32Raw.INT32_LE, off, (i % 7) + 1);
        }

        DivInt32Raw.noNulls(left, right, out, n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Integer.BYTES;
            assertEquals((i * 101 - 2000) / ((i % 7) + 1), out.get(DivInt32Raw.INT32_LE, off), "n=" + n + " row=" + i);
        }
    }

    private MemorySegment ints(int n) {
        return arena.allocate((long) n * Integer.BYTES);
    }

    private MemorySegment bytes(int n) {
        return arena.allocate(n);
    }
}
