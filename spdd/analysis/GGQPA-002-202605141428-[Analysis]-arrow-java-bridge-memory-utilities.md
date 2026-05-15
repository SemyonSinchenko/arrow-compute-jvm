# SPDD Analysis: Arrow Java Bridge and Memory Utilities

## Original Business Requirement
# Requirement: Arrow Java Bridge and Memory Utilities

## Business requirement

Create minimal non-hot infrastructure that safely bridges Arrow Java vectors to `MemorySegment` views used by raw kernels.

## Scope

Implement:

```text
memory/SegmentViews.java
memory/BufferRefs.java
memory/Checks.java
memory/Errors.java
memory/Validity.java
memory/Bitmap.java
```

## Responsibilities

`SegmentViews`: checked `ArrowBuf` / `FieldVector` address + byte size → `MemorySegment` view via two-arg `MemorySegment.ofAddress(addr).reinterpret(byteSize)`. Centralizes the dangerous `ofAddress` call so raw kernels never call it. See `CORE_DESIGN.md §SegmentViews §Lifetime invariant` — segments must never escape the wrapper's try-with-resources.

`BufferRefs`: retain/release buffers used by wrappers. Single entry point:

```java
try (var refs = BufferRefs.retain(left, right, out)) {
    // segment views created here
}
```

`BufferRefs.retain(...)` retains **both data and validity buffers** of every passed vector. There is no separate `retainData`/`retainValidity` split. Closing the `BufferRefs` releases everything that was retained.

`Checks`: small validation helpers used at wrapper boundary:

- `Checks.sameValueCount(left, right)` — returns the shared `n` or throws `IllegalArgumentException`.
- `Checks.outputCapacity(out, n)` — throws `IllegalArgumentException` if the output cannot hold `n` rows. Allocation policy stays with the caller; this method never allocates.
- `Checks.zeroSliceOffset(...vectors)` — rejects vectors with non-zero slice offset (MVP invariant per foundation). Concrete API choice (per-vector method vs Arrow internals) decided at implementation time; whatever the implementation, the public-facing call is `Checks.zeroSliceOffset(...)`.
- `Checks.matchingDecimalPrecisionScale(...)` (added when `14-slow-tier-decimal128-add.md` lands; can be stubbed here or added in 14).

`Errors`: exception factory. All `Errors.*` helpers return / throw **unchecked** exceptions:

- `IllegalArgumentException` for size, shape, slice offset, precision/scale mismatch.
- `UnsupportedOperationException` for unsupported type combinations at dispatch.
- `ArithmeticException` for domain errors (`Errors.divByZero(rowIndex)`, `Errors.overflow(rowIndex)`).

No checked exceptions on hot or cold paths.

`Validity`: high-level validity propagation:

- `Validity.markAllValid(out, n)` — when both inputs have `getNullCount() == 0`.
- `Validity.propagateUnary(input, out, n)` — copy input validity to output.
- `Validity.propagateBinary(left, right, out, n)` — `left_validity & right_validity → out_validity`. Word-wise where possible.

`Bitmap`: low-level word-wise bitmap operations needed by kernels and `Validity`. Examples:

- `Bitmap.and(leftBitmap, rightBitmap, outBitmap, n)` (word-wise).
- `Bitmap.or(...)`, `Bitmap.andNot(...)`, `Bitmap.not(input, out, n)`.
- `Bitmap.countSetBits(bitmap, n)` if not satisfied by `BitVectorHelper.getNullCount`.

`Bitmap` must **not** duplicate `BitVectorHelper` methods unless the duplicate exists for a raw `MemorySegment` hot path or a measured perf win.

## Arrow Java API first

Use Arrow Java utilities where they already fit (see `ARROW_JAVA_API_USAGE.md`):

- `ValueVector` / `FieldVector` lifecycle APIs (§3);
- `BitVectorHelper` for scalar bit work, validity sizing, tail handling (§5);
- `TransferPair` and `copyFromSafe` for non-hot copies (§6);
- `validate` / `validateFull` in tests (§7);
- Arrow Java POJO types (`Schema`, `Field`, `FieldType`, `ArrowType`) (§9);
- Arrow Java `algorithm` module (§8).

Do not duplicate Arrow Java helpers unless this project needs raw `MemorySegment` access or word-wise hot-path behavior.

## Tests

Use Arrow Java vectors and allocators. Run with `-Darrow.memory.debug.allocator=true`.

