# SPDD Analysis: Bitmap and Boolean Output Support

## Original Business Requirement
# Requirement: Bitmap and Boolean Output Support

## Business requirement

Support Arrow-style bit-packed boolean output for comparison kernels and validity operations.

## Scope

Implement or refine bitmap support and comparison output support.

## Arrow Java first

Use `BitVectorHelper` (foundation `ARROW_JAVA_API_USAGE.md §5`) for:

- validity buffer sizing (`getValidityBufferSize`);
- scalar get/set in non-hot code (`get`, `setBit`, `unsetBit`, `setValidityBit`);
- null count checks (`getNullCount`, `checkAllBitsEqualTo`);
- test helpers.

Use project `Bitmap` only for:

- word-wise operations on raw `MemorySegment` buffers (AND, OR, AND_NOT, NOT);
- hot loops where per-call Arrow Java overhead matters.

## Required behavior

Comparison kernels (when added later — e.g., `GreaterInt32`) write Arrow-compatible boolean output. Account for:

- bit-packed value buffer (1 bit per row, LSB-first within a byte);
- bit-packed output validity buffer (separate from value buffer);
- tail bits in the last byte for non-multiple-of-8 row counts;
- non-multiple-of-64 row counts (last word in word-wise writes);
- correct interaction with `BitVector.setValueCount(n)` — Arrow Java's `setValueCount` may clear or set tail bits. Wrappers either:
  (a) call `setValueCount(n)` **before** the kernel writes data and then make tail-aware writes, OR
  (b) call `setValueCount(n)` **after** the kernel writes data, accepting that Arrow Java may clear tail bits beyond row `n-1` (which is exactly what we want for indices outside the valid range).

The implementation picks one approach and documents it in the wrapper class javadoc. Pattern (b) is usually safer; the kernel writes the full last word, and `setValueCount(n)` cleans up.

## Tests

Run with `-Darrow.memory.debug.allocator=true`. Cover:

- all-false output;
- all-true output;
- alternating values;
- random values (fixed seed `0xC0FFEEL`);
- row counts not divisible by 8 (tail-bit correctness);
- row counts not divisible by 64 (word-wise tail correctness);
- null propagation through binary bitmap ops;
- `BitVector.validateFull()` on the output;
- intentional tail-bit corruption regression test (e.g., write an out-of-range bit, assert `validateFull()` catches it).

## Benchmarks

- Bitmap AND (project `Bitmap.and`) vs scalar Java loop;
- Boolean output packing (e.g., a hypothetical `GreaterInt32Raw` that produces bit-packed output) vs naive Java byte-per-bool then pack;
- Comparison raw kernel vs naive Java (when comparison kernels land — this iteration may stub the comparison or defer to a follow-up).

## Non-goals

- Complex boolean Kleene logic (three-valued logic for nullable booleans). Standard two-valued AND/OR/NOT only.
- Filter / take kernels (consumer of bit-packed booleans; out of scope here).
- Comparison kernels themselves — they may land in this iteration if scope permits, or in a follow-up.

## Acceptance criteria

- Boolean output matches Arrow layout (LSB-first within byte, validity in a separate bit buffer).
- Tail bits are correct for non-multiple-of-8 and non-multiple-of-64 row counts.
- `BitVector.validateFull()` passes on all wrapper outputs.
- `Bitmap` operations have unit tests covering AND/OR/AND_NOT/NOT and tail-bit edges.

## Cross-references

- `CORE_DESIGN.md §Bitmap`, §Validity bitmap rules.
- `AGENTS.md §Validity bitmap rules`, §Boolean output.
- `ARROW_JAVA_API_USAGE.md §5 BitVectorHelper`.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Bitmap Operation Kernel (`Bitmap`): low-level bitwise validity operations over `MemorySegment` with explicit tail masking — provides the foundation for word-wise validity propagation and bit integrity.
- Validity Propagation Service (`Validity`): wrapper-level orchestration that applies null rules (`markAllValid`, unary copy, binary AND) and bridges Arrow vectors to bitmap operations.
- Wrapper-Orchestrated Lifecycle Control (`BufferRefs` + `SegmentViews`): explicit retain/release and bounded segment-view creation that define memory safety boundaries for bitmap/value writes.
- Runtime Null-Mode Branching (safe wrappers such as `AddInt32`): established pattern where wrappers branch on runtime null counts, compute data, then finalize vector state via `setValueCount`.
- Explicit Dispatch Surface (`AddDispatch`, `MulDispatch`, `Compute`): public operation routing model that currently covers arithmetic but not comparison/boolean-producing operations.

#### New Concepts Required
- Boolean Output Contract for Comparisons: a comparison-result concept with two independent bitmaps (value bits and validity bits) following Arrow bit order and tail semantics.
- Tail-Bit Finalization Policy: explicit project-level decision for when wrapper calls `setValueCount(n)` relative to kernel writes, as a documented correctness boundary for out-of-range bits.
- Bitmap Verification Matrix: a broader correctness envelope that treats all bitwise ops (AND/OR/AND_NOT/NOT), non-8/non-64 tails, and validation behavior as first-class acceptance concepts.

