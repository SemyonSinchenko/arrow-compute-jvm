# SPDD Analysis: Fast-Tier String Scaffold - StartsWithUtf8

## Original Business Requirement
# Requirement: Fast-Tier String Scaffold — StartsWithUtf8

## Business requirement

Establish the fast-tier string kernel pattern through one canonical operation. `StartsWithUtf8` is the simplest fast-tier string op that exercises the full vertical (offsets buffer + data buffer + bit-packed boolean output) and proves the project can deliver SIMD string predicates competitive with native.

## Scope

Create the full vertical for `StartsWithUtf8` over `VarCharVector` input with a scalar `byte[]` prefix and `BitVector` boolean output:

```text
raw/StartsWithUtf8Raw.java
wrapper/safe/StartsWithUtf8.java
dispatch/StartsWithDispatch.java
Compute.startsWith(...)
```

## Raw API

```java
static void computeAll(
    MemorySegment offsets,     // int32 offsets buffer, length n+1
    MemorySegment data,        // utf8 byte data buffer
    byte[] needle,             // prefix bytes (small scalar, broadcast)
    MemorySegment outBits,     // bit-packed boolean output (length ceil(n/8))
    int n
)
```

## Semantics

- Compare each row's first `needle.length` bytes against `needle` using `ByteVector` SIMD where possible (Vector API mask comparisons).
- Rows shorter than `needle.length` produce `false`.
- Empty `needle` produces `true` for every row.
- Input null lanes: raw kernel computes data anyway (safe on arbitrary bytes); wrapper sets output validity from input validity.
- Output is bit-packed Arrow boolean (1 bit per row); tail bits in the last byte handled per `BitVector` layout.
- Little-endian buffers, non-aliasing inputs vs output, zero slice offset (foundation invariants).

## Wrapper behavior

`StartsWithUtf8.eval(VarCharVector input, byte[] needle, BitVector out)`:

- `Checks.outputCapacity(out, n)`
- `Checks.zeroSliceOffset(input)`
- `try (var refs = BufferRefs.retain(input, out))`:
  - retrieve offset buffer, data buffer, output data buffer (bit-packed) and validity buffer
  - `Validity.propagateUnary(input, out, n)` (or `markAllValid` if `getNullCount() == 0`)
  - call `StartsWithUtf8Raw.computeAll(...)`
  - `out.setValueCount(n)`

## Dispatch behavior

`StartsWithDispatch.eval(VarCharVector input, byte[] needle, BitVector out)` routes directly to `StartsWithUtf8` for the only supported type combination. Throw `UnsupportedOperationException` via `Errors.unsupported(...)` for other input types.

`Compute.startsWith(...)` is the public entry point. `StartsWithDispatch` is a `public` class consistent with foundation §Dispatch surface visibility.

## Tests

Raw tests without Arrow (`Arena.ofConfined()` fixtures):

- `n = 0`, `n = 1`, less than species length, exactly species length, non-multiple of species length.
- Empty `needle`.
- `needle` length > every input length.
- `needle` length equal to input length (exact match).
- ASCII-only inputs.
- Multibyte UTF-8 inputs (needle is bytes; multibyte sequences must not be split — wrapper-level concern when the API surface grows, but raw kernel must not corrupt them).
- Mixed match / no-match patterns.
- Tail-bit correctness in `outBits` for non-multiple-of-8 row counts.

Wrapper tests with Arrow:

- All-valid, sparse nulls (1%), dense nulls (30%), all-null.
- `BitVector.validateFull()` on the output.
- Output value count is set.
- Allocator-debug-mode test JVM (`-Darrow.memory.debug.allocator=true`); no leaks.
- Input data is not mutated.

## Benchmarks

- Raw SIMD vs naive `MemorySegment` byte-by-byte loop.
- Wrapper vs raw.
- Optional: vs PyArrow `pa.compute.starts_with` over preloaded Arrow batches (literal needle).

Required dimensions: rows 1K / 16K / 64K / 1M; needle lengths 2 / 8 / 16 / 32 bytes; null profiles 0% and 10% (wrapper benchmarks).

## Non-goals

- Other string ops (`equals`, `is_ascii`, `length_bytes`, `ends_with`, `contains`, `lower_ascii`, `upper_ascii`, `trim_ascii`, `element_at`). Deferred to a fast-tier string expansion spdd.
- Vector-vs-vector `starts_with` (needle is a vector). Deferred.
- `ends_with` and `contains` are not free re-uses of `StartsWithUtf8Raw`; they get their own kernels later.

## Acceptance criteria

- `Compute.startsWith(input, needle, out)` works end-to-end for `VarCharVector → BitVector` with a `byte[]` prefix.
- Raw, wrapper, and dispatch tests pass.
- JMH benchmark runs and produces interpretable results with the baseline matrix labeled (raw vs naive `MemorySegment`).
- The pattern (raw signature shape, wrapper helpers used, dispatch class layout) is documented well enough that the next fast-tier string spdd reuses it without re-deriving design decisions.

