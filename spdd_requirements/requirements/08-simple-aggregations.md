# Requirement: Simple Aggregations

## Business requirement

Implement first array-to-scalar aggregation kernels over fixed-width primitive Arrow data.

## Scope

Start with `SumInt64Raw` and `SumInt64` wrapper.

Optionally add `SumFloat64`, `MinFloat64`, `MaxFloat64` if scope permits.

Raw kernels live in flat `raw/`; wrappers in `wrapper/agg/`.

## Raw API

Returning primitives is acceptable:

```java
static long noNulls(MemorySegment input, int n)
static long skipNulls(MemorySegment input, MemorySegment validity, int n)
```

The raw method computes the sum (or min/max) **over valid rows only**. It is the **wrapper**'s job to handle the all-null case (because a primitive `long` return cannot represent "null result"). See `CORE_DESIGN.md §Aggregation state model` — ungrouped state is a primitive local.

## Wrapper API

`SumInt64.eval(BigIntVector input, BigIntVector out)`:

- `Checks.outputCapacity(out, 1);`
- `Checks.zeroSliceOffset(input);`
- `try (var refs = BufferRefs.retain(input, out)) { ... }`.
- **All-null check**: if `input.getNullCount() == input.getValueCount()`:
  - mark `out` row 0 as null, set value count to 1, return;
  - do not call the raw kernel.
- Otherwise:
  - `nullCount == 0` → `long result = SumInt64Raw.noNulls(inputData, n);`
  - else → `long result = SumInt64Raw.skipNulls(inputData, validityData, n);`
  - write `result` to `out` row 0, mark `out` row 0 as valid, set value count to 1.

The wrapper applies `skip_nulls = true, min_count = 1` semantics (foundation default for MVP). If `min_count` becomes configurable later, it joins the primitive-flag pattern from `AGENTS.md §Options and modes`.

## Null semantics

For MVP:

```text
skip_nulls = true
min_count = 1
```

If all values are null, output is null (wrapper enforces).

No options object yet.

## Aggregation state shape

Even for ungrouped MVP aggregations, the state shape matches the SoA layout from `CORE_DESIGN.md §Aggregation state model`:

- ungrouped sum: a single `long sum` local;
- ungrouped min/max: a single `int min` or `long min` local plus a `boolean seen` if min/max requires "at least one valid value seen";
- ungrouped count: a single `long count` local.

The grouped state engine remains out of MVP scope. This iteration only confirms the state shape so it doesn't have to be retrofitted when grouping lands.

## Overflow / FP policy

- `SumInt64`: Java `long` wraparound. Matches Arrow default for int64 sum.
- `SumFloat64`: Java arithmetic. **No Kahan or pairwise summation** in MVP; the project promises naive floating sum. This is a documented choice, not a bug.

## Float ULP tolerance

`SumFloat64` correctness tests allow up to **4 ULP** difference vs a reference scalar Java sum because the SIMD reduction tree changes order of operations. `MinFloat64` and `MaxFloat64` are bit-exact (min/max is order-independent).

## Tests

Raw tests without Arrow (`Arena.ofConfined()` fixtures):

- empty (`n = 0`) — defined behavior: `noNulls` returns 0 for sum;
- one value;
- positives only;
- negatives only;
- mixed;
- `all-null via synthetic bitmap` — `skipNulls` returns 0 (wrapper interprets all-null separately);
- mixed null pattern via bitmap;
- boundary values (`Long.MIN_VALUE`, `Long.MAX_VALUE`);
- overflow semantics (wraparound).

Wrapper tests with Arrow (`-Darrow.memory.debug.allocator=true`):

- output value count is 1;
- all-valid input → correct sum, output validity = 1;
- mixed nulls → sum over valid rows, output validity = 1;
- all-null input → output validity = 0, raw kernel was **not** called (assert via a test-only counter or a spy `MemorySegment`);
- float ULP tolerance for `SumFloat64` (when implemented).

## Benchmarks

- Raw aggregation vs naive Java loop;
- Wrapper aggregation;
- Global aggregation over preloaded Arrow batches (this also flows into the 1BRC benchmark in `11-onebrc-arrow-aggregation-benchmark.md`).

Required dims per `BENCHMARKS.md §Wrapper benchmarks` plus 0% / 10% / 50% / 100% null profiles to exercise the all-null fast-skip.

## Non-goals

- Grouped aggregation engine (hash table + multi-group state). Foundation defers; this spdd does not introduce it.
- Aggregate state framework (record / class hierarchy). Use primitive locals.
- `skip_nulls = false` and `min_count > 1` modes. Future work.

## Acceptance criteria

- At least `SumInt64` works end-to-end (raw + wrapper + dispatch).
- All-null handling proven by test (wrapper does not invoke raw kernel).
- Semantics documented (skip_nulls = true, min_count = 1, wraparound for int64, naive sum for float64, 4 ULP tolerance in float tests).
- Benchmarks run.

## Cross-references

- `CORE_DESIGN.md §Aggregation state model`.
- `AGENTS.md §Hot paths are boring and explicit` (no allocation per row).
- `BENCHMARKS.md §Aggregation and batch-operation benchmarks`.
