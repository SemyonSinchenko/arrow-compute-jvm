# SPDD Analysis: First Valid-Only Div Int32 Kernel

## Original Business Requirement
# Requirement: First Valid-Only Kernel — DivInt32

## Business requirement

Implement the first kernel that must not execute on null slots. Integer division is not safe on arbitrary null-slot data because divisor data may contain zero. This iteration also establishes the **precheck-before-loop** error contract that all future checked kernels follow.

## Scope

Create:

```text
raw/DivInt32Raw.java
wrapper/validonly/DivInt32.java
dispatch/DivideDispatch.java
```

Raw kernel lives in the **flat** `raw/` package. Wrapper lives in `wrapper/validonly/` (foundation `CORE_DESIGN.md §Package layout`).

Support `IntVector / IntVector → IntVector`.

## Semantics

Integer division is not safe on arbitrary null-slot data because divisor data may contain zero. Additionally, `Integer.MIN_VALUE / -1` traps with overflow.

### No-null path

`raw/DivInt32Raw.noNulls(MemorySegment left, MemorySegment right, MemorySegment out, int n)`:

- Per-row Java integer division `left[i] / right[i]`.
- Wrapper has already prechecked `right` (see below); the raw kernel does not re-check.
- Vector API where it helps; integer division may fall back to scalar.

### Nullable valid-only path

`raw/DivInt32Raw.validOnly(MemorySegment left, MemorySegment right, MemorySegment out, MemorySegment activeValidity, int n)`:

- Compute division only where `activeValidity[i] == 1`.
- Inactive (null) rows: do not read divisor, do not throw, do not compute. Output data is don't-care.
- For MVP this path may be scalar (no Vector API).

`activeValidity` = `left_validity & right_validity`.

## Precheck-before-loop error contract

Foundation `AGENTS.md §Error handling §Precheck-before-loop rule` and `CORE_DESIGN.md §Safe vs valid-only kernels §Precheck-before-loop for checked kernels` define the contract. Concrete behavior for `DivInt32`:

The **wrapper** prechecks **active rows** (rows where `activeValidity[i] == 1`) for:

- divisor = 0
- `(left[i] == Integer.MIN_VALUE) && (right[i] == -1)` (overflow trap pattern)

If any active row violates either condition:

- the wrapper throws `ArithmeticException` (built via `Errors.divByZero(rowIndex)` or `Errors.overflow(rowIndex)`);
- the exception message carries the row index of the **first** offender;
- the compute loop never runs;
- `out` is not partially written;
- `setValueCount(n)` is not called.

Inactive null rows are **never** inspected and **never** trigger throws. They produce null output regardless of underlying bytes.

Precheck happens **before** the try-with-resources that creates `MemorySegment` views — or inside it but before the raw call — implementer's choice; correctness requirement is "no compute begins before precheck passes."

No per-row `try` / `throw` in the hot loop.

## Wrapper behavior

`DivInt32.eval(IntVector left, IntVector right, IntVector out)`:

1. `int n = Checks.sameValueCount(left, right);`
2. `Checks.outputCapacity(out, n);`
3. `Checks.zeroSliceOffset(left, right);`
4. `try (var refs = BufferRefs.retain(left, right, out)) { ... }`.
5. Build `activeValidity` segment:
   - If both inputs have `nullCount == 0`, `activeValidity` is conceptually all-ones; the wrapper uses the no-null code path.
   - Otherwise compute `activeValidity = left_validity & right_validity` (word-wise via `Bitmap.and` or `Validity.propagateBinary`).
6. **Precheck** `right` over active rows (and the `MIN_VALUE / -1` trap pattern). Throw on first offender.
7. Write `out_validity = activeValidity`.
8. Call the raw kernel:
   - no-null path: `DivInt32Raw.noNulls(leftData, rightData, outData, n);`
   - valid-only path: `DivInt32Raw.validOnly(leftData, rightData, outData, activeValidityData, n);`
9. `out.setValueCount(n);`.

## Output validity

```text
out_validity = left_validity & right_validity
```

Output data on null lanes is don't-care.

## Tests

Raw tests without Arrow (`Arena.ofConfined()` fixtures):