## Cross-references

- `AGENTS.md §Vector API guidelines`, §Default invariants, §Slow-tier kernels (negative example — this is fast-tier).
- `CORE_DESIGN.md §Two-tier kernel design` (fast-tier op taxonomy).
- `ARROW_JAVA_API_USAGE.md §5 BitVectorHelper` (output bit layout).
- `BENCHMARKS.md §Raw kernel benchmarks`, §Wrapper benchmarks.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Compute API facade: public operation entrypoint that delegates to explicit dispatch classes, currently covering arithmetic and aggregation only - governs outward API consistency (`src/main/java/io/github/semyonsinchenko/arrowcompute/compute/Compute.java`).
- Public dispatch surface: operation-specific public dispatch classes route supported Arrow vector type combinations and reject unsupported ones via `Errors.unsupported` - defines extension and compatibility boundary (`src/main/java/io/github/semyonsinchenko/arrowcompute/compute/dispatch/AddDispatch.java`, `src/main/java/io/github/semyonsinchenko/arrowcompute/compute/dispatch/DivideDispatch.java`).
- Wrapper safety contract: wrappers perform value-count/capacity/slice checks, retain buffers in a bounded scope, propagate validity, call raw kernel, then finalize `setValueCount` - owns correctness at Arrow boundary (`src/main/java/io/github/semyonsinchenko/arrowcompute/compute/wrapper/safe/AddInt32.java`, `src/main/java/io/github/semyonsinchenko/arrowcompute/compute/wrapper/safe/CompareInt32Greater.java`).
- Memory lifetime bridge: `BufferRefs` and `SegmentViews` centralize retain/release and unsafe address-to-segment conversion for wrapper-scoped use - governs off-heap safety (`src/main/java/io/github/semyonsinchenko/arrowcompute/memory/BufferRefs.java`, `src/main/java/io/github/semyonsinchenko/arrowcompute/memory/SegmentViews.java`).
- Validity and bitmap semantics: validity helpers and bitmap operations already encode Arrow polarity, tail masking, and unary/binary propagation patterns - governs nullable behavior and bit correctness (`src/main/java/io/github/semyonsinchenko/arrowcompute/memory/Validity.java`, `src/main/java/io/github/semyonsinchenko/arrowcompute/memory/Bitmap.java`).
- Boolean bit-packed output precedent: `CompareInt32Greater` plus matrix tests already validate Arrow-compatible bit-packed boolean output and tail integrity - provides closest implementation analog for `BitVector` output path (`src/main/java/io/github/semyonsinchenko/arrowcompute/compute/wrapper/safe/CompareInt32Greater.java`, `src/test/java/io/github/semyonsinchenko/arrowcompute/compute/wrapper/safe/BitmapBooleanOutputMatrixTest.java`).

#### New Concepts Required
- Fast-tier UTF-8 prefix predicate kernel: canonical string predicate concept that consumes offsets/data buffers and emits bit-packed boolean results - extends fast-tier scope beyond fixed-width primitives.
- VarChar-to-BitVector wrapper vertical: unary string wrapper concept that combines variable-width buffer access, validity propagation, and boolean output finalization - bridges variable-width Arrow layout to raw SIMD path.
- String-specific dispatch and API entrypoint: public dispatch plus `Compute.startsWith` concept for a first-class string predicate surface - aligns strings with existing arithmetic dispatch model.
- Reusable fast-tier string scaffold pattern: documented vertical template (raw signature, wrapper helper sequence, dispatch shape, benchmark/testing matrix) - intended to accelerate subsequent fast-tier string operations without re-deriving conventions.

#### Key Business Rules
- Prefix semantics rule: rows match only when initial bytes match `needle`; shorter rows are non-matches; empty needle matches all rows - governs `StartsWithUtf8` predicate semantics.
- Null propagation split rule: raw may compute null lanes, but wrapper-owned validity determines observable null behavior - governs correctness separation between data and validity.
- Arrow boolean layout rule: output must remain bit-packed, LSB-first, with valid tail handling for non-multiple-of-8 row counts - governs interoperability with Arrow `BitVector`.
- Foundation invariant rule: little-endian access, non-aliasing assumptions, zero slice-offset enforcement, and retained-lifetime scope are mandatory preconditions - governs safety and portability.
- Benchmark honesty rule: performance claims must be baseline-labeled (raw vs naive and wrapper vs raw) and reproducible across required matrix dimensions - governs evidencing of “competitive with native” objective.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Deliver `StartsWithUtf8` as a full vertical consistent with current architecture: public `Compute` facade -> public dispatch class -> wrapper boundary checks/lifetime/validity orchestration -> Arrow-free raw kernel.
- Reuse established boolean-output and validity patterns (already present in compare wrappers) while introducing variable-width string buffer handling as the new capability boundary.
- Treat this iteration as a scaffold milestone: the output is not only the operation itself but also a repeatable fast-tier string template for subsequent UTF-8 predicates.

