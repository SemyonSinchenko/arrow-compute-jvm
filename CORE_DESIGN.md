# CORE_DESIGN.md

## Purpose

This project is a JVM-native compute layer over Arrow-style columnar memory. It is designed for long-running JVM data engines that already work with Arrow buffers and want fast, deployable kernels without per-platform native builds.

Iteration sequencing and dependency planning live in `DEVELOPMENT_PLAN.md`.

The goal is not to clone all of Arrow C++ Compute. The first goal is a small, honest, fast core:

```text
fixed-width primitive kernels
+ bitmap/validity utilities
+ simple aggregations
+ strict memory/lifetime handling
+ JMH benchmarks
```

The codebase should be easy for humans and LLM agents to extend safely. The ideal workflow for a new operation is:

```text
add one raw kernel
add raw tests without Arrow
add Arrow-aware wrapper
add wrapper tests with Arrow
add dispatch branch
add benchmark
```

## Design philosophy

### Modern Java as a systems language

Use Java as a low-level JVM systems language:

- small `final` classes
- `static` methods
- primitive locals
- explicit byte offsets
- `MemorySegment`
- Vector API
- preallocated output buffers
- simple counted loops
- no object-per-row layout
- no framework runtime in kernels

Prefer this:

```java
final class AddInt32Raw {
    static void computeAll(MemorySegment a, MemorySegment b, MemorySegment out, int n) {
        // tight loop
    }
}
```

Avoid this:

```java
AbstractVectorizedArithmeticExecutionStrategyFactoryImpl
```

### Minimal abstractions

Abstractions must earn their existence.

Good early abstractions remove repeated unsafe code or repeated correctness logic:

```text
SegmentViews
BufferRefs
Validity
Bitmap
Checks
Errors
```

Suspicious early abstractions mostly rename direct method calls:

```text
Kernel
ScalarKernel
NullSafeKernel
NullUnsafeKernel
FunctionRegistry
KernelSignature
FunctionDescriptor
KernelExecutionStrategyFactory
Datum
```

These may become useful later, but they should not exist until the codebase has a concrete pain point.

### Follow Arrow semantics, not necessarily Arrow internals

The implementation should follow Arrow/PyArrow compute semantics where practical:

- Arrow buffers are the data model.
- Inputs are read-only by default.
- Outputs are written into preallocated Arrow buffers.
- Output validity follows Arrow null semantics.
- Fixed-width primitive kernels come first.

Exact parity with Arrow C++ is not required when it would make the JVM implementation disproportionately complex.

## Execution layers

The architecture has strict layers:

```text
Public API
  -> Dispatch layer
      -> Arrow-aware wrapper layer
          -> Raw kernel layer
```

Memory utilities are used by wrappers, not by raw hot loops.

## Public API layer

The public API should initially be a small static facade:

```java
Compute.add(left, right, out);
Compute.divide(left, right, out);
Compute.greater(left, right, out);
Compute.sum(input, out);
```

Example shape:

```java
public final class Compute {
    private Compute() {}

    public static void add(FieldVector left, FieldVector right, FieldVector out) {
        AddDispatch.eval(left, right, out);
    }

    public static void divide(FieldVector left, FieldVector right, FieldVector out) {
        DivideDispatch.eval(left, right, out);
    }

    public static void greater(FieldVector left, FieldVector right, FieldVector out) {
        CompareDispatch.greater(left, right, out);
    }

    public static void sum(FieldVector input, FieldVector out) {
        AggregateDispatch.sum(input, out);
    }
}
```

Array-producing functions should write into preallocated output vectors. Convenience methods that allocate outputs may be added later, but they must be benchmarked separately.

### Aggregation defaults (MVP)

For first scalar aggregations, wrappers enforce fixed defaults:

- `skip_nulls = true`
- `min_count = 1`

This means all-null input yields a null scalar output, while mixed-null
input aggregates only valid rows. Int64 `sum` uses Java `long`
wraparound semantics. Float `sum` remains naive order-dependent
arithmetic, and tests use bounded ULP tolerance when SIMD reduction order
differs from scalar reference order.

## Options and flags

Operations may have orthogonal modes: `checked` vs `unchecked` arithmetic,
NaN-propagating vs NaN-agnostic `min`/`max`, signed-zero handling, etc.
The project passes these as **primitive flags at the wrapper signature
only**:

```java
Compute.add(left, right, out, /*checked=*/ false, /*nanAgnostic=*/ false);
Compute.min(input, out, /*nanAgnostic=*/ true);
```

Rules:

- **No options objects.** No records, maps, or arrays of flags cross
  into wrapper or raw layers.
- **Raw kernels are single-mode.** Each mode-combination has its own
  raw class: `AddInt32Raw`, `AddInt32CheckedRaw`,
  `MinFloat64NanAgnosticRaw`. Wrapper branches on the primitive flags
  and selects the right raw kernel.
- **Naming**: `<Op><Type>[<Mode>]Raw`, modes concatenated in a fixed
  order if more than one applies. The default-mode kernel drops the
  suffix.
- **Escalation rule**: if an op grows to a third orthogonal mode,
  revisit the policy before the suffix grammar explodes. A small
  per-op `<Op>Options` record at the wrapper layer (still never
  crossed into raw kernels) becomes acceptable at that point.

## Pre-resolved kernel handle (v2)

The MVP `Compute.*` facade dispatches per call. For engines that invoke
the same operation across many batches in a row, this re-resolves the
kernel for each call.

