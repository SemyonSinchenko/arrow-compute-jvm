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
