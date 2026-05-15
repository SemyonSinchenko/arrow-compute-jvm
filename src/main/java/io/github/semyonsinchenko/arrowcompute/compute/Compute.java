package io.github.semyonsinchenko.arrowcompute.compute;

import io.github.semyonsinchenko.arrowcompute.compute.dispatch.AddDispatch;
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
}
