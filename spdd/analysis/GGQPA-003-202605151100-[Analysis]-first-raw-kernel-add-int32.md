# SPDD Analysis: First Raw Kernel AddInt32

## Original Business Requirement
# Requirement: First Raw Kernel — AddInt32Raw

## Business requirement

Implement the first raw SIMD kernel to prove the hot-path design.

## Scope

Create `raw/AddInt32Raw.java` (flat `raw/` package per foundation `CORE_DESIGN.md §Package layout`) with:

```java
static void computeAll(MemorySegment left, MemorySegment right, MemorySegment out, int n)
```

Entry-point method name is `computeAll` (foundation `AGENTS.md §API design`).

## Semantics

- Input: two contiguous int32 buffers.
- Output: one contiguous int32 buffer.
- Nulls are not represented at raw level.
- Overflow follows Java int wraparound.
- Output is preallocated by the caller.
- Method returns `void`.

Foundation invariants assumed (wrapper enforces; raw kernel does not check):

- **Little-endian** buffers.
- **Non-aliasing**: `left`, `right`, `out` do not overlap.
- **Zero slice offset**: `out` and inputs start at the buffer's logical first byte.

## Hot-path constraints

No Arrow imports, allocation, retain/release, `MemorySegment.ofAddress`, boxed primitives, streams, lambdas, collections, reflection, logging, or row-level exceptions.

Use `static final` species/layout/order constants, Vector API loop, and scalar tail.

## Tests

Raw tests without Arrow. Use `Arena.ofConfined()` as the test memory fixture; close in `@AfterEach`. Cover:

- `n = 0`, `n = 1`;
- `n` less than species length;
- `n` exactly species length;
- `n` non-multiple of species length;
- positive, negative, mixed-sign values;
- `Integer.MIN_VALUE`, `Integer.MAX_VALUE`;
- overflow wraparound (`MAX + 1`, `MIN - 1`);
- separate input/output segments (assert no aliasing assumption is violated by the test fixture).

## Benchmarks

Add JMH:

- `AddInt32Raw.computeAll` (Vector API);
- naive Java `MemorySegment` scalar loop.

Both consume outputs via `Blackhole.consume(out)` or a small reduction; never discard.

Fixed seed for input generation: `0xC0FFEEL`.

## Non-goals

No wrapper, public API, or raw-kernel abstraction.

## Acceptance criteria

- Raw tests pass without Arrow.
- JMH benchmark runs and reports raw vs naive `MemorySegment`.
- Class is small (< 200 lines) and readable.
- No Arrow imports; verified by grep.

## Cross-references

- `AGENTS.md §Hot paths are boring and explicit`, §Vector API guidelines, §Default invariants.
- `CORE_DESIGN.md §Raw kernel layer`, §Package layout.
- `BENCHMARKS.md §Raw kernel benchmarks`.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Raw Kernel Layer: Existing fast-tier concept defined in architecture docs as Arrow-free `MemorySegment` kernels with `computeAll` entry points and caller-owned outputs — forms the direct target surface for this requirement.
- Memory Bridge Boundary: Existing wrapper/memory utility boundary (`SegmentViews`, `BufferRefs`, `Checks`) that owns safety checks and lifecycle guarantees before raw execution — supplies the invariants this raw kernel is allowed to assume.
- Bitmap/Memory Utility Style: Existing low-level utility style (`Bitmap`, `Validity`) demonstrates small static classes with explicit loops and tail handling — provides coding and testing conventions for hot-path components.
- JMH Baseline Pattern: Existing benchmark scaffolding (`me.champeau.jmh` plugin and baseline benchmark class) plus benchmark policy docs already define comparison expectations for raw vs naive approaches.
- Iteration Sequencing Context: Existing plan places this as iteration 03, directly after infra and bridge foundations — clarifies this is a pattern-establishing kernel, not an end-to-end feature.

#### New Concepts Required
- AddInt32 Raw Compute Primitive: First concrete int32 vectorized arithmetic kernel in `compute/raw` — establishes the reusable pattern for future fixed-width raw kernels.
- Arrow-free Raw Test Harness: Dedicated raw-kernel test style using FFM `Arena` instead of Arrow vectors — validates semantics and edge cases at the kernel boundary.
- Raw-vs-Naive Int32 Benchmark Slice: Focused benchmark comparison for a single raw kernel against naive `MemorySegment` baseline — provides proof of hot-path direction before wrappers/dispatch are involved.

