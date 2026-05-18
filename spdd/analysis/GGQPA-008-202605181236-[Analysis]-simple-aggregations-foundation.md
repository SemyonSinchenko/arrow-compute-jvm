# SPDD Analysis: Simple Aggregations Foundation

## Original Business Requirement
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

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Raw Kernel: vectorized primitive compute unit over `MemorySegment` buffers, already used for add/mul/div and suitable for aggregate hot loops — consumed by Arrow-aware wrappers.
- Wrapper Kernel: Arrow-boundary safety and semantics layer (checks, retain/release, validity management, value-count finalization) — orchestrates null behavior and raw invocation.
- Dispatch Surface: explicit type-based routing (`AddDispatch`, `DivideDispatch`) from public API to wrapper implementations — extension point for new operations.
- Compute Facade: stable public API entrypoint (`Compute`) — currently exposes arithmetic operations and is the expected place for aggregate API exposure.
- Validity/Bitmap Utilities: shared null-propagation and bitmap operations (`Validity`, `Bitmap`) — reusable for skip-null aggregate scanning patterns.
- Memory Lifetime Guardrails: `BufferRefs`, `SegmentViews`, `Checks`, `Errors` enforce Arrow buffer lifetime, shape checks, and error semantics — required boundary for any new wrapper.
- Benchmark/Test Harness: JMH setup and raw+wrapper+dispatch test layering already established — provides standard verification path for new kernels.

#### New Concepts Required
- Scalar Aggregation Wrapper: operation producing one-row output from N-row input with Arrow null result semantics — bridges primitive aggregate result to nullable Arrow scalar row.
- Aggregate Dispatch Surface: explicit routing for aggregate operations by input/output vector types — integrates aggregate wrappers into public compute API.
- Skip-Null Aggregate Semantics Contract: clear operation-level semantics (`skip_nulls=true`, `min_count=1`) for MVP — governs wrapper behavior for all-null and mixed-null inputs.
- Ungrouped Aggregate State: primitive-local accumulator/seen-state model for array-to-scalar execution — aligns with future grouped SoA model without introducing grouped engine now.

#### Key Business Rules
- Valid Rows Only Aggregation: raw aggregate computes over valid rows and never owns null-result decisions — governs raw kernel and wrapper contract boundary.
- All-Null Produces Null Result: when input null count equals value count, wrapper must emit null scalar and skip raw call — governs wrapper control flow and correctness.
- Primitive Overflow/FP Policy Is Explicit: int64 sum uses Java wraparound; float64 sum is naive order-dependent arithmetic with bounded ULP tolerance in tests — governs correctness expectation and documentation.
- Output Shape Is Scalar Row: aggregate wrapper always sets output value count to 1 and validity for row 0 according to semantics — governs API consistency and downstream consumption.
- MVP Scope Boundary: grouped aggregation engine and advanced null/min_count modes are excluded — governs delivery scope and prevents premature abstraction.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Extend the existing execution-layer pattern (Compute facade -> explicit dispatch -> Arrow-aware wrapper -> raw kernel) with an aggregation lane for array-to-scalar operations, while preserving current safety/lifetime conventions and benchmark-first discipline.

#### Key Design Decisions
- Separate raw compute from null-result semantics: keeps hot path primitive and branch-light in raw layer, with semantic branching in wrapper -> recommended because this matches current architecture and avoids encoding nullable scalar in raw return types.
- Introduce aggregate routing as explicit dispatch rather than ad hoc direct calls: slightly more boilerplate, but preserves external extension pattern and public consistency -> recommended for long-term API coherence.
- Treat aggregation wrapper as scalar-output special case in existing wrapper model: adds one new output-shape branch, but reuses `Checks`, `BufferRefs`, `SegmentViews`, error conventions -> recommended to minimize new infrastructure.
- Keep MVP semantics fixed (`skip_nulls=true`, `min_count=1`) with documented policy rather than early configuration: reduces flexibility now, but avoids option-surface churn and preserves simple contracts -> recommended for first aggregate milestone.
- Stage optional float/min/max aggregates after `SumInt64` path lands: slower feature breadth, but reduces risk and allows proving scalar-output design first -> recommended based on explicit scope wording and current code maturity.

#### Alternatives Considered
- Make raw kernel return nullable/boxed result object: rejected due to hot-path object overhead and mismatch with project rule of primitive locals/static kernels.
- Bypass dispatch and add wrapper-only public method: rejected because it fragments API and diverges from established explicit dispatch pattern.
- Introduce generic aggregation state framework now: rejected because requirement and core design explicitly defer grouped engine/framework and prioritize primitive-local MVP simplicity.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Dispatch placement ambiguity: requirement AC says end-to-end includes dispatch, but does not explicitly name whether this is a new `AggregateDispatch` and/or `Compute.sum` public method.
- Empty-input interpretation: raw tests define `noNulls(n=0)=0`, but wrapper-level expected output validity for empty non-null-count input is not explicitly stated.
- Optional scope boundary: `SumFloat64`/`MinFloat64`/`MaxFloat64` are optional, but acceptance language references float semantics documentation, creating potential expectation drift.
- All-null proof mechanism: requirement allows counter or spy approach, but does not constrain preferred testing strategy, which may affect maintainability/readability.

#### Edge Cases
- Zero-row input with nullable semantics: wrapper must distinguish empty vs all-null behavior consistently to avoid contradictory scalar outputs.
- 100% null profile benchmark path: must represent all-null fast-skip without accidentally invoking raw compute in wrapper benchmark method.
- Overflow-heavy datasets: wraparound behavior must remain documented and stable even when intermediate sums cross boundaries many times.
- Tail-size batches: aggregate correctness for row counts around vector species boundaries still matters despite scalar output.

#### Technical Risks
- Scalar-output wrapper mismatch risk: existing wrappers are mostly vector-to-vector; introducing vector-to-scalar output can accidentally violate value-count or validity conventions -> mitigate by codifying dedicated wrapper tests for output row 0 state.
- API surface drift risk: adding aggregate support without coherent dispatch/facade update could create inconsistent external entry points -> mitigate by aligning with current dispatch conventions before expanding optional aggregates.
- Null-path correctness risk: all-null fast path must bypass raw kernel deterministically -> mitigate with explicit non-invocation tests and null-count branch assertions.
- Benchmark interpretation risk: aggregation benchmarks can conflate compute with setup/output handling -> mitigate by following `BENCHMARKS.md` layer questions and preloaded/preallocated measurement boundaries.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | At least `SumInt64` works end-to-end (raw + wrapper + dispatch). | Yes | Requires explicit aggregate dispatch/API decision to avoid partial integration. |
| 2 | All-null handling proven by test (wrapper does not invoke raw kernel). | Yes | Test strategy choice (counter vs spy) remains open but feasible within existing test patterns. |
| 3 | Semantics documented (skip_nulls = true, min_count = 1, wraparound for int64, naive sum for float64, 4 ULP tolerance in float tests). | Partial | Int64 parts are directly in-scope; float semantics may be documentation-only unless optional float kernels are implemented now. |
| 4 | Benchmarks run. | Yes | Needs dedicated aggregation benchmark classes aligned with wrapper/raw/macro benchmark conventions. |