Cover:

- empty vectors;
- capacity errors (`outputCapacity` throws);
- slice-offset rejection;
- all-valid, all-null, alternating nulls bitmap inputs;
- binary validity propagation correctness (random bitmaps);
- tail-bit correctness for non-multiple-of-8 row counts;
- invalid byte sizes rejected by `SegmentViews`;
- leak checks via child allocators (intentional missing release fails the test);
- `Errors.*` returns the expected exception type with the expected message shape.

## Non-goals

No compute kernels. No kernel interface. No registry.

## Acceptance criteria

- Memory utilities are small (each class < 200 lines) and auditable.
- Raw kernels never call `MemorySegment.ofAddress` (verified by grep in a future iteration).
- `BufferRefs.retain` retains data + validity for all passed vectors; releasing is symmetric.
- Tests cover memory and validity behavior with allocator debug mode active.
- All `Errors.*` helpers throw unchecked exceptions matching the table above.

## Cross-references

- `CORE_DESIGN.md §Arrow-aware wrapper layer`, §Memory and lifetime model, §SegmentViews.
- `AGENTS.md §Arrow buffer access`, §Safety reminders.
- `ARROW_JAVA_API_USAGE.md §2 Memory management`, §4 SegmentViews, §5 BitVectorHelper, §11 Output allocation.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Arrow Wrapper Boundary: Existing architectural boundary that validates vectors, manages lifetime, and bridges to raw kernels — owns the contract between Arrow vectors and raw compute (`CORE_DESIGN.md`).
- Raw Kernel Contract: Existing concept where hot-path classes consume `MemorySegment` and primitives only — depends on wrappers for safety and preconditions (`CORE_DESIGN.md`).
- Buffer Lifetime Management: Existing concept defined by retain/release pairing and debug-allocator verification — governs safe off-heap access (`CORE_DESIGN.md`, `ARROW_JAVA_API_USAGE.md`).
- Validity Bitmap Semantics: Existing concept enforcing Arrow validity polarity and word-wise propagation patterns — shared foundation for nullable behavior (`CORE_DESIGN.md`, `ARROW_JAVA_API_USAGE.md`).
- Memory Package Placeholder: `io.github.semyonsinchenko.arrowcompute.memory` exists but currently has no utility implementations beyond package scaffold (`src/main/java/io/github/semyonsinchenko/arrowcompute/memory/package-info.java`).

#### New Concepts Required
- Segment View Gateway: Dedicated bridge concept that is the single allowed location for unsafe address-to-segment conversion — directly supports wrapper-to-raw handoff.
- Retained Buffer Scope: Explicit lifecycle scope concept (`try-with-resources`) for wrapper calls — binds buffer liveness to kernel invocation window.
- Wrapper Boundary Checks: Unified precondition concept for shape/capacity/slice/decimal compatibility before raw access — protects raw kernels from defensive branching.
- Error Taxonomy for Compute Boundary: Consistent unchecked exception categories aligned to dispatch/shape/domain failures — standardizes behavior across wrappers.
- Validity Propagation Utility Layer: High-level null-propagation policy surface (all valid, unary propagate, binary propagate) — composes with low-level bitmap ops.
- Bitmap Word-Operations Layer: Primitive bitmap algebra for validity processing and future kernel reuse — complements, not duplicates, Arrow helpers.

#### Key Business Rules
- Unsafe Address Centralization: `MemorySegment.ofAddress(...).reinterpret(byteSize)` is only allowed through the segment-view utility; raw kernels must not invoke it.
- Symmetric Retain/Release: Every retained data and validity buffer in wrapper execution must be released in the same lifecycle scope.
- Wrapper-Owned Preconditions: Value count, output capacity, and slice-offset constraints are validated before any raw memory view is created.
- Unchecked Exception Policy: All boundary and domain failure paths remain unchecked and categorized by failure type.
- Arrow API First Rule: Built-in Arrow utilities are preferred unless raw `MemorySegment` or measured word-wise needs justify project-local helpers.
- Non-Goal Guardrail: This iteration introduces infrastructure only, not compute kernels/registry/interface expansion.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Implement a thin memory-bridge foundation in `memory/` that sits between existing wrapper architecture and planned raw kernels, following the project’s current layering (`wrapper -> memory utilities -> raw`).
- Leverage existing design conventions from `CORE_DESIGN.md` and `ARROW_JAVA_API_USAGE.md`: centralized unsafe memory bridging, explicit wrapper prechecks, and allocator-debug-backed lifetime discipline.
- Validate behavior through focused memory/validity tests in the existing JUnit + Arrow allocator test setup, keeping utilities small and auditable.