**Future direction (v2, not MVP):** introduce a companion
`Compute.resolve(...)` that returns a pre-resolved `KernelHandle`:

```java
KernelHandle h = Compute.resolve(Op.ADD, leftType, rightType, outType,
                                  /*checked=*/ false);
for (Batch b : batches) {
    h.eval(b.left, b.right, b.out);   // no dispatch
}
```

This keeps the public surface small (just `Compute.resolve` + a
`KernelHandle` interface), and it does not require a registry,
`Datum`, or `FunctionSignature` abstraction. Dispatch classes already
contain the type-routing logic; `resolve` exposes that logic as a
public memoized step.

MVP does not implement this. It is documented here so the MVP design
does not drift in a direction that closes the door on it.

## Dispatch layer

Dispatch is outside the hot path.

Dispatch code may be verbose. That is acceptable. It can use `instanceof`, helper methods, readable branching, and ordinary Java style.

Example:

```java
final class AddDispatch {
    private AddDispatch() {}

    static void eval(FieldVector left, FieldVector right, FieldVector out) {
        if (left instanceof IntVector l
                && right instanceof IntVector r
                && out instanceof IntVector o) {
            AddInt32.eval(l, r, o);
            return;
        }

        if (left instanceof BigIntVector l
                && right instanceof BigIntVector r
                && out instanceof BigIntVector o) {
            AddInt64.eval(l, r, o);
            return;
        }

        if (left instanceof Float4Vector l
                && right instanceof Float4Vector r
                && out instanceof Float4Vector o) {
            AddFloat32.eval(l, r, o);
            return;
        }

        if (left instanceof Float8Vector l
                && right instanceof Float8Vector r
                && out instanceof Float8Vector o) {
            AddFloat64.eval(l, r, o);
            return;
        }

        throw Errors.unsupported("add", left, right, out);
    }
}
```

Dispatch rules:

- Dispatch must not contain raw Vector API loops.
- Dispatch must not implement memory lifetime logic.
- Dispatch should be explicit and grep-friendly.
- Do not introduce a generic registry until direct dispatch becomes painful.

### Fallthrough behavior

The final `throw Errors.unsupported(...)` is reached whenever none of
the explicit `instanceof` branches matched. This includes — and is the
**intentional** rejection path for — dictionary-encoded vectors (see
§Non-goals). The thrown `UnsupportedOperationException` message names
the concrete input types (`vector.getClass().getSimpleName()` or
equivalent) so callers can diagnose the mismatch without a debugger.

Dispatch does not silently decode dictionaries or coerce types. Callers
must materialize dictionaries (`DictionaryEncoder.decode(...)` or
equivalent Arrow Java path) before invoking compute.

## Arrow-aware wrapper layer

Wrappers are the Arrow-Java adapter into the kernel layer. They translate Arrow `FieldVector` inputs into `MemorySegment` views that raw kernels can consume, apply Arrow-specific validity rules, and set Arrow-specific output state. They do **not** own buffer lifetime; per SPDD 13 (`spdd_requirements/requirements/13-arrow-rs-peer-positioning.md`), the caller is responsible for keeping all input and output `FieldVector`s live for the duration of every wrapper call. This mirrors the arrow-rs / arrow-cpp kernel contract.

Wrappers accept concrete Arrow vectors such as:

```text
IntVector
BigIntVector
Float4Vector
Float8Vector
BitVector
```

Wrappers are responsible for:

- checking value counts
- checking output capacity
- rejecting sliced inputs (non-zero slice offset)
- creating bounded `MemorySegment` views
- preparing output validity buffers when `null_count > 0` (the `null_count == 0` path leaves validity-buffer contents unspecified per the Arrow IPC spec)
- choosing null execution mode
- calling raw kernels
- setting output value count and null count

Buffer lifetime is owned by the caller, not by the wrapper. Per SPDD 13,
callers must keep input and output `FieldVector`s live for the duration of
every wrapper call. `BufferRefs` survives as a public utility for callers
(tests, ingestion paths, async hand-off) that need explicit retain/release
pairing, but wrappers themselves no longer call it. This mirrors the
arrow-rs / arrow-cpp kernel contract.

Wrappers may import Arrow Java classes. Wrappers must not contain the main Vector API loop.

Example:

```java
final class AddInt32 {
    private AddInt32() {}

    static void eval(IntVector left, IntVector right, IntVector out) {
        int n = Checks.sameValueCount(left, right);
        Checks.outputCapacity(out, n);
        Checks.zeroSliceOffset(left, right);

        long byteSize = (long) n * Integer.BYTES;
        var leftData = SegmentViews.data(left, byteSize);
        var rightData = SegmentViews.data(right, byteSize);
        var outData = SegmentViews.data(out, byteSize);

        AddInt32Raw.computeAll(leftData, rightData, outData, n);

        if (left.getNullCount() == 0 && right.getNullCount() == 0) {
            out.setNullCount(0);
        } else {
            Validity.propagateBinary(left, right, out, n);
        }
        out.setValueCount(n);
    }
}
```

## Raw kernel layer

Raw kernels are the hot path.

Raw kernels accept only:

- `MemorySegment`
- primitive scalar values
- row counts
- byte offsets, if needed
- bitmap segments, if needed

Raw kernels must not:

