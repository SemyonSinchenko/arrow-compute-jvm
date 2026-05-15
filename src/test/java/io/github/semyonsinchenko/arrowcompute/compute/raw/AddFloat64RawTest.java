package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class AddFloat64RawTest {
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
    void computeAll_isBitExactAgainstScalarReference() {
        int species = AddFloat64Raw.SPECIES.length();
        assertCase(1);
        assertCase(species);
        assertCase(species + 5);
    }

    private void assertCase(int n) {
        var left = doubles(n);
        var right = doubles(n);
        var out = doubles(n);
        for (int i = 0; i < n; i++) {
            long off = (long) i * Double.BYTES;
            double lv = switch (i % 6) {
                case 0 -> Double.NaN;
                case 1 -> Double.POSITIVE_INFINITY;
                case 2 -> Double.NEGATIVE_INFINITY;
                case 3 -> -0.0d;
                case 4 -> 1.0E307;
                default -> i * 0.25d - 11.0d;
            };
            double rv = switch (i % 5) {
                case 0 -> 4.0d;
                case 1 -> Double.NaN;
                case 2 -> Double.POSITIVE_INFINITY;
                case 3 -> -2.0d;
                default -> i * 0.5d + 3.0d;
            };
            left.set(AddFloat64Raw.FLOAT64_LE, off, lv);
            right.set(AddFloat64Raw.FLOAT64_LE, off, rv);
        }

        AddFloat64Raw.computeAll(left, right, out, n);

        for (int i = 0; i < n; i++) {
            long off = (long) i * Double.BYTES;
            double expected = left.get(AddFloat64Raw.FLOAT64_LE, off) + right.get(AddFloat64Raw.FLOAT64_LE, off);
            long expectedBits = Double.doubleToRawLongBits(expected);
            long actualBits = Double.doubleToRawLongBits(out.get(AddFloat64Raw.FLOAT64_LE, off));
            assertEquals(expectedBits, actualBits, "row=" + i);
        }
    }

    private MemorySegment doubles(int n) {
        return arena.allocate((long) n * Double.BYTES);
    }
}