#### Key Business Rules
- Raw Layer Purity Rule: Kernel behavior is constrained to pure compute over contiguous int32 buffers with no Arrow dependencies and no lifecycle responsibilities.
- Wrapper-Enforced Invariant Rule: Endianness, non-aliasing, and zero-slice assumptions are contractual inputs to raw execution and must remain outside raw validation scope.
- Deterministic Arithmetic Rule: Integer overflow semantics are explicitly Java wraparound, so correctness includes overflow behavior rather than overflow prevention.
- Hot-Path Structure Rule: Strategic performance proof depends on Vector API main loop plus scalar tail path, with no allocation or exception-driven row control.
- Evidence Rule: Delivery is not complete without both correctness evidence (raw tests) and performance evidence (JMH raw vs naive), aligned to benchmark documentation.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Introduce a single-purpose fast-tier raw kernel as the first concrete implementation of the project’s raw contract, then validate it independently through Arrow-free tests and a narrowly scoped JMH comparison.
- Follow current architecture layering strictly: this iteration stays entirely in raw/test/benchmark scope and intentionally does not cross into wrapper, dispatch, or public API layers.
- Reuse established project conventions for class shape (small, final, static), benchmark execution (JMH plugin and shared JVM flags), and iteration boundaries (iteration 03 role in `DEVELOPMENT_PLAN.md`).

#### Key Design Decisions
- Raw-only delivery vs early wrapper integration: raw-only keeps the signal focused on hot-path feasibility but postpones end-to-end validation → recommend raw-only now because the requirement is explicitly pattern proof, not API exposure.
- Strict Arrow-free kernel boundary vs convenience Arrow abstractions: strict boundary increases discipline and test isolation but requires explicit memory-oriented coding style → recommend strict boundary to preserve long-term kernel portability and auditability.
- Single-kernel benchmark focus vs broad benchmark matrix in this iteration: narrow scope reduces noise and accelerates learning but leaves broader performance characterization for later iterations → recommend focused comparison now, then expand under later benchmark iterations.
- Contract-assumed invariants vs defensive checks in kernel: contract assumptions maximize hot-path simplicity but shift responsibility to wrappers/tests → recommend contract-assumed raw kernel consistent with architecture documents and non-goals.

#### Alternatives Considered
- Implement first kernel in wrapper layer only: rejected because it would blur responsibility boundaries and fail to prove raw-layer design directly.
- Build a generic raw-kernel abstraction before first concrete kernel: rejected as premature frameworking that increases complexity without proving throughput value.
- Defer benchmark until multiple kernels exist: rejected because this requirement explicitly uses benchmark evidence to validate the strategic direction early.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Benchmark acceptance threshold: requirement asks to report raw vs naive but does not define a pass/fail performance target or minimum improvement.
- Benchmark scope depth: benchmark doc recommends broader row/alignment matrix, while this requirement only mandates a minimal raw-vs-naive comparison.
- Readability criterion: “small and readable” is stated but lacks explicit readability rubric beyond line count.

#### Edge Cases
- Zero-length and tiny row counts: must preserve correctness when vector loop does not execute and only tail/no-op paths apply.
- Non-multiple-of-species lengths: scalar tail correctness is essential because it is the dominant correctness boundary for SIMD kernels.
- Signed extremes and wraparound: boundary values must match Java int arithmetic contract, including overflow transitions.
- Segment separation assumptions in tests: tests must avoid accidental aliasing to preserve contract validity and avoid false confidence.

#### Technical Risks
- Vector API portability behavior: preferred species may vary across machines/JDK behavior, potentially affecting observed benchmark shape; mitigation is functional correctness coverage across small/large `n` and documenting benchmark environment.
- Dead-code elimination in benchmark path: if outputs are not consumed robustly, benchmark results may be invalid; mitigation is explicit output consumption via `Blackhole` or reduction.
- Hidden boundary drift from architecture contract: adding defensive checks or Arrow usage in raw code could silently violate layer intent; mitigation is grep-based guardrails and strict package-level conventions.
- Benchmark comparability risk: naive baseline structure can accidentally include different work than vector path; mitigation is align semantic workload and output handling across both paths.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Raw tests pass without Arrow. | Yes | Directly addressable with dedicated raw test fixture using FFM `Arena` and no Arrow vectors. |
| 2 | JMH benchmark runs and reports raw vs naive `MemorySegment`. | Yes | Build already includes JMH plugin and runtime flags; only kernel-specific benchmark class is needed. |
| 3 | Class is small (< 200 lines) and readable. | Partial | Line-count is measurable; readability is subjective and should be checked against project style conventions in review. |
| 4 | No Arrow imports; verified by grep. | Yes | Fully addressable with package-local grep validation over raw kernel source. |