- import Arrow classes
- allocate
- retain or release buffers
- call `MemorySegment.ofAddress`
- construct Arrow vectors
- set Arrow value counts
- log
- use exceptions as row-level control flow
- call framework code
- use streams, lambdas, `Optional`, boxed primitives, or collections in the loop

Raw kernels should be small standalone classes, usually 100-300 lines.

### Default invariants assumed by raw kernels

Raw kernels assume the following unless they explicitly document
otherwise. The wrapper layer is responsible for enforcing these
invariants at the boundary; a raw kernel handed a buffer that violates
them may produce wrong results or crash the JVM.

- **Non-aliasing**: inputs and outputs do not overlap in memory.
  Wrappers must not pass aliasing segments. Kernels that explicitly
  support in-place operation document this with an `inPlace` method or a
  documentation note.
- **Little-endian**: data buffers are little-endian (Arrow in-memory
  invariant). See §"Memory and lifetime model".
- **Contiguous, zero-offset**: the `MemorySegment` view starts at the
  buffer's logical first byte. Wrappers reject vectors with non-zero
  slice offset before calling raw kernels. Offset-aware raw kernels are
  out of MVP scope.
- **Aligned for the element type**: data buffers are at least
  element-aligned (e.g., 4-byte aligned for int32, 8-byte aligned for
  int64/float64). Arrow Java buffers are by default; the wrapper does
  not need to re-check this unless dealing with external buffers.

For array-producing kernels, raw kernels mutate preallocated output memory and return `void`:

```java
static void computeAll(
        MemorySegment left,
        MemorySegment right,
        MemorySegment out,
        int n
)
```

Avoid returning Arrow vectors from raw kernels:

```java
static IntVector computeAll(IntVector left, IntVector right, int n) // no
```

For scalar-producing aggregation kernels, returning a primitive is acceptable:

```java
static long sumNoNulls(MemorySegment input, int n)
```

## Package layout

Kernels are organized by execution strategy, not inheritance. The split
between `safe` and `validonly` is a **wrapper** concern; raw kernels are
flat (their distinction is encoded by method name and signature, not by
package).

```text
src/main/java/<base>/compute/
  Compute.java

src/main/java/<base>/compute/dispatch/
  AddDispatch.java
  DivideDispatch.java
  CompareDispatch.java
  AggregateDispatch.java
  RegexDispatch.java
  DecimalDispatch.java

src/main/java/<base>/compute/wrapper/safe/
  AddInt32.java
  AddInt64.java
  AddFloat32.java
  AddFloat64.java
  MulInt32.java
  MulInt64.java
  MulFloat32.java
  MulFloat64.java
  GreaterInt32.java
  GreaterInt64.java
  GreaterFloat32.java
  GreaterFloat64.java
  StartsWithUtf8.java
  EqualsUtf8.java
  IsAsciiUtf8.java
  LengthBytesUtf8.java

src/main/java/<base>/compute/wrapper/validonly/
  DivInt32.java
  DivInt64.java
  RemInt32.java
  RemInt64.java
  CheckedCastInt64ToInt32.java

src/main/java/<base>/compute/wrapper/agg/
  SumInt64.java
  SumFloat64.java
  MinFloat64.java
  MaxFloat64.java

src/main/java/<base>/compute/wrapper/slow/
  RegexMatchUtf8.java
  RegexExtractUtf8.java
  RegexReplaceUtf8.java
  LowerUtf8.java
  UpperUtf8.java
  AddDecimal128.java
  MulDecimal128.java
  DivDecimal128.java
  RegexMatcher.java          // interface + default impl

src/main/java/<base>/compute/raw/
  AddInt32Raw.java
  AddInt64Raw.java
  AddFloat32Raw.java
  AddFloat64Raw.java
  AddInt32CheckedRaw.java
  MulInt32Raw.java
  MulFloat64Raw.java
  GreaterInt32Raw.java
  GreaterFloat64Raw.java
  DivInt32Raw.java
  DivInt64Raw.java
  RemInt32Raw.java
  RemInt64Raw.java
  SumInt64Raw.java
  SumFloat64Raw.java
  StartsWithUtf8Raw.java
  EqualsUtf8Raw.java
  IsAsciiUtf8Raw.java
  LengthBytesUtf8Raw.java

src/main/java/<base>/compute/memory/
  BufferRefs.java
  SegmentViews.java
  Validity.java
  Bitmap.java
  Checks.java
  Errors.java
```

Rationale for the flat `raw/` layout: a raw kernel's null mode is encoded
by its method signature (`computeAll` vs `validOnly` vs `noNulls` vs
`skipNulls`), not by its package. Splitting raw kernels into `raw/safe/`
and `raw/validonly/` doubled the package taxonomy without adding type
safety. Wrapper packages remain split because wrappers really do differ
by null path — they branch on `getNullCount()` and on slow-vs-fast tier.

## Two-tier kernel design

Not every operation benefits from Vector API SIMD. The project recognizes
two tiers of kernel and treats them differently in code, in
documentation, and in benchmarks.

### Fast tier (SIMD `raw/` kernels)

Fast-tier kernels follow the strict raw rules described in
`AGENTS.md`: Vector API, `MemorySegment`, no allocation, no branches in
the inner loop, no Arrow imports. They live in `raw/` and have wrapper
counterparts in `wrapper/safe/`, `wrapper/validonly/`, or
`wrapper/agg/`. This is where the project's value proposition lives —
JVM-native, fused, single-JAR-deploy, competitive with native.

