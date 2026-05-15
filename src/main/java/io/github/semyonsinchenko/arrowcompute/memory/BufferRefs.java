package io.github.semyonsinchenko.arrowcompute.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.FieldVector;

/**
 * Retain/release scope for vector data and validity buffers.
 *
 * <p>Wrappers use this to guarantee symmetric lifetime around temporary MemorySegment views.
 */
public final class BufferRefs implements AutoCloseable {
    private final List<ArrowBuf> retainedBuffers;
    private boolean closed;

    private BufferRefs(List<ArrowBuf> retainedBuffers) {
        this.retainedBuffers = retainedBuffers;
    }

    public static BufferRefs retain(FieldVector... vectors) {
        Objects.requireNonNull(vectors, "vectors must not be null");
        var retained = new ArrayList<ArrowBuf>(vectors.length * 2);
        try {
            for (var vector : vectors) {
                Objects.requireNonNull(vector, "vector must not be null");
                var data = Objects.requireNonNull(vector.getDataBuffer(), "data buffer must not be null");
                data.getReferenceManager().retain();
                retained.add(data);

                var validity = Objects.requireNonNull(vector.getValidityBuffer(), "validity buffer must not be null");
                validity.getReferenceManager().retain();
                retained.add(validity);
            }
            return new BufferRefs(retained);
        } catch (RuntimeException ex) {
            releaseReverse(retained);
            throw ex;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseReverse(retainedBuffers);
    }

    private static void releaseReverse(List<ArrowBuf> buffers) {
        for (int i = buffers.size() - 1; i >= 0; i--) {
            buffers.get(i).getReferenceManager().release();
        }
    }
}
