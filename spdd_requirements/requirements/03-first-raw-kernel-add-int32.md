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
