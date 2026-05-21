package io.github.semyonsinchenko.arrowcompute.compute;

import io.github.semyonsinchenko.arrowcompute.compute.dispatch.AddDispatch;
import io.github.semyonsinchenko.arrowcompute.compute.dispatch.AggregateDispatch;
import io.github.semyonsinchenko.arrowcompute.compute.dispatch.DivideDispatch;
import io.github.semyonsinchenko.arrowcompute.compute.dispatch.MulDispatch;
import io.github.semyonsinchenko.arrowcompute.compute.dispatch.StartsWithDispatch;
import java.util.Objects;
import org.apache.arrow.vector.FieldVector;

/**
 * Public compute API facade.
 *
 * <p>Caller-owned lifetime contract: all input and output vectors must remain live for the full
 * duration of each call. The wrapper layer does not retain or extend Arrow buffer lifetime. This
 * matches arrow-rs / arrow-cpp kernel call semantics.
 */
public final class Compute {
    private Compute() {
    }

    /**
     * Adds two vectors into a preallocated output vector.
     *
     * <p>Caller-owned lifetime contract: wrappers do not retain buffers; caller keeps vectors live
     * for the full call (arrow-rs / arrow-cpp equivalent).
     */
    public static void add(FieldVector left, FieldVector right, FieldVector out) {
        AddDispatch.eval(left, right, out);
    }

    /**
     * Multiplies two vectors into a preallocated output vector.
     *
     * <p>Caller-owned lifetime contract: wrappers do not retain buffers; caller keeps vectors live
     * for the full call (arrow-rs / arrow-cpp equivalent).
     */
    public static void mul(FieldVector left, FieldVector right, FieldVector out) {
        MulDispatch.eval(left, right, out);
    }

    /**
     * Divides two vectors into a preallocated output vector.
     *
     * <p>Caller-owned lifetime contract: wrappers do not retain buffers; caller keeps vectors live
     * for the full call (arrow-rs / arrow-cpp equivalent).
     */
    public static void divide(FieldVector left, FieldVector right, FieldVector out) {
        DivideDispatch.eval(left, right, out);
    }

    /**
     * Aggregates a vector into a preallocated scalar output vector.
     *
     * <p>Caller-owned lifetime contract: wrappers do not retain buffers; caller keeps vectors live
     * for the full call (arrow-rs / arrow-cpp equivalent).
     */
    public static void sum(FieldVector input, FieldVector out) {
        AggregateDispatch.sum(input, out);
    }

    /**
     * Computes UTF-8 starts-with against a scalar needle into a preallocated output vector.
     *
     * <p>Caller-owned lifetime contract: wrappers do not retain buffers; caller keeps vectors live
     * for the full call (arrow-rs / arrow-cpp equivalent).
     */
    public static void startsWith(FieldVector input, byte[] needle, FieldVector out) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(needle, "needle must not be null");
        Objects.requireNonNull(out, "out must not be null");
        StartsWithDispatch.eval(input, needle, out);
    }
}