Fast-tier ops at MVP:

- **Fixed-width arithmetic**: add, sub, mul, div (safe and checked
  variants) for int32, int64, float32, float64.
- **Fixed-width comparisons**: `<`, `<=`, `==`, `!=`, `>=`, `>`.
- **Bitmap ops**: AND, OR, AND_NOT, NOT, word-wise validity propagation.
- **Aggregations**: sum, min, max, count.
- **Strings (UTF-8) — scanning/short-needle subset**:
  - `equals(str_vec, scalar)`, `equals(str_vec, str_vec)`
  - `starts_with(str_vec, prefix)`, `ends_with(str_vec, suffix)`
  - `contains(str_vec, short_needle)` (literal needles up to ~16 bytes)
  - `is_ascii(str_vec)`
  - `length_bytes(str_vec)` (int32 SIMD over the offsets buffer)
  - `lower_ascii`, `upper_ascii`, `trim_ascii`
  - `element_at(str_vec, i)` (UTF-8 byte indexing; fast on ASCII)

### Slow tier (`wrapper/slow/`, plain Java loops)

Slow-tier kernels are wrapper-only. They have no raw layer because they
would not benefit from one. They walk Arrow Java vectors directly with
`vector.get(i)` style accessors, use the Arrow Java `algorithm` module
where it fits, and use `BitVectorHelper` for scalar bit work. They are
honest about being slower than native — the project ships them for API
completeness.

Slow-tier ops at MVP:

**Strings:**

- Regex: `regex_match`, `regex_extract`, `regex_replace` — behind
  `RegexMatcher` / `RegexExtractor` / `RegexReplacer` interfaces.
- `LIKE` with non-trivial patterns.
- Unicode-aware `lower` / `upper`.
- Locale-aware comparison and collation.
- `replace` with variable-length replacement.
- Date / timestamp parsing from strings.

**Decimal (Decimal128 / Decimal256):**

- All arithmetic (add, sub, mul, div).
- Comparisons.
- Casts to/from float, int, string.

**Borderline (start slow, candidate for graduation):**

- `length_chars` (UTF-8 codepoint count).
- Simple `LIKE` patterns (`prefix%`, `%suffix`, `%middle%`).

### Pluggability for slow-tier ops

Slow-tier ops that have plausible alternative backends (JNI to Arrow
C++, re2j, Hyperscan-Java, native ICU) expose an **interface plus
default plain-Java implementation**:

```java
public interface RegexMatcher {
    void match(VarCharVector input, String pattern, BitVector out);
    static RegexMatcher defaultMatcher() { return new JavaUtilRegexMatcher(); }
}
```

Callers can pass a custom implementation per call, or swap the static
factory globally. **No `ServiceLoader`, no DI framework, no kernel
registry.** This is the lightest possible extension story that still
leaves the door open for future native or 3rd-party backends.

### Graduation rule

A borderline op moves from slow tier to fast tier only when a benchmark
demonstrates it would be measurably hot in a real workload. Until then
the project resists writing SIMD kernels for ops it cannot prove are
worth it.

### Benchmark requirement

Every slow-tier kernel ships with a benchmark comparing it against
PyArrow (and, when available, a native baseline). Reports must
explicitly label the result as slow-tier. See
`BENCHMARKS.md §Slow-tier benchmarks`.

## Aggregation state model

The MVP does not include a grouped hash aggregation engine, but the
**state layout** is committed now because it affects raw kernel
signatures and the long-term shape of every aggregation kernel.

### SoA — parallel primitive arrays keyed by group id

```java
long[] counts;   // counts[gid] = count of valid rows for group gid
int[]  mins;     // mins[gid]   = min for group gid
int[]  maxs;     // maxs[gid]   = max for group gid
long[] sums;     // sums[gid]   = sum for group gid
```

For ungrouped aggregations, state is just primitive locals
(`long count, int min, int max, long sum`).

Rationale:

- **No object-per-group.** Java records would add a header per group; at
  100k groups this is unmeasurable, at 10M groups it is 200MB+ of pure
  header overhead. Project Valhalla value-records may change this
  trade-off later, but they are not stable in Java 25.
- **Matches Arrow's columnar layout.** The finalization pass that
  writes results back into Arrow output vectors naturally maps one
  state array to one output column.
- **SIMD-friendly finalization.** If we ever vectorize the final pass
  (writing min/max/sum columns), one-array-per-pass is the right shape.
- **Aligns with the project rule** "primitive locals, no object-per-row".

### Cost

Per-row update touches N state arrays (one cache line per aggregation
field). For typical group counts (< 100k groups, < 8 aggregation fields)
the entire state set fits in L2. For very wide aggregations (many
fields), a struct-of-arrays-per-cache-line variant may be added later;
the current layout is a deliberate v1 simplification.

### Out of scope (still)

- The hash table that maps group keys to `gid` is **not** part of MVP.
- Grouped hash aggregation engine is **not** part of MVP.
- Spilling, partial aggregation, distributed roll-up are **not** part
  of MVP.

These are deferred until the MVP proves the JVM-native pitch holds
against the arrow-rs out-of-process reference for fixed-width
arithmetic (see `§Risks & assumptions §Performance assumption`).

## Safe vs valid-only kernels

The split is about whether the data operation may run over null slots.

### `safe`

A safe kernel may compute data for every row, including null rows, because output values in null rows are not observable.

Examples:

