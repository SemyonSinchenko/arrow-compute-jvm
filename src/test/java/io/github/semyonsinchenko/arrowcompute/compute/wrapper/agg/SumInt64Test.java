package io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg;

import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SumInt64Test {
    @Test
    void eval_writesSingleScalarForAllValidInput() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("sum-int64-all-valid", 0, Long.MAX_VALUE);
             var input = new BigIntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(4);
            out.allocateNew(1);
            input.set(0, 5L);
            input.set(1, -1L);
            input.set(2, 10L);
            input.set(3, 7L);
            input.setValueCount(4);

            SumInt64.eval(input, out);

            assertEquals(1, out.getValueCount());
            assertEquals(false, out.isNull(0));
            assertEquals(21L, out.get(0));
        }
    }

    @Test
    void eval_sumsOnlyValidRowsForNullableInput() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("sum-int64-nullable", 0, Long.MAX_VALUE);
             var input = new BigIntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(5);
            out.allocateNew(1);
            input.set(0, 100L);
            input.setNull(1);
            input.set(2, -3L);
            input.setNull(3);
            input.set(4, 9L);
            input.setValueCount(5);

            SumInt64.eval(input, out);

            assertEquals(1, out.getValueCount());
            assertEquals(false, out.isNull(0));
            assertEquals(106L, out.get(0));
        }
    }

    @Test
    void eval_returnsNullScalarForAllNullInput() {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("sum-int64-all-null", 0, Long.MAX_VALUE);
             var input = new BigIntVector("input", child);
             var out = new BigIntVector("out", child)) {
            input.allocateNew(4);
            out.allocateNew(1);
            for (int i = 0; i < 4; i++) {
                input.setNull(i);
            }
            input.setValueCount(4);

            SumInt64.eval(input, out);

            assertEquals(1, out.getValueCount());
            assertEquals(true, out.isNull(0));
        }
    }
}
