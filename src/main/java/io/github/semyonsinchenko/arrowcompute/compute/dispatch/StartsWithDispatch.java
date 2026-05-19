package io.github.semyonsinchenko.arrowcompute.compute.dispatch;

import io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe.StartsWithUtf8;
import io.github.semyonsinchenko.arrowcompute.memory.Errors;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;

/**
 * Public explicit dispatch surface for startsWith operation.
 */
public class StartsWithDispatch {
    private StartsWithDispatch() {
    }

    public static void eval(FieldVector input, byte[] needle, FieldVector out) {
        if (input instanceof VarCharVector && out instanceof BitVector) {
            StartsWithUtf8.eval((VarCharVector) input, needle, (BitVector) out);
            return;
        }
        throw Errors.unsupported("startsWith", input, null, out);
    }
}
