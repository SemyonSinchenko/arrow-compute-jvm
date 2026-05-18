package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.validonly.DivInt32;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;

/**
 * Public explicit dispatch surface for divide operation.
 */
public class DivideDispatch {
    private DivideDispatch() {
    }

    public static void eval(FieldVector left, FieldVector right, FieldVector out) {
        if (left instanceof IntVector && right instanceof IntVector && out instanceof IntVector) {
            DivInt32.eval((IntVector) left, (IntVector) right, (IntVector) out);
            return;
        }
        throw Errors.unsupported("divide", left, right, out);
    }
}
