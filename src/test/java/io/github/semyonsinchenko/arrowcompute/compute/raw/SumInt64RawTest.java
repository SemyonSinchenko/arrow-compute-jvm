package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SumInt64RawTest {
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
    void noNulls_handlesEmptyAndMixedSigns() {
        var empty = longs(1);
        assertEquals(0L, SumInt64Raw.noNulls(empty, 0));

        long[] values = {7L, -3L, 18L, -22L, 9L};
        var input = longs(values.length);
        for (int i = 0; i < values.length; i++) {
            input.set(SumInt64Raw.INT64_LE, (long) i * Long.BYTES, values[i]);
        }
        assertEquals(9L, SumInt64Raw.noNulls(input, values.length));
    }

    @Test
    void noNulls_preservesWraparound() {
        var input = longs(3);
        input.set(SumInt64Raw.INT64_LE, 0L, Long.MAX_VALUE);
        input.set(SumInt64Raw.INT64_LE, Long.BYTES, 1L);
        input.set(SumInt64Raw.INT64_LE, (long) 2 * Long.BYTES, -4L);

        assertEquals(Long.MIN_VALUE - 4L, SumInt64Raw.noNulls(input, 3));
    }

    @Test
    void skipNulls_sumsOnlyValidRows() {
        int n = 6;
        var input = longs(n);
        for (int i = 0; i < n; i++) {
            input.set(SumInt64Raw.INT64_LE, (long) i * Long.BYTES, i + 1L);
        }
        var validity = bytes(1);
        validity.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0b0010_1101);

        assertEquals(14L, SumInt64Raw.skipNulls(input, validity, n));
    }

    @Test
    void skipNulls_returnsZeroWhenNoValidRows() {
        int n = 8;
        var input = longs(n);
        for (int i = 0; i < n; i++) {
            input.set(SumInt64Raw.INT64_LE, (long) i * Long.BYTES, 100L + i);
        }
        var validity = bytes(1);
        validity.set(ValueLayout.JAVA_BYTE, 0L, (byte) 0);

        assertEquals(0L, SumInt64Raw.skipNulls(input, validity, n));
    }

    private MemorySegment longs(int n) {
        return arena.allocate((long) n * Long.BYTES);
    }

    private MemorySegment bytes(int n) {
        return arena.allocate(n);
    }
}
