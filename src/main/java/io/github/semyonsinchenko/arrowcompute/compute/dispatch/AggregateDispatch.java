package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg.SumInt64;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;

/**
 * Public explicit dispatch surface for aggregate operations.
 */
public class AggregateDispatch {
    private AggregateDispatch() {
    }

    public static void sum(FieldVector input, FieldVector out) {
        if (input instanceof BigIntVector && out instanceof BigIntVector) {
            SumInt64.eval((BigIntVector) input, (BigIntVector) out);
            return;
        }
        throw new UnsupportedOperationException(
                "unsupported sum combination: input=%s, out=%s"
                        .formatted(typeName(input), typeName(out))
        );
    }

    private static String typeName(FieldVector vector) {
        return vector == null ? "null" : vector.getMinorType().name();
    }
}
