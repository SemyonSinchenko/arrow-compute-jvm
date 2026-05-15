package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddInt32;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddInt64;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddFloat64;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;

/**
 * Public explicit dispatch surface for add operation.
 */
public class AddDispatch {
    private AddDispatch() {
    }

    public static void eval(FieldVector left, FieldVector right, FieldVector out) {
        if (left instanceof IntVector && right instanceof IntVector && out instanceof IntVector) {
            AddInt32.eval((IntVector) left, (IntVector) right, (IntVector) out);
            return;
        }
        if (left instanceof BigIntVector && right instanceof BigIntVector && out instanceof BigIntVector) {
            AddInt64.eval((BigIntVector) left, (BigIntVector) right, (BigIntVector) out);
            return;
        }
        if (left instanceof Float8Vector && right instanceof Float8Vector && out instanceof Float8Vector) {
            AddFloat64.eval((Float8Vector) left, (Float8Vector) right, (Float8Vector) out);
            return;
        }
        throw Errors.unsupported("add", left, right, out);
    }
}
