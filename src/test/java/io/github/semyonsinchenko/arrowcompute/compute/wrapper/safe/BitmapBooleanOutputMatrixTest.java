package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import io.github.semyonsinchenko.arrowcompute.compute.raw.CompareInt32GreaterRaw;
import io.github.semyonsinchenko.arrowcompute.exception.BitmapTailViolationException;
import io.github.semyonsinchenko.arrowcompute.memory.Bitmap;
import io.github.semyonsinchenko.arrowcompute.memory.SegmentViews;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.util.Random;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.BitVectorHelper;
import org.apache.arrow.vector.IntVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BitmapBooleanOutputMatrixTest {
    private static final long FIXED_SEED = 0xC0FFEEL;

    @Test
    @DisplayName("bitmap ops cover and/or/andNot/not with tail edges")
    void bitmapOps_coverAndOrAndNotNot_withTailEdges() {
        int[] sizes = new int[] {0, 1, 7, 8, 9, 63, 64, 65, 257};
        var rnd = new Random(FIXED_SEED);
        for (int n : sizes) {
            int bytes = (n + 7) >>> 3;
            byte[] left = new byte[bytes];
            byte[] right = new byte[bytes];
            rnd.nextBytes(left);
            rnd.nextBytes(right);
            if ((n & 7) != 0 && bytes > 0) {
                int mask = (1 << (n & 7)) - 1;
                left[bytes - 1] &= (byte) mask;
                right[bytes - 1] &= (byte) mask;
            }

            byte[] andOut = new byte[bytes];
            byte[] orOut = new byte[bytes];
            byte[] andNotOut = new byte[bytes];
            byte[] notOut = new byte[bytes];
            Bitmap.and(MemorySegment.ofArray(left), MemorySegment.ofArray(right), MemorySegment.ofArray(andOut), n);
            Bitmap.or(MemorySegment.ofArray(left), MemorySegment.ofArray(right), MemorySegment.ofArray(orOut), n);
            Bitmap.andNot(MemorySegment.ofArray(left), MemorySegment.ofArray(right), MemorySegment.ofArray(andNotOut), n);
            Bitmap.not(MemorySegment.ofArray(left), MemorySegment.ofArray(notOut), n);

            for (int i = 0; i < n; i++) {
                int l = bitAt(left, i);
                int r = bitAt(right, i);
                assertEquals(l & r, bitAt(andOut, i), "and mismatch row=" + i + " n=" + n);
                assertEquals(l | r, bitAt(orOut, i), "or mismatch row=" + i + " n=" + n);
                assertEquals(l & (r ^ 1), bitAt(andNotOut, i), "andNot mismatch row=" + i + " n=" + n);
                assertEquals(l ^ 1, bitAt(notOut, i), "not mismatch row=" + i + " n=" + n);
            }

            int andExpectedCount = 0;
            for (int i = 0; i < n; i++) {
                andExpectedCount += bitAt(andOut, i);
            }
            assertEquals(andExpectedCount, Bitmap.countSetBits(MemorySegment.ofArray(andOut), n));
        }
    }

    @Test
    @DisplayName("boolean output all false/all true/alternating/random")
    void booleanOutput_allFalse_allTrue_alternating_random() {
        assertBooleanProfile(128, Profile.ALL_FALSE);
        assertBooleanProfile(128, Profile.ALL_TRUE);
        assertBooleanProfile(257, Profile.ALTERNATING);
        assertBooleanProfile(257, Profile.RANDOM);
    }

    @Test
    @DisplayName("wrapper output validateFull passes after setValueCount")
    void wrapperOutput_validateFull_passesAfterSetValueCount() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compare-int32-validate-full", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new BitVector("out", child)) {
            int n = 257;
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);
            for (int i = 0; i < n; i++) {
                left.set(i, i);
                right.set(i, n - i);
            }
            left.setValueCount(n);
            right.setValueCount(n);

            CompareInt32Greater.eval(left, right, out);
            out.validateFull();
            assertEquals(n, out.getValueCount());
        }
    }

    @Test
    @DisplayName("tail corruption regression detected before finalization")
    void tailCorruption_regression_detectedBeforeFinalization() throws Exception {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compare-int32-tail-corruption", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new BitVector("out", child)) {
            int n = 9;
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);
            for (int i = 0; i < n; i++) {
                left.set(i, i + 1);
                right.set(i, i);
            }
            left.setValueCount(n);
            right.setValueCount(n);

            long dataBytes = (long) n * Integer.BYTES;
            int bitmapBytes = (int) BitVectorHelper.getValidityBufferSize(n);
            var leftSeg = SegmentViews.data(left, dataBytes);
            var rightSeg = SegmentViews.data(right, dataBytes);
            var outSeg = SegmentViews.data(out, bitmapBytes);
            CompareInt32GreaterRaw.computeAll(leftSeg, rightSeg, outSeg, n);

            long lastByteOff = bitmapBytes - 1L;
            int b = Byte.toUnsignedInt(outSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, lastByteOff));
            outSeg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, lastByteOff, (byte) (b | 0b1110_0000));

            Method method = CompareInt32Greater.class.getDeclaredMethod(
                    "validateTailClear",
                    java.lang.foreign.MemorySegment.class,
                    int.class
            );
            method.setAccessible(true);
            var ex = assertThrows(Exception.class, () -> method.invoke(null, outSeg, n));
            assertEquals(BitmapTailViolationException.class, ex.getCause().getClass());
            assertEquals("BITMAP_TAIL_VIOLATION", ((BitmapTailViolationException) ex.getCause()).errorCode());
        }
    }

    private static void assertBooleanProfile(int n, Profile profile) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("compare-int32-profile-" + profile.name(), 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new BitVector("out", child)) {
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);
            var rnd = new Random(FIXED_SEED + n + profile.ordinal());
            boolean[] expectedValue = new boolean[n];
            boolean[] expectedValid = new boolean[n];

            for (int i = 0; i < n; i++) {
                int lv;
                int rv;
                boolean valid;
                switch (profile) {
                    case ALL_FALSE -> {
                        lv = i;
                        rv = i + 1;
                        valid = true;
                    }
                    case ALL_TRUE -> {
                        lv = i + 10;
                        rv = i;
                        valid = true;
                    }
                    case ALTERNATING -> {
                        lv = i;
                        rv = i - 1;
                        valid = (i & 1) == 0;
                    }
                    case RANDOM -> {
                        lv = rnd.nextInt();
                        rv = rnd.nextInt();
                        valid = rnd.nextInt(5) != 0;
                    }
                    default -> throw new IllegalStateException("unknown profile");
                }

                left.set(i, lv);
                right.set(i, rv);
                expectedValue[i] = lv > rv;
                if (valid) {
                    expectedValid[i] = true;
                } else {
                    expectedValid[i] = false;
                    if ((i & 1) == 0) {
                        left.setNull(i);
                    } else {
                        right.setNull(i);
                    }
                }
            }
            left.setValueCount(n);
            right.setValueCount(n);

            CompareInt32Greater.eval(left, right, out);

            for (int i = 0; i < n; i++) {
                assertEquals(expectedValid[i], !out.isNull(i), "validity mismatch row=" + i + " profile=" + profile);
                if (expectedValid[i]) {
                    assertEquals(expectedValue[i], out.get(i) != 0, "value mismatch row=" + i + " profile=" + profile);
                }
            }
        }
    }

    private static int bitAt(byte[] data, int i) {
        return (data[i >>> 3] >>> (i & 7)) & 1;
    }

    private enum Profile {
        ALL_FALSE,
        ALL_TRUE,
        ALTERNATING,
        RANDOM
    }
}