- integer add/sub/mul
- floating add/sub/mul/div
- comparisons
- bitwise operations
- widening casts

Nullable safe strategy:

```text
out_validity = input validity rule
out_data     = compute all rows
```

### `validonly`

A valid-only kernel must not execute the operation on null rows because invalid/null data could cause observable behavior.

Examples:

- integer division
- integer remainder
- checked arithmetic
- checked casts
- operations with domain errors
- parsers

Nullable valid-only strategy:

```text
out_validity = input validity rule
compute only where out_validity is true
```

A raw valid-only kernel usually receives the final active bitmap:

```java
static void validOnlyChecked(
        MemorySegment leftData,
        MemorySegment rightData,
        MemorySegment outData,
        MemorySegment activeValidity,
        int n
)
```

### Precheck-before-loop for checked kernels

**Active rows** are rows where *all* input validities are true — for a
binary kernel `left_validity & right_validity`; for n-ary kernels the
AND of every input's validity. Inactive null rows are never inspected
for domain errors and never trigger throws.

Checked kernels (`DivInt32`, checked arithmetic, checked narrowing
casts, parsing) must **precheck the active rows before the compute loop
starts**. If any active row would trigger a domain error (divide-by-zero,
overflow, invalid input), the wrapper throws `ArithmeticException`
carrying the row index of the first offender, and the compute loop
never runs.

Consequences:

- No per-row `try`/`throw` in the hot loop. Loops stay branch-light.
- No partial writes to the output on failure: precheck throws **before**
  any `MemorySegment` view is created or any compute begins.
- `Integer.MIN_VALUE / -1` (and the int64 analogue) is treated as a
  checked-overflow case under the same precheck rule.

See `AGENTS.md §Error handling §Exception types` for the full
exception-type table (`UnsupportedOperationException` for dispatch
mismatches, `IllegalArgumentException` for shape/slice/capacity,
`ArithmeticException` for domain errors).

## Null semantics

Do not confuse schema nullability with runtime null presence.

Schema-level:

```text
field is nullable
```

Runtime-level:

```java
vector.getNullCount() == 0
```

Runtime `nullCount` drives fast path selection.

Arrow validity uses `1 = valid`, `0 = null`. All bitmap code in this
project assumes this polarity; the inverse is a frequent bug source.

**Bit order within a byte is LSB-first** per the Arrow IPC spec: row 0
= bit 0 = mask `0x01`, row 7 = bit 7 = mask `0x80`. Row 8 lives in the
next byte at bit 0. See `AGENTS.md §Validity bitmap rules` for
tail-word semantics for non-multiple-of-64 row counts.

Common modes:

```text
NO_NULLS
NULLABLE_COMPUTE_ALL
NULLABLE_VALID_ONLY
```

These labels are documentation only. There is no `NullMode` enum
threaded through dispatch — wrappers branch on `vector.getNullCount()`
and on the operation's own semantics directly.

For simple null-propagating binary kernels:

```text
out_validity = left_validity & right_validity
```

For simple unary kernels:

```text
out_validity = input_validity
```

For safe kernels, null-lane output data is don't-care and should not be asserted in tests unless explicitly defined.

### Float special values

NaN and Infinity are values, not nulls.

- Arithmetic with NaN produces NaN.
- IEEE 754 comparisons involving NaN are `false`, including
  `NaN == NaN`.
- Infinity arithmetic follows standard IEEE rules.
- Aggregations: the default is "NaN propagates" — any-NaN-in produces
  NaN-out for `min`, `max`, `sum`. A "skip-NaN" variant may exist later;
  it must be named explicitly (suffix `NanAgnostic` or a primitive
  flag).
- NaN is never confused with the validity bitmap. A NaN value with
  validity `1` is a valid NaN; validity `0` means null regardless of
  the underlying byte pattern.

## Memory and lifetime model

Arrow Java buffers are reference-counted off-heap memory.

**Buffer lifetime is owned by the caller.** Per SPDD 13, wrappers and raw
kernels assume input and output `FieldVector`s remain live for the duration
of every wrapper call. Wrappers do not retain. Callers that need to defer
execution across thread or stage boundaries must retain explicitly using
`BufferRefs` or Arrow Java's reference manager.

All raw memory access must be centralized through memory utilities and wrapper code.

All raw kernels assume **little-endian** buffers (Arrow in-memory
invariant). Big-endian hosts are out of MVP scope. The project does not
attempt to support big-endian Arrow streams; ingestion from such a
source must byte-swap before reaching the wrapper layer.

### `SegmentViews`

Creates bounded `MemorySegment` views over Arrow buffers.

Rules:

- Check requested byte size against `ArrowBuf.capacity()`.
- Use Arrow Java buffer addresses.
- Use `MemorySegment.ofAddress(...).reinterpret(byteSize)` only here.
- Raw kernels must never call `MemorySegment.ofAddress`.

#### Lifetime invariant

The project uses the **two-arg** form of `reinterpret`:

```java
MemorySegment.ofAddress(arrowBufAddress).reinterpret(byteSize);
```

This returns a **global, unrestricted** segment whose validity is *not*
linked to any `Arena` or `Scope`. Lifetime correctness depends entirely
on the caller's buffer-lifetime contract (see §Memory and lifetime model
opening). Therefore:

- `MemorySegment` views must never escape the wrapper call. No storing
  in fields, no returning to callers, no passing to background threads.
