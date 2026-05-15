package io.github.semyonsinchenko.arrowcompute.memory;

import java.lang.foreign.MemorySegment;
import java.util.Random;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MemoryUtilitiesTest {
    private static final int SAMPLE_SIZE = 32;

    @Test
    @DisplayName("SegmentViews rejects invalid byte sizes")
    void segmentViews_rejectsInvalidByteSizes() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("segment-views", 0, Long.MAX_VALUE);
             var vector = new IntVector("ints", child)) {
            vector.allocateNew(SAMPLE_SIZE);
            var data = vector.getDataBuffer();

            var nonPositive = assertThrows(IllegalArgumentException.class, () -> SegmentViews.fromArrowBuf(data, 0));
            assertEquals("byteSize must be > 0", nonPositive.getMessage());

            var tooLarge = assertThrows(
                    IllegalArgumentException.class,
                    () -> SegmentViews.fromArrowBuf(data, data.capacity() + 1)
            );
            assertEquals("byteSize exceeds buffer capacity", tooLarge.getMessage());
        }
    }

    @Test
    @DisplayName("Checks output capacity throws on insufficient capacity")
    void checks_outputCapacity_throwsOnInsufficientCapacity() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("checks-capacity", 0, Long.MAX_VALUE);
             var out = new IntVector("out", child)) {
            out.allocateNew(4);
            int capacity = out.getValueCapacity();
            int required = capacity + 1;
            var ex = assertThrows(IllegalArgumentException.class, () -> Checks.outputCapacity(out, required));
            assertEquals(
                    "output capacity too small for out: required=" + required + ", capacity=" + capacity,
                    ex.getMessage()
            );
        }
    }

    @Test
    @DisplayName("Checks zeroSliceOffset rejects variable-width sliced vectors")
    void checks_zeroSliceOffset_rejectsVariableWidthOffsetStart() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("checks-slice", 0, Long.MAX_VALUE);
             var source = new VarCharVector("in", child)) {
            source.allocateNew();
            source.setSafe(0, "alpha".getBytes());
            source.setSafe(1, "beta".getBytes());
            source.setSafe(2, "gamma".getBytes());
            source.setValueCount(3);

            var transferPair = source.getTransferPair(child);
            transferPair.splitAndTransfer(1, 2);
            var sliced = (VarCharVector) transferPair.getTo();
            int offset = 3;
            sliced.getOffsetBuffer().setInt(0, offset);

            var ex = assertThrows(IllegalArgumentException.class, () -> Checks.zeroSliceOffset(sliced));
            assertEquals("non-zero slice offset for in: offset=" + offset, ex.getMessage());
            sliced.close();
        }
    }

    @Test
    @DisplayName("BufferRefs retain and close keeps debug allocator balanced")
    void bufferRefs_retainAndClose_balancedUnderDebugAllocator() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("buffer-refs", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child)) {
            left.allocateNew(SAMPLE_SIZE);
            right.allocateNew(SAMPLE_SIZE);

            try (var refs = BufferRefs.retain(left, right)) {
                assertInstanceOf(BufferRefs.class, refs);
            }
        }
    }

    @Test
    @DisplayName("Validity propagateBinary matches expected bitmap for randomized nulls")
    void validity_propagateBinary_matchesExpectedBitmap_randomized() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("validity-binary", 0, Long.MAX_VALUE);
             var left = new IntVector("left", child);
             var right = new IntVector("right", child);
             var out = new IntVector("out", child)) {
            int n = 257;
            left.allocateNew(n);
            right.allocateNew(n);
            out.allocateNew(n);
            var rnd = new Random(7);

            for (int i = 0; i < n; i++) {
                if (rnd.nextBoolean()) {
                    left.set(i, i);
                } else {
                    left.setNull(i);
                }
                if (rnd.nextBoolean()) {
                    right.set(i, i);
                } else {
                    right.setNull(i);
                }
                out.set(i, i);
            }
            left.setValueCount(n);
            right.setValueCount(n);
            out.setValueCount(n);

            try (var refs = BufferRefs.retain(left, right, out)) {
                Validity.propagateBinary(left, right, out, n);
            }

            for (int i = 0; i < n; i++) {
                int expected = (left.isNull(i) || right.isNull(i)) ? 0 : 1;
                assertEquals(expected, out.isNull(i) ? 0 : 1);
            }
        }
    }

    @Test
    @DisplayName("Bitmap tail bits for non-multiple-of-8 are handled correctly")
    void bitmap_tailBits_nonMultipleOf8HandledCorrectly() {
        int n = 10;
        byte[] left = new byte[] {(byte) 0b11111111, (byte) 0b11111111};
        byte[] right = new byte[] {(byte) 0b00001111, (byte) 0b00001111};
        byte[] out = new byte[2];

        Bitmap.and(MemorySegment.ofArray(left), MemorySegment.ofArray(right), MemorySegment.ofArray(out), n);
        assertEquals(6, Bitmap.countSetBits(MemorySegment.ofArray(out), n));
        assertEquals((byte) 0b00000011, out[1]);
    }

    @Test
    @DisplayName("Errors factory returns expected unchecked types and message shape")
    void errors_factory_returnsExpectedUncheckedTypesAndMessages() {
        var sizeMismatch = Errors.sizeMismatch("add", 3, 4);
        assertInstanceOf(IllegalArgumentException.class, sizeMismatch);
        assertEquals("add valueCount mismatch: left=3, right=4", sizeMismatch.getMessage());

        var outputCapacity = Errors.outputCapacity("out", 9, 8);
        assertInstanceOf(IllegalArgumentException.class, outputCapacity);
        assertEquals("output capacity too small for out: required=9, capacity=8", outputCapacity.getMessage());

        var sliceOffset = Errors.sliceOffset("col", 2);
        assertInstanceOf(IllegalArgumentException.class, sliceOffset);
        assertEquals("non-zero slice offset for col: offset=2", sliceOffset.getMessage());

        var divByZero = Errors.divByZero(11);
        assertInstanceOf(ArithmeticException.class, divByZero);
        assertEquals("division by zero at row=11", divByZero.getMessage());

        var overflow = Errors.overflow(7);
        assertInstanceOf(ArithmeticException.class, overflow);
        assertEquals("arithmetic overflow at row=7", overflow.getMessage());
    }
}