#### Key Business Rules
- Arrow Bit Layout Rule: boolean values are bit-packed LSB-first, and validity is stored in a separate bitmap.
- Null Propagation Rule: binary validity remains `left_validity & right_validity` and is not inferred from data bytes.
- Tail Correctness Rule: bits outside `[0, n-1]` must not violate Arrow vector correctness after wrapper finalization.
- Responsibility Split Rule: `BitVectorHelper` is default for scalar/wrapper/test utilities; project `Bitmap` is reserved for word-wise/hot-path operations.
- Scope Rule: two-valued boolean bitmap behavior is in scope; Kleene logic and filter/take consumers remain out of scope.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Extend the existing wrapper/memory architecture rather than introducing a new boolean subsystem: keep Arrow vector lifecycle and null propagation in wrappers, while using `Bitmap` for word-wise operations and `BitVectorHelper` for scalar correctness checks and sizing.
- Treat boolean output support as a cross-cutting contract (layout + validity + tail policy + validation), not as a single kernel feature.
- Keep comparison-kernel delivery decoupled: enable boolean-output infrastructure now, and allow comparison kernels to land in this iteration only if scope remains controlled.

#### Key Design Decisions
- Tail cleanup timing (`setValueCount` before vs after writes): before-write gives stricter write discipline; after-write simplifies kernel logic and aligns with current wrapper pattern of finalizing count at end -> recommend after-write cleanup (pattern b) with explicit javadoc contract, because it is safer against tail-bit drift in word-wise writes.
- Bitmap API ownership boundary: moving more logic to `BitVectorHelper` improves canonical Arrow usage but may sacrifice hot-loop efficiency; expanding project `Bitmap` improves throughput but risks API duplication -> recommend strict split already documented in `ARROW_JAVA_API_USAGE.md`: Arrow helper for scalar/sizing/tests, project bitmap only for measured word-wise/hot loops.
- Delivery scope for comparisons: implementing full comparison kernels now improves end-to-end completeness but increases change surface across raw/wrapper/dispatch/tests/benchmarks -> recommend infrastructure-first with optional comparison stub/follow-up, to satisfy current requirement while controlling risk.
- Validation posture: relying on row-level value assertions alone may miss structural corruption; always requiring `validateFull()` strengthens correctness but adds test runtime -> recommend mandatory `validateFull()` for boolean-output wrappers and targeted corruption regression tests.

#### Alternatives Considered
- Implement byte-per-bool intermediate then pack later: rejected because it diverges from Arrow-native output contract and adds extra transformation overhead.
- Defer all boolean-output work until comparison kernels are fully implemented: rejected because requirement explicitly calls for bitmap/boolean output support and testable tail correctness now.
- Use only Arrow helper methods and remove project `Bitmap` operations: rejected for this phase because word-wise AND/OR/AND_NOT/NOT are already part of current low-level design and performance intent.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Comparison-kernel scope ambiguity: requirement allows comparison kernels to be deferred, but benchmark/boolean-output examples reference them; boundary between “support infra” and “deliver kernel” is not fully explicit.
- Tail corruption expectation ambiguity: requirement suggests a regression test where `validateFull()` catches corruption, but does not specify whether corruption is injected pre- or post-`setValueCount`, which materially changes expected behavior.
- Wrapper documentation target ambiguity: requirement asks wrapper javadocs to document selected tail policy, but does not specify which wrapper(s) own this contract if comparison wrappers are deferred.

#### Edge Cases
- `n=0` and very small `n` with bit-packed outputs: must preserve valid empty-vector state without stray tail writes.
- Non-multiple-of-8 and non-multiple-of-64 boundaries under all ops (AND/OR/AND_NOT/NOT): each operation can produce different high-bit patterns and needs explicit tail handling.
- All-null and mixed-null inputs for future comparison outputs: value bits in null lanes are don't-care, but validity bits must stay exact and independently verifiable.
- Full-word writes followed by `setValueCount`: requires deterministic post-finalization expectations for out-of-range bits in both value and validity buffers.

#### Technical Risks
- One-bit correctness regressions: bitmap bugs are high-impact and subtle; mitigation direction is exhaustive tail-focused tests plus `validateFull()` checks in wrapper paths.
- Inconsistent policy across wrappers: if different wrappers choose different `setValueCount` timing without clear docs, behavior drifts; mitigation direction is a single documented project convention and wrapper-level javadoc enforcement.
- Performance/correctness tension in bitmap utilities: overuse of Arrow scalar helpers in hot loops can hurt throughput, while over-expansion of custom bitmap code can duplicate semantics; mitigation direction is benchmark-gated ownership boundaries.
- Missing comparison dispatch surface: infrastructure may ship without public compare entrypoints, leaving partial usability; mitigation direction is explicitly mark comparison integration as follow-up scope when deferred.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Boolean output matches Arrow layout (LSB-first within byte, validity in a separate bit buffer). | Partial | Core layout rules are already codified in design/docs and bitmap utilities; explicit comparison-output wrapper path is not yet present in current codebase. |
| 2 | Tail bits are correct for non-multiple-of-8 and non-multiple-of-64 row counts. | Partial | `Bitmap` currently masks tail bytes and has one non-multiple-of-8 test; broader operation coverage and non-multiple-of-64/boolean-output wrapper scenarios remain to be expanded. |
| 3 | `BitVector.validateFull()` passes on all wrapper outputs. | Partial | `validateFull()` is present in smoke tests, but not yet systematically asserted for all relevant bitmap/boolean-output wrapper paths. |
| 4 | `Bitmap` operations have unit tests covering AND/OR/AND_NOT/NOT and tail-bit edges. | Partial | Current tests directly exercise `Bitmap.and` tail behavior; OR/AND_NOT/NOT and full tail-edge matrix are not yet covered. |