- Tests run with `-Darrow.memory.debug.allocator=true` so any leaked
  retain/release imbalance at the caller's batch close fails the test
  rather than silently corrupting later runs.
- This choice trades safety for cost: the scoped form
  (`reinterpret(byteSize, arena, cleanup)`) would be safer but pays
  per-call Arena overhead. We accept the trade-off because wrappers are
  thin and the lifetime invariant is local to the call.

### `BufferRefs`

Public utility for callers that need explicit retain/release pairing on
Arrow buffers — for example, tests that materialize buffers, ingestion
paths that hand buffers between stages, or async pipelines that cross
thread boundaries. Wrappers do not call `BufferRefs` themselves; per
SPDD 13, buffer lifetime is the caller's responsibility.

When used by callers:

- Retain data buffers before creating or using `MemorySegment` views.
- Retain validity buffers when reading or writing validity.
- Release buffers in `close()`.
- Prefer try-with-resources.
- `retain()` protects lifetime, not exclusive ownership.

### `Validity`

Owns high-level validity propagation:

```java
Validity.markAllValid(out, n);
Validity.propagateUnary(input, out, n);
Validity.propagateBinary(left, right, out, n);
```

Per SPDD 13, wrappers do not call `markAllValid` on the `null_count == 0`
happy path. Instead they set the output's null count to `0` and follow the
Arrow IPC convention that leaves validity-buffer contents unspecified.
`markAllValid` remains available for callers that need a fully materialized
bitmap.

### `Bitmap`

Owns low-level bit manipulation. It should be heavily tested because many correctness bugs will be one-bit bugs.

## Threading model

Kernels are single-threaded by design.

They operate on one batch or slice at a time and never create threads.

Wrappers and raw kernels are **not thread-safe**. Callers must serialize
per-batch execution; concurrent calls into the same wrapper or onto
overlapping Arrow vectors are undefined behavior.

Parallelism belongs to the caller or execution engine:

```text
Spark executor
Flink task
Trino worker
custom batch scheduler
```

This avoids:

- nested parallelism
- oversubscription
- hidden thread pools
- unexpected synchronization
- confusing memory ownership
- difficult cancellation semantics

Benchmarking kernels should be single-threaded by default.

A separate driver-level benchmark may test scaling across many independent batches, but that is not a raw kernel benchmark.

## Testing philosophy

Testing is split by layer. Each layer tests its own responsibility and should not duplicate the responsibilities of other layers.

### Raw kernel tests

Raw kernel tests do not require Arrow.

They allocate memory using FFM `Arena` and call raw kernels directly.

They test:

- numeric correctness
- scalar tails
- empty input
- single row
- row counts not divisible by vector species length
- boundary values
- NaN and Infinity for floating point
- overflow behavior, if defined
- division by zero behavior for checked kernels
- valid-only behavior using synthetic bitmaps
- input/output aliasing assumptions if supported or forbidden

Raw tests should be small and fast.

### Memory utility tests

Memory utilities are tested with Arrow Java vectors and allocators.

They test:

- buffer retention and release
- bounded segment creation
- invalid byte-size rejection
- validity buffer sizing
- bitmap correctness
- all-valid output marking
- null propagation
- tail bit handling
- behavior with empty vectors
- behavior with all-null and all-valid vectors

Memory tests do not need to test arithmetic correctness. That belongs to raw kernel tests.

### Wrapper tests

Wrapper tests use Arrow Java vectors.

They test:

- wrapper chooses the right null path
- output validity is correct
- output value count is set
- output capacity checks work
- wrapper calls the raw kernel correctly
- memory helpers are used correctly
- inputs are not mutated

Wrapper tests should include representative kernel correctness, but they should not exhaustively retest every raw kernel corner case.

### Dispatch tests

Dispatch tests use Arrow Java vectors.

They test:

- supported type combinations route to the right wrapper
- unsupported type combinations throw clear errors
- output type mismatches are rejected
- scalar/vector shape variants route correctly when added

Dispatch tests should not test raw arithmetic corner cases.

### Integration tests

Integration tests may use the public `Compute` API over Arrow vectors.

They test realistic flows:

```text
allocate vectors
fill data
call Compute.*
check output data and validity
close resources
```

### Property-based tests

Use property-based tests where practical:

- random row counts
- random values
- random null bitmaps
- random scalar/vector inputs
- random batch shapes

For nullable kernels, always assert:

```text
valid output data
+
output validity bitmap
```

Do not assert null-slot output data unless the kernel explicitly defines it.

## Hot-path coding philosophy

Raw kernels may be aggressive.

Use explicit low-level code when it helps:

- `static final` species and layouts
- primitive locals
- simple counted loops
- `loopBound`
- scalar tails
- bit shifts for bitmap math
- masks and word-wise bitmap operations
- hardcoded happy paths for common cases
- separate methods for no-null vs nullable paths

Avoid unnecessary branches in raw loops.

When branches are unavoidable:

- move them outside the loop if possible
- split hot and cold methods
- keep the common path obvious
- avoid megamorphic calls
- avoid exception paths in the loop
- make conditions stable and predictable

Non-hot code should optimize for readability and safety.

## Expression fusion

Expression fusion is a future direction, not an MVP requirement.

The goal of fusion is to avoid:

```text
tmp1 = multiply(a, scale)
tmp2 = add(tmp1, b)
out  = greater_than(tmp2, threshold)
```

and prefer:

```text
out = greater_than(add(multiply(a, scale), b), threshold)
```