#### Key Design Decisions
- Prefix interpretation domain (`byte[]` literal vs richer text abstraction): byte[] keeps raw path simple and SIMD-friendly, but pushes text-level normalization concerns out of scope -> recommend byte[] literal now, consistent with fast-tier, low-overhead goals.
- Output tail policy (strict in-kernel tail clearing vs finalize-via-`setValueCount` convention): strict kernel masking improves local guarantees but adds extra complexity; finalize convention aligns with current wrapper patterns and existing tests -> recommend preserving current wrapper finalization convention and explicitly documenting expected tail behavior.
- Dispatch surface breadth (single `VarCharVector -> BitVector` combination now vs generalized polymorphic matrix): broader support improves immediate flexibility but expands risk and test surface significantly -> recommend single canonical combination now to establish pattern quality first.
- Benchmark scope strictness (minimum requirement matrix vs expanded ecosystem baselines): expanded baselines improve market signal but increase delivery complexity and variability -> recommend mandatory internal matrix now with optional PyArrow comparison retained as non-blocking.
- Pattern documentation depth (code-only precedent vs explicit scaffold guidance): relying on code alone is faster short-term but weak for downstream reuse -> recommend explicit pattern documentation to satisfy the acceptance criterion on reuse.

#### Alternatives Considered
- Implement `starts_with` in slow-tier wrapper first (`wrapper/slow`) then optimize later: rejected because requirement explicitly targets fast-tier scaffold and SIMD vertical proof.
- Add a generic string predicate framework before first operation: rejected because it introduces premature abstraction and conflicts with project preference for explicit specialized kernels.
- Deliver wrapper/dispatch first and postpone raw SIMD kernel: rejected because the business goal is to prove fast-tier competitiveness, which requires raw kernel presence and benchmarkable baselines.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Multibyte UTF-8 statement mixes concerns: requirement notes wrapper-level concern for sequence boundaries while API is byte-prefix based; expected future text-level semantics are not explicitly bounded.
- Documentation acceptance threshold is qualitative (“documented well enough”); no explicit checklist defines what constitutes sufficient scaffold documentation.
- Optional PyArrow benchmark is listed, but pass/fail influence is not explicit; this can create interpretation drift during completion review.

#### Edge Cases
- `needle` larger than all row lengths and `needle` equal to row length need deterministic non-match/exact-match behavior across mixed datasets.
- Zero-row and tiny-row batches must preserve valid Arrow vector state without stray bits in output buffers.
- Non-multiple-of-species row counts and non-multiple-of-8 bit tails must keep consistent predicate and bitmap outcomes.
- All-null inputs should preserve null validity while preventing accidental interpretation of data-lane bits as meaningful values.
- Inputs containing multibyte UTF-8 data with byte-prefix boundaries near codepoint edges must remain byte-correct and non-corrupting.

#### Technical Risks
- Variable-width buffer handling risk: incorrect offsets/data boundary handling can produce out-of-range reads or false matches; mitigation direction is strong raw tests across length profiles and mixed content.
- Bit-packed write correctness risk: one-bit indexing/tail errors can pass value assertions but fail Arrow structural integrity; mitigation direction is `BitVector.validateFull()` and targeted tail-bit tests.
- Lifetime/scope risk for unsafe memory segments: segment escape or retain/release imbalance can cause allocator-debug failures or latent corruption; mitigation direction is strict wrapper-scoped `BufferRefs` usage and no segment escape.
- Performance claim risk: if benchmark baselines are mislabeled or weak, “competitive with native” narrative is not defensible; mitigation direction is explicit baseline-matrix mapping and reproducible fixed-seed datasets.
- API creep risk: broadening type combinations or string operation family in this iteration can dilute scaffold quality; mitigation direction is strict scope adherence to one canonical operation.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `Compute.startsWith(input, needle, out)` works end-to-end for `VarCharVector → BitVector` with a `byte[]` prefix. | Yes | Architecture already supports this vertical pattern via existing `Compute -> Dispatch -> Wrapper -> Raw` conventions. |
| 2 | Raw, wrapper, and dispatch tests pass. | Yes | Existing project test style and helper utilities directly support a dedicated three-layer test matrix for this operation. |
| 3 | JMH benchmark runs and produces interpretable results with the baseline matrix labeled (raw vs naive `MemorySegment`). | Yes | JMH infrastructure exists; requirement-aligned labeling conventions are already documented in benchmark requirements and existing benchmark classes. |
| 4 | The pattern (raw signature shape, wrapper helpers used, dispatch class layout) is documented well enough that the next fast-tier string spdd reuses it without re-deriving design decisions. | Partial | Existing docs describe target architecture and tiering, but string-specific scaffold guidance is not yet concretely present in codebase artifacts and must be made explicit in this iteration output. |
