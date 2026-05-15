# SPDD Analysis: First Wrapper and Dispatch for Int32 Add

## Original Business Requirement
# Requirement: First Wrapper and Dispatch — Compute.add for IntVector

## Business requirement

Implement the first end-to-end compute path from public API to Arrow-aware wrapper to raw kernel.

## Scope

Create/update:

```text
compute/Compute.java
dispatch/AddDispatch.java
wrapper/safe/AddInt32.java
```

Support `Compute.add(FieldVector left, FieldVector right, FieldVector out)` for `IntVector + IntVector → IntVector`.

`AddDispatch` is a **public** class (foundation §Dispatch surface visibility — consumers may extend it to plug in custom kernels without forking).

## Wrapper behavior

`AddInt32.eval(IntVector left, IntVector right, IntVector out)`:

- `int n = Checks.sameValueCount(left, right);`
- `Checks.outputCapacity(out, n);`
- `Checks.zeroSliceOffset(left, right);`
- `try (var refs = BufferRefs.retain(left, right, out)) { ... }` — `BufferRefs.retain` retains data + validity of all passed vectors.
- Create `MemorySegment` views via `SegmentViews.data(...)` inside the try block; segments must not escape.
- Validity:
  - If `left.getNullCount() == 0 && right.getNullCount() == 0`: `Validity.markAllValid(out, n);`
  - Else: `Validity.propagateBinary(left, right, out, n);`
- Call `AddInt32Raw.computeAll(leftData, rightData, outData, n);`.
- `out.setValueCount(n);`.

## Null behavior

Add is safe-on-null-data:

```text
out_validity = left_validity & right_validity
out_data = compute all rows
```

Use all-valid path if both inputs have `nullCount == 0`. Null-lane output data is don't-care.

## Dispatch behavior

`AddDispatch.eval(FieldVector left, FieldVector right, FieldVector out)` routes `(IntVector, IntVector, IntVector)` to `AddInt32`. Throw `Errors.unsupported("add", left, right, out)` (an `UnsupportedOperationException`) for unsupported combinations.

## Public API

`Compute.add(FieldVector left, FieldVector right, FieldVector out)` delegates to `AddDispatch.eval(...)`.

Future overloads with primitive flags (`checked`, `nanAgnostic`) are out of scope here; foundation §Options and modes is the long-term plan.

## Tests

Wrapper tests with Arrow. Run with `-Darrow.memory.debug.allocator=true`.

- All-valid;
- left has nulls, right does not;
- right has nulls, left does not;
- both have nulls (sparse 1%, dense 30%);
- all-null on both sides;
- output value count is set correctly;
- output validity matches `left & right`;
- valid output values match a `BigInteger`-free Java reference (`int a + int b`);
- input vectors are not mutated;
- unsupported dispatch type combination throws `UnsupportedOperationException`;
- non-zero slice offset on any input throws before any compute (use `TransferPair.splitAndTransfer` if needed to construct a sliced vector).

Do not assert null-slot output data.

## Benchmarks

Compare:

- `AddInt32Raw.computeAll`;
- `AddInt32.eval` (wrapper);
- `Compute.add` (dispatch).

Wrapper benchmarks include null profiles 0%, 1%, 10%, 30%. Raw benchmarks use no null profile.

## Non-goals

No all-type support. No registry. No scalar (vector + scalar) inputs.

## Acceptance criteria

- Public `Compute.add` works for int32 vectors.
- Wrapper tests pass.
- Dispatch tests pass (right routing + unsupported errors).
- Dispatch remains simple (instanceof chain), grep-friendly.
- `AddDispatch` is `public`.
- Slice-offset rejection works.

## Cross-references

- `CORE_DESIGN.md §Public API layer`, §Dispatch layer, §Arrow-aware wrapper layer, §Options and flags.
- `AGENTS.md §Options and modes`, §Dispatch surface visibility, §Default invariants.
- `ARROW_JAVA_API_USAGE.md §3 FieldVector and ValueVector APIs to prefer §Slice offsets`.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Compute Raw Kernel: `AddInt32Raw` already exists as a vectorized int32 compute primitive that consumes `MemorySegment` and assumes wrapper-enforced invariants — it is the execution core that wrapper and dispatch must expose.
- Arrow Wrapper Safety Boundary: `Checks`, `BufferRefs`, `SegmentViews`, `Validity`, and `Errors` are already in place as the lifecycle, validation, and null-propagation guardrail layer — wrappers are expected to compose these consistently.
- Public Dispatch Contract: architecture docs already define dispatch as explicit `instanceof` routing with unsupported fallthrough, and explicitly require dispatch classes to be public extension points.
- Static Public Compute Facade Pattern: `CORE_DESIGN.md` defines `Compute.add(...)` as a small static entry-point delegating to dispatch — this requirement operationalizes the first facade path.
- Null Semantics Policy for Safe Arithmetic: existing validity utilities and rules support nullable compute-all behavior (`out_validity = left & right`, compute data on all rows), matching add semantics.

#### New Concepts Required
- Add Int32 Wrapper Operation: first concrete Arrow-aware safe wrapper in `wrapper/safe` that bridges `IntVector` inputs to the existing raw kernel while preserving memory/lifetime and validity rules.
- Add Dispatch Specialization: first concrete add operation dispatcher that routes the `(IntVector, IntVector, IntVector)` triple to the wrapper and rejects non-supported combinations through standardized unsupported errors.
- Public Compute Add Surface: first public compute arithmetic call path (`Compute.add`) connecting API, dispatch, and wrapper as an end-to-end stable contract.
- Wrapper/Dispatch-Level Validation Suite: first operation-specific Arrow-backed tests validating null profiles, output validity semantics, mutation boundaries, and slice-offset rejection for the wrapper/dispatch tier.