A fused kernel should:

- read input buffers once where possible
- avoid temporary Arrow buffers
- combine validity handling
- write directly to output buffers
- be benchmarked against PyArrow/Arrow compute chains

Runtime code generation may eventually be used, but only after the handwritten kernel and wrapper model is proven.

When runtime codegen is on the table, three candidate sub-paths exist on the JVM:
(a) Janino compiling generated Java source, (b) `javax.tools.JavaCompiler` (the
in-process JDK compiler) compiling generated Java source, (c) ByteBuddy/ASM
emitting bytecode directly. All three avoid an LLVM-IR layer and let HotSpot's
C2 plus the Vector API do SIMD lowering. The choice between them depends on
whether the chosen compiler accepts `import jdk.incubator.vector.*` and
produces bytecode whose imports resolve at runtime under the project's
`--add-modules jdk.incubator.vector` JVM args. SPDD 15
(`15-janino-runtime-codegen-feasibility-probe.md`) probes that question for
the Janino path against `MulFloat64Raw` and ships a binary outcome that gates
the codegen sub-path choice for any future fusion SPDD.

SPDD 15 remains probe-scoped: it adds isolated `compute.codegen` artifacts only,
without integrating runtime codegen into `Compute` or dispatch in this phase.

## Non-goals

Do not start by implementing:

- full Arrow Compute parity
- full SQL engine or Catalyst-style optimizer
- generic `Datum` abstraction
- function registry
- UDF system
- joins (any kind)
- sort engine
- distributed execution
- grouped hash aggregation engine (the **state layout** is defined; the
  hash table that maps keys to `gid` is not)
- universal expression compiler (handwritten fused kernels are MVP;
  runtime codegen is post-MVP)
- dictionary-encoded vector inputs (`DictionaryEncoding`,
  `BaseListVector`-with-dictionary, etc.). Dispatch rejects them
  explicitly with `UnsupportedOperationException`; callers must
  materialize the dictionary before invoking compute. Adding native
  dictionary handling is post-MVP.

Strings and decimal kernels are **in scope** through the two-tier
kernel design. The fast tier (simple string scanning / short-needle
ops) is part of the value prop; the slow tier (regex, full Unicode,
Decimal128/256 arithmetic) ships for API completeness with honest
benchmark gaps documented.

Start with:

```text
fixed-width primitive kernels
bitmap utilities
simple aggregations
fast-tier string kernels (equals, starts_with, is_ascii, length_bytes, ...)
correct memory handling
JMH benchmarks
```

## Development order

Suggested order:

1. Memory utilities: `BufferRefs`, `SegmentViews`, `Bitmap`, `Validity`, `Checks`
2. JMH harness
3. One raw no-null kernel, such as `AddInt32Raw`
4. Wrapper for that kernel
5. Dispatch for that operation
6. Raw nullable-safe kernels
7. Bitmap propagation tests
8. Comparisons and boolean output
9. One valid-only kernel, such as `DivInt32Raw`
10. One aggregation kernel, such as `SumInt64Raw`
11. Integration benchmark
12. Fused expression prototype

## Design decisions

A condensed record of the non-obvious choices, one-line rationale each.
When a decision is challenged, the rationale here should answer the
challenge or be updated.

- **Raw kernels are Arrow-free.** Enables testing and benchmarking raw
  kernels without any Arrow Java import — every raw kernel can be
  exercised over an FFM `Arena`-backed `MemorySegment` with no
  allocator setup.
- **Wrappers are the Arrow-Java adapter, not a safety boundary.** Per
  SPDD 13, buffer lifetime is owned by the caller; wrappers do not
  retain. Validity-buffer materialization is conditional on
  `null_count > 0`. Capacity, value-count, and slice-offset checks
  remain at the wrapper layer. Raw kernels still assume correctness;
  nothing about the raw layer changes.
- **Raw `/raw/` package is flat.** Null mode is encoded by method
  name (`computeAll` / `noNulls` / `skipNulls` / `validOnly`), not by
  package. Wrapper packages remain split because wrappers really do
  branch on null path.
- **Two-tier kernel design.** Fast tier (SIMD `raw/`) is the value
  prop; slow tier (`wrapper/slow/`) ensures API completeness. Slow ops
  have honest benchmarks. Borderline ops graduate only when proven hot.
- **Slow-tier pluggability is interface-only.** Each pluggable op has
  an interface plus a default plain-Java implementation. No
  `ServiceLoader`, no DI, no registry. Door stays open for future JNI
  or 3rd-party backends.
- **Dispatch surface is public.** External consumers can extend
  dispatch classes to plug in custom raw kernels without forking.
  Avoids `FunctionRegistry`/`Datum`/UDF infrastructure.
- **No `FunctionRegistry`, `Datum`, or UDF system in MVP.** First
  prove the JVM-native pitch holds against the arrow-rs out-of-process
  reference for fixed-width arithmetic. Generic dispatch infrastructure
  can come once a real workload demands it.
- **Options are primitive flags at wrapper signature.** No options
  objects in hot path. Raw kernels are single-mode; wrapper branches
  on flags and selects the right raw kernel.
- **SoA aggregation state.** Parallel primitive arrays keyed by group
  id. No object-per-group. Matches columnar finalization. Valhalla
  value-records can be adopted later if profiling justifies the
  switch.
