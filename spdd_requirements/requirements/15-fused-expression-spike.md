# Requirement: Fused Expression Spike

## Business requirement

Test whether a warmed-up JVM fused kernel can outperform generic PyArrow or JNI compute chains by avoiding temporary buffers and repeated dispatch.

## Scope

Implement one handwritten fused expression kernel.

Hard prerequisites for this iteration:

- benchmark harness and reporting conventions from `10-jmh-benchmarks.md`;
- available JVM chain ops: `Compute.mul`, `Compute.add`, `Compute.subtract`, `Compute.max`.

If any required chain op is missing, add it in a predecessor iteration or
explicitly narrow the baseline before implementation starts.

Initial expression:

```text
out = max(a * scale + b - c, 0)
```

Type: `Float64`.

## Implementations to compare

```text
JVM fused raw kernel
JVM chain using own kernels
PyArrow compute chain
```

Optional: native baseline (JNI or FFM downcall) Arrow C++ chain, handwritten native fused kernel.

## Null profiles

```text
0% null
1% null
10% null
30% null
```

For MVP, the **compute-all-with-validity-propagation** path is enough. All operations in the fused expression are safe on null lane data per foundation `CORE_DESIGN.md §Null semantics §Float special values`:

- `a * scale`, `(...) + b`, `(...) - c` are all IEEE 754 arithmetic — safe on arbitrary bit patterns.
- `max(x, 0)` returns `NaN` if `x` is `NaN`; foundation defines NaN as a value, not a null. The wrapper handles null lanes via the validity bitmap, not via NaN.

Output validity is computed once: `out_validity = a_validity & b_validity & c_validity` (binary propagation chained).

## Platforms

Run locally first. Later run on x86_64 and AWS Graviton arm64.

## Tests

Raw fused tests without Arrow: normal values, negative clamped values, scalar tails, NaN/Infinity if relevant.

Wrapper/integration tests with Arrow: all-valid, sparse nulls, output validity, output values where valid.

## Benchmark interpretation

Allowed claim: JVM fused expression beats generic PyArrow compute chain in this steady-state scenario.

Forbidden claim: Java is faster than C++.

## Non-goals

No expression compiler, runtime code generation, or generic expression tree yet.

## Acceptance criteria

- Fused kernel exists, lives in flat `raw/` (e.g., `raw/FusedScaleAddSubClampFloat64Raw.java`) with `static void computeAll(...)` entry point.
- Wrapper exists, propagates validity once across the chain, calls the raw kernel once.
- JVM chain baseline exists (using `Compute.mul`, `Compute.add`, `Compute.subtract`, `Compute.max` end-to-end with temporary buffers).
- PyArrow chain baseline exists or is explicitly deferred.
- Native baseline (JNI or FFM chain) is optional.
- Benchmark report is scenario-honest and matches `BENCHMARKS.md §Fusion benchmarks` interpretation rules.

## Cross-references

- `CORE_DESIGN.md §Null semantics §Float special values` (NaN policy for `max`).
- `CORE_DESIGN.md §Two-tier kernel design` (this is fast tier; no slow-tier ingredient in the expression).
- `BENCHMARKS.md §Fusion benchmarks`, §Native-baseline benchmarks.
- `AGENTS.md §Expression fusion`.
