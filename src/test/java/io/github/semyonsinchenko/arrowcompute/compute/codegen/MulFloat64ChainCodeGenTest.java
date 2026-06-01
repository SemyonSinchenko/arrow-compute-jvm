package io.github.semyonsinchenko.arrowcompute.compute.codegen;

import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain20Raw;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain50Raw;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Chain5Raw;
import io.github.semyonsinchenko.arrowcompute.compute.raw.MulFloat64Raw;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MulFloat64ChainCodeGenTest {
    @Test
    void parityWithAotKernelForK5K20K50() throws Throwable {
        int n = MulFloat64Chain5Raw.SPECIES.length() + 3;
        long byteSize = (long) n * Double.BYTES;

        try (var arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(byteSize);
            for (int i = 0; i < n; i++) {
                long off = (long) i * Double.BYTES;
                double v = switch (i % 9) {
                    case 0 -> -3.0d;
                    case 1 -> -0.0d;
                    case 2 -> 0.0d;
                    case 3 -> 0.5d;
                    case 4 -> -0.75d;
                    case 5 -> Double.NaN;
                    case 6 -> Double.POSITIVE_INFINITY;
                    case 7 -> Double.NEGATIVE_INFINITY;
                    default -> i * 0.125d;
                };
                x.set(MulFloat64Chain5Raw.FLOAT64_LE, off, v);
            }

            assertParityForK(arena, x, n, byteSize, 5);
            assertParityForK(arena, x, n, byteSize, 20);
            assertParityForK(arena, x, n, byteSize, 50);
        }
    }

    @Test
    void parityWithNaiveChainForK5() {
        int n = MulFloat64Chain5Raw.SPECIES.length() + 3;
        long byteSize = (long) n * Double.BYTES;
        try (var arena = Arena.ofConfined()) {
            MemorySegment x = arena.allocate(byteSize);
            MemorySegment step2 = arena.allocate(byteSize);
            MemorySegment step3 = arena.allocate(byteSize);
            MemorySegment step4 = arena.allocate(byteSize);
            MemorySegment naiveOut = arena.allocate(byteSize);
            MemorySegment aotOut = arena.allocate(byteSize);

            for (int i = 0; i < n; i++) {
                long off = (long) i * Double.BYTES;
                x.set(MulFloat64Chain5Raw.FLOAT64_LE, off, i * 0.25d - 1.0d);
            }

            MulFloat64Raw.computeAll(x, x, step2, n);
            MulFloat64Raw.computeAll(step2, x, step3, n);
            MulFloat64Raw.computeAll(step3, x, step4, n);
            MulFloat64Raw.computeAll(step4, x, naiveOut, n);

            MulFloat64Chain5Raw.computeAll(x, aotOut, n);
            assertByteEqual(naiveOut, aotOut, byteSize);
        }
    }

    private static void assertParityForK(Arena arena, MemorySegment x, int n, long byteSize, int k) throws Throwable {
        MemorySegment janinoOut = arena.allocate(byteSize);
        MemorySegment aotOut = arena.allocate(byteSize);
        var handle = new MulFloat64ChainCodeGen().loadComputeAllHandle(k);
        handle.invokeExact(x, janinoOut, n);
        switch (k) {
            case 5 -> MulFloat64Chain5Raw.computeAll(x, aotOut, n);
            case 20 -> MulFloat64Chain20Raw.computeAll(x, aotOut, n);
            case 50 -> MulFloat64Chain50Raw.computeAll(x, aotOut, n);
            default -> throw new IllegalArgumentException("Unsupported chain depth k=" + k);
        }
        assertByteEqual(aotOut, janinoOut, byteSize, "k=" + k);
    }

    private static void assertByteEqual(MemorySegment expected, MemorySegment actual, long byteSize) {
        assertByteEqual(expected, actual, byteSize, "");
    }

    private static void assertByteEqual(MemorySegment expected, MemorySegment actual, long byteSize, String context) {
        for (long b = 0; b < byteSize; b++) {
            byte exp = expected.get(ValueLayout.JAVA_BYTE, b);
            byte act = actual.get(ValueLayout.JAVA_BYTE, b);
            if (exp != act) {
                int row = (int) (b / Double.BYTES);
                assertEquals(exp, act, context + " first mismatch at byte=" + b + ", row=" + row);
            }
        }
    }
}
