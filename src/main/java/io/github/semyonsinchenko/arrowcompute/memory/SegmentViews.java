package io.github.semyonsinchenko.arrowcompute.memory;

import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.FieldVector;

/**
 * Unsafe ArrowBuf-to-MemorySegment bridge used by wrappers.
 *
 * <p>Null policy and aliasing are handled by wrapper-level validation. Segment lifetime is bounded by
 * the BufferRefs retain scope and segments must not escape that scope.
 */
public final class SegmentViews {
    static final long MAX_BYTE_SIZE = Long.MAX_VALUE;

    private SegmentViews() {
    }

    public static MemorySegment fromArrowBuf(ArrowBuf buffer, long byteSize) {
        Objects.requireNonNull(buffer, "buffer must not be null");
        if (byteSize <= 0) {
            throw new IllegalArgumentException("byteSize must be > 0");
        }
        if (byteSize > MAX_BYTE_SIZE) {
            throw new IllegalArgumentException("byteSize exceeds supported maximum");
        }
        if (byteSize > buffer.capacity()) {
            throw new IllegalArgumentException("byteSize exceeds buffer capacity");
        }

        long address = buffer.memoryAddress();
        return MemorySegment.ofAddress(address).reinterpret(byteSize);
    }

    public static MemorySegment data(FieldVector vector, long byteSize) {
        Objects.requireNonNull(vector, "vector must not be null");
        return fromArrowBuf(vector.getDataBuffer(), byteSize);
    }

    public static MemorySegment validity(FieldVector vector, long byteSize) {
        Objects.requireNonNull(vector, "vector must not be null");
        return fromArrowBuf(vector.getValidityBuffer(), byteSize);
    }
}