#### Key Design Decisions
- Centralize unsafe FFM bridging vs distributed conversion calls: centralized utility improves auditability and policy enforcement but creates a hard dependency point → recommend centralization to satisfy safety and grep-verifiability goals.
- Single retain API for data+validity vs split retain APIs: single API is simpler and harder to misuse, but may retain buffers not always touched in niche flows → recommend single retain API as the project’s default wrapper contract.
- Keep checks at wrapper boundary vs inside raw loops: wrapper checks add upfront validation cost but keep hot path branch-light and deterministic → recommend wrapper boundary checks, consistent with existing architecture.
- Build project bitmap ops vs rely only on Arrow helpers: custom ops add maintenance burden but enable word-wise bulk operations needed by nullable kernels → recommend minimal bitmap surface limited to operations not already covered well by `BitVectorHelper`.
- Add decimal precision/scale check now vs defer to iteration 14: adding now improves forward compatibility but may introduce unused surface early → recommend stub-or-minimal hook now only if it preserves utility cohesion; otherwise defer explicitly with a tracked boundary.

#### Alternatives Considered
- Let each wrapper call `MemorySegment.ofAddress` directly: rejected because it weakens safety control and conflicts with explicit centralization requirement.
- Push retain/release responsibility into raw kernels: rejected because raw layer must stay Arrow-free and lifecycle-agnostic.
- Implement generic framework-style memory registry: rejected as over-design relative to MVP and non-goals.
- Use only scalar bit operations from Arrow helpers for all validity cases: rejected for future throughput and word-wise propagation needs.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Slice offset detection mechanics: requirement permits implementation choice, but exact per-vector handling boundaries (especially variable-width/vector families) must be confirmed before coding.
- Decimal precision/scale timing: requirement allows stub now or add in iteration 14; delivery expectation for this iteration is not fully explicit.
- Error message shape: exception types are defined, but message format strictness is only partially described, which may create test expectation drift.
- “Small and auditable” threshold: class length target is clear, but auditability criteria beyond size are not formally specified.

#### Edge Cases
- Zero-row vectors with non-zero capacities: behavior for validity marking/propagation should remain consistent and not over-write unnecessary bytes.
- Tail bits beyond logical row count: correctness for non-multiple-of-8 counts must preserve Arrow semantics and avoid false null/valid signals.
- Mixed null profiles (all-valid/all-null/alternating/random): propagation must be correct without relying on data-slot values in null lanes.
- Unsupported vector types at boundaries: wrappers must fail deterministically with the agreed unchecked type when vectors are outside MVP scope.
- Retain/release failure paths: exceptions during setup must still avoid leaks under allocator debug mode.

#### Technical Risks
- Lifetime safety risk with unrestricted `MemorySegment` reinterpretation: misuse could create latent memory corruption; mitigation is strict scope confinement plus debug allocator tests.
- Bitmap polarity/bit-order bugs: one-bit mistakes can silently corrupt null semantics; mitigation is heavy bitmap test coverage including random and tail scenarios.
- Utility duplication drift against Arrow APIs: over-implementing helpers can increase maintenance and divergence; mitigation is Arrow-first review gate per utility method.
- Premature surface expansion in infrastructure layer: adding extra knobs now may conflict with MVP simplicity; mitigation is strict scope control to listed responsibilities.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Memory utilities are small (each class < 200 lines) and auditable. | Yes | Scope and existing package layout support this; auditability checklist should be made explicit during REASONS Canvas. |
| 2 | Raw kernels never call `MemorySegment.ofAddress` (verified by grep in a future iteration). | Partial | Strategy supports this, but explicit grep verification is deferred to a later iteration by requirement text. |
| 3 | `BufferRefs.retain` retains data + validity for all passed vectors; releasing is symmetric. | Yes | Directly aligned with utility contract and wrapper lifecycle model in `CORE_DESIGN.md`. |
| 4 | Tests cover memory and validity behavior with allocator debug mode active. | Yes | Build already enforces allocator debug mode in tests (`build.gradle.kts`). |
| 5 | All `Errors.*` helpers throw unchecked exceptions matching the table above. | Yes | Exception taxonomy is already consistent with project-level rules in design docs. |
