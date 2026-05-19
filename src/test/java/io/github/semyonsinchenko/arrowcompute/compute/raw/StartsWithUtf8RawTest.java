package io.github.semyonsinchenko.arrowcompute.compute.raw;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class StartsWithUtf8RawTest {
    private Arena arena;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        arena = Arena.ofConfined();
    }

    @AfterEach
    void tearDown() {
        arena.close();
    }

    @Test
    @DisplayName("n=0 returns without touching output")
    void computeAll_handlesEmptyInput() {
        var offsets = arena.allocate(Integer.BYTES);
        offsets.set(StartsWithUtf8Raw.INT32_LE, 0, 0);
        var data = arena.allocate(1);
        var out = arena.allocate(1);
        out.set(StartsWithUtf8Raw.BYTE, 0, (byte) 0x5A);

        StartsWithUtf8Raw.computeAll(offsets, data, "ab".getBytes(StandardCharsets.UTF_8), out, 0);

        assertEquals(0x5A, Byte.toUnsignedInt(out.get(StartsWithUtf8Raw.BYTE, 0)));
    }

    @Test
    @DisplayName("empty needle sets all rows true and clears tail bits")
    void computeAll_emptyNeedle() {
        byte[][] rows = new byte[][]{"x".getBytes(StandardCharsets.UTF_8), new byte[0], "xyz".getBytes(StandardCharsets.UTF_8)};
        var fixture = fixture(rows);
        int n = rows.length;
        int outBytes = (n + 7) >>> 3;
        var out = arena.allocate(outBytes);

        StartsWithUtf8Raw.computeAll(fixture.offsets(), fixture.data(), new byte[0], out, n);

        assertEquals(0b0000_0111, Byte.toUnsignedInt(out.get(StartsWithUtf8Raw.BYTE, 0)));
    }

    @Test
    @DisplayName("mix of ASCII and multibyte UTF-8 rows")
    void computeAll_mixedUtf8() {
        byte[][] rows = new byte[][]{
                "alphabet".getBytes(StandardCharsets.UTF_8),
                "alpha".getBytes(StandardCharsets.UTF_8),
                "beta".getBytes(StandardCharsets.UTF_8),
                "álpha".getBytes(StandardCharsets.UTF_8),
                "префикс".getBytes(StandardCharsets.UTF_8),
                "ab".getBytes(StandardCharsets.UTF_8),
                "a".getBytes(StandardCharsets.UTF_8),
                "".getBytes(StandardCharsets.UTF_8),
                "alpha-beta".getBytes(StandardCharsets.UTF_8)
        };
        var fixture = fixture(rows);
        int n = rows.length;
        int outBytes = (n + 7) >>> 3;
        var out = arena.allocate(outBytes);

        StartsWithUtf8Raw.computeAll(fixture.offsets(), fixture.data(), "alpha".getBytes(StandardCharsets.UTF_8), out, n);

        assertEquals(1, bit(out, 0));
        assertEquals(1, bit(out, 1));
        assertEquals(0, bit(out, 2));
        assertEquals(0, bit(out, 3));
        assertEquals(0, bit(out, 4));
        assertEquals(0, bit(out, 5));
        assertEquals(0, bit(out, 6));
        assertEquals(0, bit(out, 7));
        assertEquals(1, bit(out, 8));
        assertEquals(0, Byte.toUnsignedInt(out.get(StartsWithUtf8Raw.BYTE, 1)) & 0b1111_1110);
    }

    @Test
    @DisplayName("species boundary and scalar tail")
    void computeAll_speciesBoundaryAndTail() {
        int species = StartsWithUtf8Raw.SPECIES.length();
        String prefix = "a".repeat(species + 3);
        byte[] needle = prefix.getBytes(StandardCharsets.UTF_8);
        byte[][] rows = new byte[][]{
                (prefix + "z").getBytes(StandardCharsets.UTF_8),
                ("b" + prefix.substring(1)).getBytes(StandardCharsets.UTF_8),
                prefix.getBytes(StandardCharsets.UTF_8)
        };
        var fixture = fixture(rows);
        var out = arena.allocate(1);

        StartsWithUtf8Raw.computeAll(fixture.offsets(), fixture.data(), needle, out, rows.length);

        assertEquals(1, bit(out, 0));
        assertEquals(0, bit(out, 1));
        assertEquals(1, bit(out, 2));
    }

    private Fixture fixture(byte[][] rows) {
        int n = rows.length;
        int total = 0;
        for (byte[] row : rows) {
            total += row.length;
        }
        var offsets = arena.allocate((long) (n + 1) * Integer.BYTES);
        var data = arena.allocate(Math.max(1, total));
        int off = 0;
        offsets.set(StartsWithUtf8Raw.INT32_LE, 0, 0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < rows[i].length; j++) {
                data.set(StartsWithUtf8Raw.BYTE, off + j, rows[i][j]);
            }
            off += rows[i].length;
            offsets.set(StartsWithUtf8Raw.INT32_LE, (long) (i + 1) * Integer.BYTES, off);
        }
        return new Fixture(offsets, data);
    }

    private static int bit(MemorySegment out, int row) {
        long byteIndex = row >>> 3;
        int mask = 1 << (row & 7);
        int b = Byte.toUnsignedInt(out.get(StartsWithUtf8Raw.BYTE, byteIndex));
        return (b & mask) == 0 ? 0 : 1;
    }

    private record Fixture(MemorySegment offsets, MemorySegment data) {
    }
}
