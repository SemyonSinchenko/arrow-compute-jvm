package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.AddInt32;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import org.apache.arrow.vector.FieldVector;
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
        throw Errors.unsupported("add", left, right, out);
    }
}
