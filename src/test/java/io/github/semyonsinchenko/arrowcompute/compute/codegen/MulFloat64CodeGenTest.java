package io.github.semyonsinchenko.arrowcompute.compute.codegen;

import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MulFloat64CodeGenTest {
    @Test
    void parityWithAotKernel_specialValuesAndTail() throws Throwable {
        int n = MulFloat64Raw.SPECIES.length() + 3;
        long byteSize = (long) n * Double.BYTES;

        try (var arena = Arena.ofConfined()) {
            MemorySegment left = arena.allocate(byteSize);
            MemorySegment right = arena.allocate(byteSize);
            MemorySegment aotOut = arena.allocate(byteSize);
            MemorySegment codegenOut = arena.allocate(byteSize);

            for (int i = 0; i < n; i++) {
                long off = (long) i * Double.BYTES;
                double lv = switch (i % 7) {
                    case 0 -> Double.NaN;
                    case 1 -> Double.POSITIVE_INFINITY;
                    case 2 -> Double.NEGATIVE_INFINITY;
                    case 3 -> -0.0d;
                    case 4 -> 1.0E300;
                    case 5 -> -1.0E-300;
                    default -> i * 0.25d - 17.0d;
                };
                double rv = switch (i % 6) {
                    case 0 -> 0.0d;
                    case 1 -> Double.NaN;
                    case 2 -> Double.POSITIVE_INFINITY;
                    case 3 -> Double.NEGATIVE_INFINITY;
                    case 4 -> -2.0d;
                    default -> i * 0.5d + 3.0d;
                };
                left.set(MulFloat64Raw.FLOAT64_LE, off, lv);
                right.set(MulFloat64Raw.FLOAT64_LE, off, rv);
            }

            MulFloat64Raw.computeAll(left, right, aotOut, n);
            var handle = new MulFloat64CodeGen().loadComputeAllHandle();
            handle.invokeExact(left, right, codegenOut, n);

            for (int b = 0; b < byteSize; b++) {
                byte expected = aotOut.get(java.lang.foreign.ValueLayout.JAVA_BYTE, b);
                byte actual = codegenOut.get(java.lang.foreign.ValueLayout.JAVA_BYTE, b);
                if (expected != actual) {
                    int row = b / Double.BYTES;
                    assertEquals(
                            expected,
                            actual,
                            "first mismatch at byte=" + b + ", row=" + row
                    );
                }
            }
        }
    }
}