#### Key Business Rules
- Wrapper Boundary First: shape/capacity/slice constraints are validated before retain/segment creation and before compute; raw kernel remains assumption-based.
- Lifetime Confinement: all Arrow buffer retains/releases are symmetrical and scoped; temporary segments cannot escape wrapper scope.
- Safe Nullable Add Rule: output validity must be logical AND of inputs, while output data may be computed on all rows including null lanes.
- Public Extensibility Rule: dispatch class visibility is intentionally public to allow downstream custom routing without introducing a registry.
- Deterministic Delegation Rule: `Compute.add` must be a thin delegation surface, preserving current architecture separation (API -> dispatch -> wrapper -> raw).

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Introduce the first full vertical slice for arithmetic add by adding a minimal public API facade, a public dispatch router, and one Arrow-aware safe wrapper that reuses existing memory and validity utilities and the already-built raw kernel.
- Preserve established layering from `CORE_DESIGN.md`: `Compute.add(...)` performs no compute, dispatch performs type routing only, wrapper performs validation/lifetime/validity orchestration, raw kernel performs tight data-path arithmetic.
- Keep scope deliberately narrow to int32 vector-vector path to establish a reusable operational template for later primitive expansions in iteration 05.

#### Key Design Decisions
- Public dispatch class vs package-private dispatch: public increases extension surface but aligns with documented pluggability goals -> recommend public now to avoid later breaking visibility changes.
- Single explicit `instanceof` chain vs abstraction/registry: explicit chain is verbose but grep-friendly and low ceremony -> recommend explicit chain to match MVP non-goals and current conventions.
- Wrapper computes validity then always calls raw kernel vs conditional compute skipping null rows: always-calculate is simpler and fast for safe ops but leaves null-lane data undefined -> recommend always-calculate because add is safe-on-null-data and validity already governs observability.
- Immediate narrow type support vs broader multi-type rollout: narrow support limits immediate coverage but reduces coupling and risk for first end-to-end path -> recommend narrow int32-only slice to validate architecture before scaling.

#### Alternatives Considered
- Route `Compute.add` directly to wrapper without dispatch: rejected because it bypasses the intentional dispatch extension surface and weakens future type-routing consistency.
- Introduce generic operation registry now: rejected as premature infrastructure explicitly out of scope in requirement and project non-goals.
- Add checked/nan mode flags in first path: rejected because options are explicitly out of this requirement and would blur the first vertical-slice objective.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Dispatch error message contract detail: exception type is specified, but message strictness (exact class-name format) is not fully constrained and may cause test brittleness if over-specified.
- Output vector type mismatch behavior nuance: requirement states unsupported combinations should throw, but does not state whether mismatch checks should be branch-ordered for diagnostic consistency.
- Benchmark acceptance threshold: benchmark comparisons are requested, but no explicit performance target is defined for wrapper/dispatch overhead envelope.

#### Edge Cases
- Empty vectors and zero-row outputs: wrapper should still enforce capacity/lifetime/validity behavior without corrupting output state.
- All-null and mixed-null profiles: validity correctness must hold independently of data bytes in null lanes, especially with sparse and dense null distributions.
- Sliced input rejection timing: requirement mandates rejection before compute, so ordering with retain/segment creation is a correctness boundary.
- Unsupported vector combinations including non-int outputs: must fail deterministically via unsupported dispatch path instead of partial execution.

#### Technical Risks
- Lifetime misuse risk in new wrapper code: retaining and segment creation ordering errors can leak or expose invalid memory; mitigation is strict reuse of existing `BufferRefs`/`SegmentViews` pattern and allocator-debug tests.
- Null bitmap correctness risk: incorrect AND propagation or value-count handling can silently corrupt null semantics; mitigation is explicit null-profile tests and validity assertions.
- Contract drift between docs and implementation: dispatch visibility and simple `instanceof` structure are architectural commitments; mitigation is keeping class shape minimal and grep-friendly as required.
- Benchmark interpretation risk: wrapper and dispatch benches can be dominated by setup artifacts if fixtures are not controlled; mitigation direction is isolating measured region and preserving shared inputs across variants.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Public `Compute.add` works for int32 vectors. | Yes | Directly covered by introducing facade -> dispatch -> wrapper route for `(IntVector, IntVector, IntVector)`. |
| 2 | Wrapper tests pass. | Yes | Existing Arrow/JUnit setup and memory utilities already support required wrapper test scenarios. |
| 3 | Dispatch tests pass (right routing + unsupported errors). | Yes | Fully addressable with focused routing and unsupported-combination tests. |
| 4 | Dispatch remains simple (instanceof chain), grep-friendly. | Yes | Aligns with `CORE_DESIGN.md` dispatch guidance and current MVP style. |
| 5 | `AddDispatch` is `public`. | Yes | Pure class visibility requirement, straightforward to satisfy. |
| 6 | Slice-offset rejection works. | Yes | `Checks.zeroSliceOffset(...)` utility already exists and has baseline tests; op-specific wrapper tests can verify integration path. |