- normal division (positive / positive, negative / positive, etc.);
- zero divisor present in no-null path → expected to be caught by **wrapper** precheck; raw kernel test exercises this by passing pre-validated buffers (i.e., raw kernel tests do not test the precheck — that's a wrapper test);
- scalar tails;
- negatives, boundary values (`Integer.MIN_VALUE`, `Integer.MAX_VALUE`);
- `MIN_VALUE / -1` trap pattern (same comment — wrapper-tested);
- separate input/output segments.

Wrapper tests with Arrow (`-Darrow.memory.debug.allocator=true`):

- **Inactive null divisor with zero data does not throw** (precheck skips null rows);
- **Active divisor zero throws `ArithmeticException` with the first-offender row index** before the loop;
- **`MIN_VALUE / -1` on an active row throws `ArithmeticException` with row index** before the loop;
- **Output is not partially written when precheck throws** (use a writable-but-not-readable canary in `out`, or assert `getValueCount()` was not bumped);
- All-valid normal division;
- Sparse-null divisor where all active rows are non-zero → succeeds, output validity correct;
- Dense-null divisor where some null rows contain zero data → succeeds (null rows skipped);
- Output value count set after success.

## Benchmarks

- No-null checked path: raw vs naive Java;
- Nullable valid-only path: wrapper end-to-end;
- (Optional) naive Java baseline for valid-only.

Required dims per `BENCHMARKS.md §Wrapper benchmarks`. Null profiles: 0%, 1%, 10%, 30% for the valid-only path.

## Non-goals

- All-division-type suite (DivInt64, DivFloat64 — float division is null-safe, handled separately).
- Vector API optimization for the valid-only path beyond what's trivial.
- Modulo (Rem) is a sibling iteration, not this one.

## Acceptance criteria

- `Compute.divide(left, right, out)` works for int32 vectors.
- Wrapper tests prove the precheck-before-loop contract holds (no partial writes on failure, no per-row throws in hot loop, inactive nulls never trigger throws).
- Nullable semantics differ correctly from safe kernels.
- Tests prove no division happens for inactive null rows (zero divisor in a null row produces null output, not an exception).
- The precheck contract is concrete enough that the next checked kernel (e.g., a future `AddInt32Checked`) reuses it without re-deriving.

## Cross-references

- `CORE_DESIGN.md §Safe vs valid-only kernels §Precheck-before-loop for checked kernels`.
- `AGENTS.md §Error handling §Precheck-before-loop rule`, §Null handling modes.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Safe Arithmetic Wrapper Pattern: Existing wrappers (`AddInt32`, `MulInt32`) already establish the wrapper boundary pattern of prechecks, buffer retain/release, validity handling, raw invocation, and success-only `setValueCount`.
- Raw Fixed-Width Kernel Pattern: Existing raw kernels (`AddInt32Raw`, `MulInt32Raw`) establish static, Arrow-free `MemorySegment` compute loops with scalar tails and little-endian primitive access.
- Explicit Public Dispatch Surface: Existing dispatch classes (`AddDispatch`, `MulDispatch`) are public and use explicit `instanceof` routing plus `Errors.unsupported(...)` fallthrough.
- Public Compute Facade: `Compute` is an intentionally thin API layer that delegates operations to dispatch classes.
- Validity and Bitmap Utilities: `Validity.propagateBinary(...)` and `Bitmap.and(...)` already encode the project null-propagation rule (`left & right`) and LSB-first bitmap semantics.
- Error Taxonomy and Messaging: `Errors` already provides unchecked error factories including row-indexed arithmetic errors (`divByZero`, `overflow`) aligned with project exception policy.
- Memory Lifetime Guardrails: `BufferRefs` + `SegmentViews` centralize Arrow buffer lifetime and `MemorySegment` view creation constraints.

#### New Concepts Required
- Valid-Only Integer Division Operation: A first-class division operation where compute execution is constrained to active (valid) rows only.
- Active-Row Precheck Contract as Operational Primitive: A reusable business-level contract that checked kernels must pre-validate active rows for domain traps before any compute starts.
- Division-Specific Domain Trap Set: The checked error domain for int32 division (zero divisor and `MIN_VALUE / -1`) becomes explicit operation policy.
- Divide Dispatch Vertical Slice: Public type-routing entry for divide, analogous to existing add/mul dispatch surfaces, enabling extension without registry machinery.
- Wrapper/Raw Split for Valid-Only Path: Distinct no-null and valid-only raw execution modes selected by wrapper runtime null profile.

#### Key Business Rules
- Active-row-only risk evaluation: domain-error checks apply only to rows where both inputs are valid.
- Precheck-before-compute invariant: failure must occur before compute begins, preserving all-or-nothing output semantics for checked operations.
- Null-lane non-observability boundary: inactive rows must not influence exceptions or business outcomes, regardless of underlying bytes.
- Validity ownership rule: output validity is always derived from input validity intersection for binary nullable division.
- Reusable checked-kernel governance: this iteration defines a template intended to be reused by future checked kernels without reinterpretation.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Implement divide as a new end-to-end compute vertical slice that mirrors current architecture (`Compute` facade -> public dispatch -> Arrow-aware wrapper -> raw kernel), while introducing the project's first valid-only execution behavior.
- Keep checked semantics centralized in wrapper orchestration (input checks, active validity creation, precheck, output validity ownership), preserving raw kernels as assumption-based execution engines.
- Preserve existing conventions from current code (static/final kernel style, explicit dispatch, memory utility reuse, unchecked exception taxonomy) so divide integrates as a natural extension rather than a special subsystem.

#### Key Design Decisions
- Checked policy location: wrapper-level precheck vs raw-level defensive checks; wrapper precheck adds a dedicated validation pass but preserves hot-loop simplicity and no-partial-write guarantees -> recommend wrapper precheck.
- Nullable strategy: compute-all (safe style) vs valid-only execution; valid-only is less SIMD-friendly but required to avoid null-lane domain faults -> recommend valid-only for integer divide.
- Active validity construction ownership: reuse existing validity/bitmap helpers vs ad-hoc bit logic in wrapper; helper reuse may be slightly less bespoke but reduces bitmap polarity/tail-bit defect risk -> recommend helper-driven validity construction.
- Dispatch style: explicit public `instanceof` routing vs generic registry abstraction; explicit routing is more manual but matches current extension surface and MVP simplicity goals -> recommend explicit public dispatch.
- Exception contract strictness: standardized row-index-first offender semantics vs generic arithmetic failures; stricter semantics require deterministic precheck ordering but improve debuggability and reuse -> recommend strict row-indexed contract.

#### Alternatives Considered
- Keep divide in existing safe-wrapper package and behavior: rejected because it violates valid-only semantics required for null-lane safety.
- Collapse no-null and nullable handling into one always-branching raw method: rejected because it blurs mode intent and weakens maintainability of checked-kernel patterns.
- Rely on Java division exceptions during compute instead of explicit precheck: rejected because it can produce partial writes, per-row throw behavior, and nondeterministic failure ordering.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Precheck placement boundary: requirement allows precheck either before or inside retain scope, but this changes memory-lifetime behavior and test observability.
- First-offender determinism in mixed error rows: requirement mandates first offender but does not define precedence if divide-by-zero and overflow appear in different rows of the same batch beyond row order.
- Output-state proof when failing early: requirement states no partial writes and no value-count bump, but does not prescribe a single canonical assertion strategy.
- Benchmark obligation strength: benchmarks are requested with required dimensions, but no acceptance threshold is defined for overhead/regression.

#### Edge Cases
- Empty inputs: operation must preserve contract (no throw, deterministic output state) even with zero rows.
- Dense null profiles with trap bytes in null lanes: must confirm no accidental inspection of inactive divisors.
- Tail-bit and non-multiple batch sizes: active validity and valid-only scanning must remain correct for partial final words/bytes.
- Mixed null/no-null runtime profiles: branch behavior must preserve identical business semantics across fast no-null and valid-only modes.
- Boundary arithmetic inputs near `Integer.MIN_VALUE` and `Integer.MAX_VALUE`: ensures precheck and compute behavior remain coherent at limits.

#### Technical Risks
- Contract drift risk between docs and implementation: divide introduces the first checked/valid-only path; any deviation could become a flawed template for later checked kernels.
- Bitmap interpretation risk: LSB-first polarity mistakes in active-row gating can silently trigger false exceptions or missed traps; mitigation direction is strong wrapper tests across sparse/dense null patterns.
- Failure atomicity risk: if output validity/data is touched before precheck passes, all-or-nothing semantics can be violated; mitigation direction is strict orchestration ordering.
- Performance risk of additional precheck pass: checked semantics add a full active-row scan; mitigation direction is explicit no-null and valid-only path separation plus benchmark visibility.
- Memory-safety risk in new wrapper path: retain/segment misuse can leak or dereference invalid buffers; mitigation direction is strict reuse of existing `BufferRefs`/`SegmentViews` pattern and allocator-debug test execution.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `Compute.divide(left, right, out)` works for int32 vectors. | Yes | Fully aligned with existing `Compute` -> dispatch -> wrapper pattern already used by add/mul. |
| 2 | Wrapper tests prove precheck-before-loop contract (no partial writes, no per-row throws, inactive nulls never throw). | Yes | Addressable with dedicated wrapper tests; ambiguity remains only in preferred assertion mechanism for "no partial writes". |
| 3 | Nullable semantics differ correctly from safe kernels. | Yes | Valid-only concept is explicitly differentiated in architecture docs and can be expressed as separate wrapper path behavior. |
| 4 | Tests prove no division for inactive null rows. | Yes | Addressable by null-lane-zero scenarios where active rows are safe; must ensure tests cover both sparse and dense null distributions. |
| 5 | Precheck contract is reusable by next checked kernel without re-deriving. | Partial | Direction is clear and documented, but true reuse depends on codifying shared precheck pattern conventions beyond this single operation. |
