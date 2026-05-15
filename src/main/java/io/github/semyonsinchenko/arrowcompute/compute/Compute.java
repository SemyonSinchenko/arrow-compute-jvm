package io.github.semyonsinchenko.arrowcompute.compute;

import io.github.semyonsinchenko.arrowcompute.compute.dispatch.AddDispatch;
import io.github.semyonsinchenko.arrowcompute.compute.dispatch.MulDispatch;
import org.apache.arrow.vector.FieldVector;

/**
 * Public compute API facade.
 */
public final class Compute {
    private Compute() {
    }

    public static void add(FieldVector left, FieldVector right, FieldVector out) {
        AddDispatch.eval(left, right, out);
    }

    public static void mul(FieldVector left, FieldVector right, FieldVector out) {
        MulDispatch.eval(left, right, out);
    }
}
