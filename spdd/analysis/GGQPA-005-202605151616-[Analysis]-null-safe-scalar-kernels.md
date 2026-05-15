# SPDD Analysis: Basic Null-Safe Scalar Kernels

## Original Business Requirement
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

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Raw Kernel Concept: `compute/raw` already contains the canonical primitive kernel pattern (`AddInt32Raw`) with Vector API loop + scalar tail, little-endian layouts, and no null/Arrow coupling.
- Safe Wrapper Concept: `compute/wrapper/safe` already defines the null-safe wrapper orchestration model (`AddInt32`) that validates shape/capacity/slice boundaries, retains buffers, branches by runtime null counts, and computes all rows.
- Dispatch Surface Concept: `compute/dispatch/AddDispatch` already provides explicit public type-routing with unsupported fallback via `Errors.unsupported(...)`.
- Validity Propagation Concept: `memory/Validity` already centralizes `markAllValid(...)` and binary validity propagation (`left & right`) for safe binary wrappers.
- Layer-Specific Verification Concept: existing tests are split by layer (`raw`, `wrapper/safe`, `dispatch`) and existing JMH benchmarks already compare raw vs wrapper vs `Compute` dispatch path.

#### New Concepts Required
- Null-Safe Kernel Family Expansion: add a grouped family of safe arithmetic kernels (at minimum AddInt64, AddFloat64, MulInt32, MulFloat64) using the established raw/wrapper/dispatch pattern.
- Multiplication Dispatch Surface: introduce or extend public dispatch for multiply so typed routing remains explicit and extension-friendly, mirroring add behavior.
- Float Bit-Exactness Verification Contract: add an explicit correctness contract for float elementwise kernels where output equality is exact against scalar Java reference, not ULP-based.
- Benchmark Matrix Completeness for New Family: ensure at least one integer and one floating kernel in this new family have full baseline-question alignment (raw vs naive, wrapper vs raw, dispatch vs wrapper) with required null-profile dimensions.

#### Key Business Rules
- Safe-On-Null Compute Rule: for safe kernels, data is computed on all rows while observability is controlled only by validity propagation.
- Runtime Null-Path Rule: wrappers must branch by runtime null presence (`getNullCount()`), not schema-level nullability declarations.
- Layer Responsibility Rule: raw layer stays Arrow-free/null-free, wrapper handles lifecycle + validity, dispatch handles type routing, and public facade delegates.
- Naming and Packaging Rule: raw class naming and flat `raw/` location are part of the architecture contract for scalability and grepability.
- Scope Control Rule: must-have kernel set is mandatory; optional kernels are conditional on keeping the change set bounded.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Follow a template-expansion strategy: replicate the proven `AddInt32` vertical slice pattern into the required operation/type set, preserving strict layer boundaries and existing utility reuse.
- Keep data flow consistent with current architecture: `Compute facade` -> `public explicit dispatch` -> `safe wrapper null/lifetime orchestration` -> `raw Vector API kernel`.
- Sequence work in small slices by operation family (add then multiply, or vice versa) to preserve diff size control while still meeting the four-kernel minimum.

#### Key Design Decisions
- Kernel breadth now vs phased breadth: implementing only the four must-haves minimizes risk and review load but delays optional coverage -> recommend must-haves first, optional types only if tests/benchmarks remain manageable.
- Reuse one dispatch per operation vs per-type entrypoints: operation-level dispatch keeps API stable and routing centralized but increases branch count -> recommend operation-level dispatch with explicit branches to preserve current conventions.
- Strict bit-exact float assertions vs tolerance: bit-exact catches subtle semantic drift but can be brittle if operation order changes -> recommend bit-exact because elementwise order is fixed and requirement explicitly mandates it.
- Benchmark completeness vs development speed: full dimension matrix increases confidence but adds runtime/authoring cost -> recommend meeting required dimensions for selected integer+float kernels and deferring broader matrix expansion to later iterations.

#### Alternatives Considered
- Introduce a generic kernel registry for add/mul expansion: rejected because it conflicts with current MVP non-goals and would dilute explicit dispatch clarity.
- Implement valid-only and checked variants in the same pass: rejected because requirement marks both as non-goals for this iteration.
- Expand optional kernels first for broader type coverage: rejected because acceptance criteria prioritize minimum mandatory set with controlled diff size.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Benchmark seed interpretation: requirement mandates `0xC0FFEEL`, while current benchmarks use a different seed style, so compatibility expectation (replace vs new-benchmark-only) is unspecified.
- Dispatch class scope for multiplication/comparison: requirement lists `dispatch/<Op>Dispatch.java` pattern but does not clarify whether `Compute` facade must expose new operations in this iteration.
- Optional kernel prioritization: optional list mixes arithmetic and comparison kernels, but no ordering guidance is provided if partial optional coverage is chosen.

#### Edge Cases
- Zero-length and tiny row counts across new types: wrappers must still set output state correctly and raw kernels must preserve scalar-tail correctness at boundary sizes.
- All-null profiles with compute-all semantics: output values in null lanes are don't-care, so tests must avoid asserting null-slot bytes while still validating output validity and count.
- Floating special values in nullable paths: NaN/Infinity behavior must remain IEEE-consistent even when null propagation is handled separately.
- Integer overflow in unchecked kernels: wraparound must remain intentional and consistently asserted for int32/int64 additions/multiplications.

#### Technical Risks
- Copy-paste divergence risk across many similar kernels: inconsistent naming, missing slice checks, or wrong validity branch can slip in; mitigation direction is strict pattern reuse and layer-specific test parity across kernels.
- Dispatch drift risk: adding new branches may accidentally weaken unsupported-combination behavior or error clarity; mitigation direction is explicit unsupported tests per dispatch class.
- Benchmark interpretability risk: if benchmark labels and baseline questions are inconsistent, AC #3 can appear met while results remain hard to interpret; mitigation direction is aligning benchmark method naming and report framing with `BENCHMARKS.md` baseline matrix.
- Architecture overreach risk: trying to include too many optional kernels may inflate changes and reduce quality; mitigation direction is enforce scope gate at four mandatory kernels.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | At least four null-safe kernels implemented (the must-haves above). | Yes | Fully addressable via direct extension of existing raw/wrapper/dispatch pattern currently proven by AddInt32 path. |
| 2 | Tests remain layer-specific. | Yes | Existing project test structure already separates raw, wrapper, and dispatch tests; new kernels can follow same layering. |
| 3 | Benchmarks run and produce interpretable results with the baseline matrix labels from `BENCHMARKS.md §Benchmark goals`. | Partial | Existing benchmark scaffolding exists, but current seed usage and coverage for floating kernels do not yet prove this AC for the new kernel set; benchmark question-label alignment must be validated during REASONS Canvas. |
