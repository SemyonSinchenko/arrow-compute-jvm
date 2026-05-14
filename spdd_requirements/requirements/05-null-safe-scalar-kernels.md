# Requirement: Basic Null-Safe Scalar Kernels

## Business requirement

Expand fixed-width primitive kernels for operations that are safe to compute over null slots.

## Scope

Start with:

```text
AddInt64
AddFloat64
MulInt32
MulFloat64
```

Optionally add (do not stretch the diff):

```text
AddFloat32
MulInt64
MulFloat32
GreaterInt32
GreaterFloat64
```

Do not implement too many kernels in one prompt if the diff becomes large.

## Pattern

For each operation/type:

```text
raw/<Op><Type>Raw.java
wrapper/safe/<Op><Type>.java
dispatch/<Op>Dispatch.java
```

Raw kernels live in the **flat** `raw/` package (no `raw/safe/`). Wrapper classes live in `wrapper/safe/` (foundation `CORE_DESIGN.md §Package layout`).

Mode-specific raw kernels (when added later) follow `<Op><Type><Mode>Raw` naming: e.g., `AddInt32CheckedRaw`, `MinFloat64NanAgnosticRaw`. Checked variants are **not required** in this iteration.

## Semantics

For safe kernels:

```text
out_validity = input validity rule
out_data = compute all rows
```

Wrappers branch on `getNullCount()`:

- both inputs `nullCount == 0` → `Validity.markAllValid(out, n)`;
- otherwise → `Validity.propagateBinary(left, right, out, n)`.

Foundation invariants apply: little-endian, non-aliasing, zero slice offset, no per-row throw.

## Tests

Raw tests without Arrow (`Arena.ofConfined()` fixtures): scalar tails, boundary values, negatives, NaN/Infinity where relevant, integer overflow behavior (Java wraparound for unchecked variants).

Wrapper tests with Arrow (allocator debug mode enabled): all-valid, sparse nulls (1%), dense nulls (30%), all-null, output validity, output value count.

Dispatch tests: supported type routes correctly; unsupported type errors clearly via `Errors.unsupported(...)`.

Float correctness tests for `AddFloat64`/`MulFloat64` are bit-exact against a scalar Java reference (order of operations is well-defined for elementwise ops; ULP tolerance does **not** apply here — that's for aggregations).

## Benchmarks

For at least one integer and one floating kernel:

- raw vector vs naive `MemorySegment`;
- wrapper vs raw;
- dispatch vs wrapper.

Required dims per `BENCHMARKS.md §Wrapper benchmarks`. Use the fixed seed `0xC0FFEEL`.

## Non-goals

- Valid-only kernels (covered by `07-valid-only-div-int32.md`).
- Expression fusion.
- Registry.
- Checked variants (out of MVP scope; the suffix naming convention is documented but no kernel ships with `Checked` in this iteration).

## Acceptance criteria

- At least four null-safe kernels implemented (the must-haves above).
- Tests remain layer-specific.
- Benchmarks run and produce interpretable results with the baseline matrix labels from `BENCHMARKS.md §Benchmark goals`.

## Cross-references

- `CORE_DESIGN.md §Safe vs valid-only kernels`, §Null semantics, §Options and flags.
- `AGENTS.md §Null handling modes`, §Options and modes.