- **Pre-resolved kernel handle is v2.** MVP keeps `Compute.*` static
  facade only. `Compute.resolve(...)` is documented as the eventual
  hot-call-loop path so MVP shape does not drift.
- **`MemorySegment.ofAddress(...).reinterpret(byteSize)` (two-arg
  form) is the status-quo lifetime model.** Lifetime correctness rests
  on the caller's buffer-lifetime contract per SPDD 13; segments never
  escape the wrapper call. Trade-off: safer scoped form available but
  pays per-call Arena overhead.
- **Tests run with allocator debug mode.**
  `-Darrow.memory.debug.allocator=true` catches retain/release
  imbalance at the test boundary.
- **Little-endian only.** Arrow in-memory invariant. Big-endian hosts
  out of scope; ingestion must byte-swap.
- **Single-threaded by design.** Wrappers and raw kernels are not
  thread-safe; callers serialize per-batch execution. Parallelism
  belongs to the engine (Spark executor, Flink task, ...).
- **Slice-offset rejected in MVP.** Wrappers reject vectors with
  non-zero slice offset. Offset-aware raw kernels are out of MVP
  scope.
- **JPMS / `module-info.java` is deferred.** MVP ships as a plain
  classpath JAR. JPMS revisited only when a consumer requires it.
- **Java 25+ with `jdk.incubator.vector`.** Accepts the incubator-API
  dependency. Scalar `MemorySegment` is the documented fallback if
  Vector API is reshaped or deprecated; that fallback is already a
  benchmark baseline.

## Risks & assumptions

The project has two load-bearing external dependencies that are not
under our control. They are documented here so future readers can
understand which assumptions the design takes on.

### `jdk.incubator.vector` (Vector API)

Vector API has been incubating across multiple JDK releases. The API
shape may change, and the module may be reshaped, renamed, or
deprecated.

- **Mitigation**: every fast-tier raw kernel has a naive
  `MemorySegment` loop benchmark as baseline. If Vector API is removed
  or breaks, the scalar fallback is already implemented and
  measurable. The cost would be losing the SIMD speedup, not losing
  the project.
- **Trigger to re-evaluate**: JDK release notes signal a breaking
  change, or Vector API graduates to a permanent module with a
  different API shape.

### `MemorySegment.ofAddress(...).reinterpret(byteSize)` (FFM)

The Foreign Function & Memory API is permanent in Java 22+, but
specific `reinterpret` overloads or their semantics may be tightened
between JDK versions.

- **Mitigation**: usage is centralized in `SegmentViews`. If the
  two-arg form is removed or constrained, the scoped form
  (`reinterpret(byteSize, arena, cleanup)`) is a drop-in replacement
  at a small per-call cost. Other call sites do not change.
- **Trigger to re-evaluate**: a JDK upgrade rejects the current call
  shape, or allocator-debug tests reveal lifetime issues in
  production-shape workloads.

### Performance assumption

The project assumes that, in steady-state JVM workloads over
preloaded Arrow buffers, the JVM can match within a small constant
factor of a same-host out-of-process vectorized-interpreter reference
(arrow-rs / Arrow C++) for fixed-width primitive arithmetic and bitmap
ops, and can *beat* a generic per-kernel native chain when expressions
are fused.

If steady-state benchmarks show the **thin** wrapper (per SPDD 13) is
≥ 2× slower than the out-of-process arrow-rs reference on all measured
kernels at 1M rows (where both sides are DRAM-bandwidth-bound), the
project's value proposition is disproved and the design changes
accordingly. This is the headline risk. The kill criterion deliberately
targets the thin wrapper rather than the legacy heavy wrapper, whose
structural ~2× cost is removed by SPDD 13 and is not in itself
disqualifying. The reference lives in `arrow-rs-baseline/` per
`spdd_requirements/requirements/11-bench-cleanup-and-cargo-reference.md`;
the earlier in-process per-kernel JNI trigger from `12-native-baseline.md`
is superseded.

## Summary

The core design is:

```text
raw kernels:
  small, pure, Arrow-free, SIMD-focused, single-threaded

wrappers:
  Arrow-aware, memory-safe boundaries

memory utilities:
  centralized unsafe memory/lifetime code

dispatch:
  explicit, readable, outside hot path

public API:
  small static Compute methods

tests:
  layer-specific and honest

benchmarks:
  steady-state, single-thread, layer-aware
```

No hidden framework magic is required.

---

## Arrow Java API reuse policy

The project must not reimplement Arrow Java utilities in dispatch/wrapper code without a reason.

Use Arrow Java APIs for:

- vector lifecycle and allocation
- buffer access
- validity buffer sizing and scalar bit operations via `BitVectorHelper`
- transfer/copy operations via `TransferPair`, `copyFrom`, and `copyFromSafe`
- schema and type metadata via Arrow Java POJO classes
- validation via `validate` and `validateFull`
- search/sort/dedup/dictionary operations via the Arrow Java `algorithm` module where suitable

Project-local utilities exist only where they provide a narrower project-specific boundary:

```text
SegmentViews:
  ArrowBuf/FieldVector address + checked byte size -> MemorySegment

BufferRefs:
  retain/release policy for buffers used by wrappers

Bitmap:
  raw MemorySegment / word-wise bitmap operations needed by kernels
```

`Bitmap` must not duplicate `BitVectorHelper` methods unless the duplicate exists for a measured hot path or raw `MemorySegment` usage.

See `ARROW_JAVA_API_USAGE.md`.
