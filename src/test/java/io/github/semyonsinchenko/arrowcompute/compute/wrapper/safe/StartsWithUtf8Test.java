package io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe;

import java.nio.charset.StandardCharsets;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StartsWithUtf8Test {
    @Test
    @DisplayName("wrapper propagates validity and finalizes value count")
    void eval_propagatesValidityAndValues() {
        assertProfile(256, 0);
        assertProfile(256, 1);
        assertProfile(256, 30);
        assertProfile(64, 100);
    }

    private void assertProfile(int n, int nullPercent) {
        try (var root = new RootAllocator();
             var child = root.newChildAllocator("startswith-utf8-wrapper", 0, Long.MAX_VALUE);
             var input = new VarCharVector("input", child);
             var out = new BitVector("out", child)) {
            input.allocateNew();
            out.allocateNew(n);

            var valid = new boolean[n];
            var expected = new boolean[n];
            for (int i = 0; i < n; i++) {
                boolean rowValid = (i % 100) >= nullPercent;
                valid[i] = rowValid;
                String value = (i % 4 == 0) ? "alpha-" + i : "beta-" + i;
                if (rowValid) {
                    input.setSafe(i, value.getBytes(StandardCharsets.UTF_8));
                    expected[i] = value.startsWith("alpha");
                } else {
                    input.setNull(i);
                    expected[i] = false;
                }
            }
            input.setValueCount(n);

            StartsWithUtf8.eval(input, "alpha".getBytes(StandardCharsets.UTF_8), out);

            assertEquals(n, out.getValueCount());
            out.validateFull();
            for (int i = 0; i < n; i++) {
                assertEquals(valid[i], !out.isNull(i), "validity row=" + i);
                if (valid[i]) {
                    assertEquals(expected[i], out.get(i) == 1, "value row=" + i);
                }
            }
        }
    }
}
