package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.MulFloat64;
import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.MulInt32;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;

/**
 * Public explicit dispatch surface for multiply operation.
 */
public class MulDispatch {
    private MulDispatch() {
    }

    public static void eval(FieldVector left, FieldVector right, FieldVector out) {
        if (left instanceof IntVector && right instanceof IntVector && out instanceof IntVector) {
            MulInt32.eval((IntVector) left, (IntVector) right, (IntVector) out);
            return;
        }
        if (left instanceof Float8Vector && right instanceof Float8Vector && out instanceof Float8Vector) {
            MulFloat64.eval((Float8Vector) left, (Float8Vector) right, (Float8Vector) out);
            return;
        }
        throw Errors.unsupported("mul", left, right, out);
    }
}
