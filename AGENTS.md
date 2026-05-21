# AGENTS.md

## Project stance

This project is written in **modern Java** as a JVM-native compute layer over Arrow-style columnar memory.

For iteration sequencing and dependencies, consult `DEVELOPMENT_PLAN.md`.

The goal is not to write “enterprise Java”. The goal is to write small, explicit, JIT-friendly systems code that can run inside long-lived JVM data engines without native binary distribution problems.

Prefer:

```java
final class AddFloat64Raw {
    static void computeAll(...) {
        // tight loop
    }
}
```

Avoid:

```java
AbstractVectorizedComputationExecutionServiceFactoryImpl
```

## Core principles

### 1. Hot paths are boring and explicit

Hot loops must be easy for HotSpot/Graal to optimize.

Use:

- `final` classes
- `static` methods
- primitive arguments
- explicit offsets
- `MemorySegment` / Arrow buffer addresses
- `Vector API`
- preallocated outputs
- simple `for` loops

Avoid in hot paths:

- `Stream`
- `Optional`
- boxing
- lambdas
- reflection
- virtual dispatch
- exceptions as control flow
- `BigDecimal`
- logging
- allocation inside loops
- `List<T>` / `Map<K,V>` / object-per-row layouts

### 2. Arrow buffers are the data model

Do not copy Arrow data into JVM heap structures unless a test or diagnostic explicitly requires it.

Preferred data flow:

```text
Arrow input buffer
  -> Vector API load
  -> SIMD operation
  -> Arrow output buffer
```

Avoid:

```text
Arrow input buffer
  -> int[] / double[]
  -> compute
  -> Arrow output buffer
```

### 3. Inputs are read-only by default

Treat input vectors and batches as immutable after publication.

Kernels should generally follow:

```text
read-only input Arrow buffers
+
compute
+
new/preallocated output Arrow buffers
```

In-place mutation is allowed only through APIs that explicitly say `inPlace`, and only when exclusive ownership is guaranteed.

### 4. Output allocation is not part of the hot loop

Kernels should normally receive already allocated output vectors or buffers.

Good:

```java
Kernel.eval(inputA, inputB, output, rowCount);
```

Avoid:

```java
Vector output = new IntVector(...); // inside kernel
```

Allocation policy belongs to the caller, planner, or execution context.

## Java version and language style

Target **Java 25+** unless the project explicitly changes this.

Use modern Java features when they improve clarity:

- `var` for obvious local variables
- `record` for immutable data carriers
- `sealed interface` for expression trees
- pattern matching `switch`
- `try-with-resources`
- `MethodHandle` / hidden classes where useful
- Foreign Function & Memory API where appropriate
- Vector API for SIMD kernels

Do not use “enterprise Java” patterns unless they are genuinely needed.

No dependency injection framework in compute kernels.

No framework annotations in hot-path code.

## Vector API guidelines

Use `jdk.incubator.vector`.

Typical shape:

```java
private static final VectorSpecies<Double> S = DoubleVector.SPECIES_PREFERRED;

static void computeAll(MemorySegment in, MemorySegment out, int n, double scalar) {
    var rhs = DoubleVector.broadcast(S, scalar);

    int i = 0;
    int upper = S.loopBound(n);

    for (; i < upper; i += S.length()) {
        long off = (long) i * Double.BYTES;

        var x = DoubleVector.fromMemorySegment(
            S,
            in,
            off,
            ByteOrder.LITTLE_ENDIAN
        );

        x.add(rhs).intoMemorySegment(
            out,
            off,
            ByteOrder.LITTLE_ENDIAN
        );
    }

    for (; i < n; i++) {
        long off = (long) i * Double.BYTES;
        double x = in.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, off);
        out.set(ValueLayout.JAVA_DOUBLE_UNALIGNED, off, x + scalar);
    }
}
```

### Vector API rules

- Keep species in `static final` fields.
- Use `loopBound`.
- Always handle scalar tails.
- Prefer fixed-width primitive kernels first.
- Avoid per-lane Java objects.
- Avoid `vector.get(i)` / `vector.set(i)` style access in hot loops.
- Do not assume every operation maps to a single hardware instruction.
- Be especially careful with integer division and checked arithmetic.

## Arrow buffer access

Prefer physical-layout access:

```text
data buffer
validity bitmap
offset buffer
```

Avoid high-level element access in hot loops:

```java
vector.get(i);      // avoid in hot path
vector.set(i, x);   // avoid in hot path
```

Use Arrow buffer addresses or `MemorySegment` views.

When using raw addresses, lifetime must be explicit. **Per SPDD 13
(`spdd_requirements/requirements/13-arrow-rs-peer-positioning.md`),
buffer lifetime is owned by the caller.** Wrappers do not retain; they
assume input and output `FieldVector`s remain live for the duration of
every wrapper call.

Callers (engines, test harnesses, ingestion paths) that need to keep a
buffer live across a multi-kernel pipeline or hand it off across thread
or stage boundaries retain explicitly:

```java
var data = vector.getDataBuffer();
data.getReferenceManager().retain();

try {
    // run multiple wrapper calls over the held buffer
} finally {
    data.getReferenceManager().release();
}
```

`retain()` protects lifetime, not exclusive ownership.
Wrappers do not retain/release per call on the hot path.

### Lifetime invariant for `MemorySegment` views

The project uses the **two-arg** form of `reinterpret`:

```java
MemorySegment.ofAddress(arrowBufAddress).reinterpret(byteSize);
```

This returns a **global, unrestricted** segment whose validity is *not*
linked to any `Arena` or `Scope`. Lifetime correctness depends entirely
on the caller-owned-buffer contract documented in `CORE_DESIGN.md
§Memory and lifetime model`. Therefore:

- `MemorySegment` views must never escape the wrapper call. No storing
  in fields, no returning to callers, no passing to background threads.
- Tests run with `-Darrow.memory.debug.allocator=true` so any leaked
  retain/release imbalance at the caller's batch close fails the test
  rather than silently corrupting later runs.
- The dangerous `MemorySegment.ofAddress(...)` call is centralized in
  `SegmentViews` — raw kernels and wrappers never call it directly.

### Sliced vectors

MVP rejects vectors with a non-zero slice offset. The detection
mechanism is type-dependent (Arrow Java does not expose a universal
`getSliceOffset()`); see `ARROW_JAVA_API_USAGE.md §3 Slice offsets`
for the concrete check per vector type. The check is centralized in
`Checks.zeroSliceOffset(...)` and runs before any raw kernel touches
the buffers. On a non-zero slice offset, the wrapper throws
`IllegalArgumentException`. Offset-aware kernels are out of MVP scope.

## Null semantics

Do not conflate schema nullability with runtime null presence.

Schema-level:

```text
field is nullable
```

Runtime-level:

```java
vector.getNullCount() == 0
```

Always dispatch based on runtime facts where possible.

### Null handling modes

Kernels should explicitly declare or encode their null policy.

Recommended categories:

```text
NO_NULLS
NULLABLE_COMPUTE_ALL
NULLABLE_VALID_ONLY
```

These are documentation labels, not runtime enum values. Wrappers branch
directly on `vector.getNullCount()` and the operation's own semantics —
there is no `NullMode` enum carried through the call.

#### `NO_NULLS`

No validity bitmap work is required.

Use for:

```java
left.getNullCount() == 0 && right.getNullCount() == 0
```

#### `NULLABLE_COMPUTE_ALL`

Safe for kernels where null-lane data can be treated as don't-care.

Typical rule:

```text
out_data     = compute(input_data) for all rows
out_validity = input validity rule
```

Examples:

- integer add/sub/mul
- floating add/sub/mul/div
- comparisons
- bitwise operations
- widening casts

#### `NULLABLE_VALID_ONLY`

Required when evaluating null-lane data could cause observable errors or undefined behavior.

Examples:

- integer division
- integer remainder
- checked arithmetic
- checked narrowing casts
- operations with domain errors
- parsing
- some decimal operations

Do not blindly compute these over null slots.

### Float special values

NaN and Infinity are values, not nulls. Kernels follow IEEE 754:

- Arithmetic with NaN produces NaN.
- Any comparison involving NaN is `false` (including `NaN == NaN`).
- Infinity arithmetic follows standard IEEE rules.

Specific aggregations (`min`, `max`) document their own NaN policy at the
kernel level. The default is "NaN propagates" (any-NaN-in → NaN-out); a
"skip-NaN" variant may exist later but must be named explicitly (suffix
`NanAgnostic` or a primitive flag).

NaN is never confused with the validity bitmap. A NaN value with
`validity = 1` is a valid NaN; a slot with `validity = 0` is null regardless
of its underlying byte pattern.

## Validity bitmap rules

Arrow validity uses `1 = valid`, `0 = null`. All validity-bitmap code in this
project must follow this polarity; getting it backwards is the single most
common one-bit bug class.

**Bit order within a byte is LSB-first** per the Arrow IPC spec:
row 0 = bit 0 = mask `0x01`, row 7 = bit 7 = mask `0x80`. Row 8 lives
in the next byte at bit 0. All project `Bitmap` and `Validity`
utilities — and any raw kernel that touches the bitmap directly — must
follow this convention.

For simple null-propagating binary kernels:

```text
out_validity = left_validity & right_validity
```

For unary kernels:

```text
out_validity = input_validity
```

Validity bitmap processing should be word-wise where possible:

```java
long out = leftWord & rightWord;
```

Avoid decoding validity into per-row booleans unless the kernel actually needs lane masks.

### Tail-word semantics

For row counts not divisible by 64 (or by 8 at the byte boundary), the
last word of the validity buffer contains bits beyond row `n-1`. The
project rule is:

- **Reads** beyond `n-1` in the last word are permitted. Arrow Java
  validity buffers are sized to whole bytes (and typically to 64-byte
  boundaries via `BitVectorHelper.getValidityBufferSize`), so reading
  the full last word will not overrun the buffer. Bits beyond `n-1`
  are **don't-care**.
- **Writes** of the last word may set bits beyond `n-1` as a side
  effect. Either:
  - the wrapper calls `out.setValueCount(n)` after the kernel writes,
    which clears tail bits beyond `n-1` per Arrow Java semantics, OR
  - the kernel masks out-of-range bits before writing the last word.

`Bitmap` utility methods prefer the **explicit-mask** pattern for
safety; raw kernels that drop down to word-wise writes may use either
approach. Whichever the kernel uses must be documented in its
class-level javadoc per `§Documentation`.

## Boolean output

Arrow boolean values are bit-packed.

Comparison kernels must account for this.

Do not produce `byte-per-bool` output unless it is explicitly an internal temporary representation and the packing step is measured.

## Expression fusion

The project should prefer fused expression evaluation over chains of temporary kernels.

Bad:

```text
tmp1 = multiply(a, scale)
tmp2 = add(tmp1, b)
out  = greater_than(tmp2, threshold)
```

Better:

```text
out = greater_than(add(multiply(a, scale), b), threshold)
```

A fused kernel should:

- read input buffers once where possible
- avoid temporary Arrow vectors
- combine validity handling
- write directly to output buffers

## API design

Public APIs may be pleasant.

Internal hot-path APIs should be blunt.

Good public shape:

```java
var expr = gt(add(mul(col("price"), lit(1.2)), col("tax")), lit(100.0));
var kernel = compiler.compile(expr, schema);
kernel.evaluate(inputRoot, outputRoot);
```

Good internal shape:

```java
static void computeAll(
    MemorySegment aData,
    MemorySegment bData,
    MemorySegment outData,
    MemorySegment outValidity,
    int rowCount
)
```

Raw kernel entry points use `computeAll` (or `noNulls` / `skipNulls` /
`validOnly` for explicit variants). Wrappers use `eval`. Mixing the two
names is a portability hazard for grep and for agent contributors.

The library's audience is engine and library developers, not interactive analysts. Public APIs should remain ergonomic at the dispatch surface, but the wrapper and raw layers do not hide unsafe details from kernel consumers. See SPDD 13 for the positioning rationale.

## Options and modes

Operations may have orthogonal modes: `checked` vs `unchecked` arithmetic,
NaN-propagating vs NaN-agnostic min/max, signed-zero handling, etc. The
project's option-passing rule is:

- **Wrapper signature accepts primitive flags only.** No options objects,
  records, or maps are passed into wrapper or raw layers. Example:
  ```java
  Compute.add(left, right, out, /*checked=*/ false, /*nanAgnostic=*/ false);
  ```
- **Raw kernels are single-mode.** Each mode-combination has its own raw
  class: `AddInt32Raw`, `AddInt32CheckedRaw`, `MinFloat64NanAgnosticRaw`,
  etc. The wrapper switches on the primitive flags and selects the right
  raw kernel.
- **Naming convention:** `<Op><Type>[<Mode>]Raw`. Modes are concatenated
  in a fixed order if more than one applies (e.g.,
  `AddInt32CheckedRaw`). The default mode (whatever is most common) drops
  the suffix.
- **If an op grows to a third orthogonal mode**, revisit the policy
  before suffix grammar explodes. A small per-op `<Op>Options` record at
  the wrapper layer (never crossed into raw kernels) becomes acceptable
  at that point.

This keeps the hot path object-free while still allowing the public
`Compute` API to expose orthogonal flags.

## Dispatch surface visibility

Dispatch classes (`AddDispatch`, `DivideDispatch`, `CompareDispatch`,
`AggregateDispatch`, etc.) are part of the project's **public surface**.
External consumers may extend or subclass them to plug in custom raw
kernels without forking the project. This is the lightest possible
extension story.

What this does *not* mean:

- No `FunctionRegistry`, `Datum`, or generic kernel-signature
  infrastructure is provided. Consumers extend dispatch with explicit
  Java code, not via registry calls.
- No UDF or expression-DSL machinery. The project does not aspire to be
  Catalyst.
- No public type system parallel to Arrow Java. Dispatch keys on Arrow
  Java vector classes and `getMinorType()`.

Add the bare minimum public hooks as needed; resist adding generic
registry plumbing until a real consumer demands it.

## Dispatch strategy

Use explicit specialization.

Dispatch dimensions may include:

```text
operation
physical type
null mode
scalar/vector input shape
checked/unchecked mode
output layout
```

Prefer generated or selected specialized kernels over universal `Object` dispatch.

Avoid:

```java
Object eval(Object left, Object right);
```

Prefer:

```java
AddInt32VectorVector.eval(...);
AddInt32VectorScalar.eval(...);
AddFloat64VectorScalar.eval(...);
```

## Runtime code generation

Runtime-generated kernels are allowed and encouraged for fused expressions.

Acceptable strategies:

1. handwritten Java kernels for primitives
2. generated Java source compiled at runtime
3. generated bytecode
4. hidden classes
5. `MethodHandle`-based linkage

Requirements:

- cache generated kernels
- use stable cache keys
- avoid unbounded Metaspace growth
- expose diagnostics for generated code
- benchmark generated kernels separately

Suggested cache key components:

```text
canonical expression
input physical types
null policies
output physical types
target vector species category
checked/unchecked mode
```

## Error handling

Do not use exceptions for normal row-level control flow.

For kernels that can fail:

- define clear checked/unchecked semantics
- precheck where possible
- report errors with enough context for debugging
- do not throw per row in a tight loop

### Exception types

All `Errors.*` helpers and all wrapper-thrown exceptions are **unchecked**.
No checked exceptions on hot or cold paths.

| Situation | Exception type |
|---|---|
| Unsupported type combination at dispatch | `UnsupportedOperationException` |
| Size / shape / capacity / slice-offset / precision-scale mismatch | `IllegalArgumentException` |
| Domain error (divide-by-zero, checked overflow, checked narrowing cast, invalid parse, invalid decimal op) | `ArithmeticException` |

When the failing row is known (the precheck found a specific offender),
the message includes the row index of the **first** offender. Messages
also name the input type for debugging when relevant.

### Precheck-before-loop rule for checked kernels

**Active rows** are rows where *all* input validities are true — for a
binary kernel this is `left_validity & right_validity`; for n-ary
kernels it is the AND of every input's validity. Inactive null rows are
never inspected for domain errors and never trigger throws.

For any kernel that can encounter a domain error (division by zero,
checked overflow, checked narrowing cast, invalid parse), the wrapper
**must precheck the active rows before the compute loop starts**. If
the precheck finds an offending row, the wrapper throws
`ArithmeticException` carrying the row index of the first offender, and
the compute loop never runs.

This rule has two consequences:

- No per-row `try`/`throw` in the hot loop. The hot loop is a tight,
  branch-light scan after precheck has verified there are no bad rows
  among the active set.
- No partial writes to the output on failure: precheck throws **before**
  any segment is created or any compute begins.

Examples of dangerous operations:

- integer division by zero
- overflow in checked arithmetic
- checked casts
- invalid parse
- invalid decimal operation

`Integer.MIN_VALUE / -1` (and the int64 analogue) is treated as a
checked-overflow case under the same precheck rule: the wrapper
inspects active divisors for both `0` and the trap pattern before the
loop runs.

## Benchmarking

Every serious kernel must have JMH benchmarks.

Benchmark at least:

```text
no nulls
1% nulls
10% nulls
30% nulls
small batches
medium batches
large batches
scalar input
vector input
aligned-ish offsets
unaligned offsets
```

Measure:

- throughput
- allocation rate
- branch behavior if possible
- warmup sensitivity
- scalar fallback performance
- comparison against a simple Java scalar baseline
- comparison against Arrow C++ / PyArrow where practical

Do not trust microbenchmarks until dead-code elimination is ruled out.

Use realistic output consumption.

## Profiling

Use:

- JMH
- JFR
- async-profiler
- JITWatch / perfasm where useful
- GC logs when allocation behavior matters

Look for:

- unexpected allocation
- boxing
- failed inlining
- reflection
- bounds checks in hot loops
- virtual calls in hot loops
- poor vectorization
- memory bandwidth saturation

## Dependencies

Keep the kernel layer dependency-light.

Allowed in core kernel code:

- JDK
- Arrow Java memory/vector modules
- JMH for benchmarks

Be cautious with:

- logging frameworks
- dependency injection
- collections libraries
- async frameworks
- runtime reflection libraries

No dependency should enter a tight loop.

## Testing

Correctness tests must cover:

- empty arrays
- single-row arrays
- non-multiple-of-species row counts
- all-valid
- all-null
- alternating nulls
- random nulls
- scalar/vector combinations
- boundary numeric values
- NaN / Infinity for floating point
- integer overflow cases
- division by zero cases
- slicing / non-zero offsets if supported

Use property-based tests where practical.

For nullable kernels, assert both:

```text
output data where valid
output validity bitmap
```

Do not assert data values in null slots unless the kernel explicitly defines them.

## Code style

Prefer short names in small scopes:

```java
int n;
int i;
long off;
var x;
var y;
```

Prefer descriptive names at API boundaries:

```java
rowCount
inputValidity
outputValidity
leftData
rightData
```

Avoid ceremonial names.

Bad:

```java
VectorizedIntegerAdditionKernelExecutionContext
```

Good:

```java
AddInt32Context
```

## Comments

Comment why, not what.

Good:

```java
// Compute null lanes too: add is safe on arbitrary bit patterns,
// and output validity is handled separately.
```

Bad:

```java
// Increment i by species length.
```

## Documentation

Document each kernel with:

```text
operation
supported physical types
null policy
checked/unchecked behavior
output validity rule
aliasing assumptions
in-place support, if any
```

Example:

```text
AddInt32
- inputs: int32 vector/vector or vector/scalar
- null policy: null-propagating
- safe on null data: yes
- output validity: left & right
- overflow: Java int wraparound unless checked variant
- input mutation: never
```

## Slow-tier kernels

The project ships a **two-tier kernel design** (see `CORE_DESIGN.md
§Two-tier kernel design` for the full taxonomy). Some operations are not
amenable to Vector API SIMD — variable-width strings beyond simple
scanning, full Unicode, regex, locale-aware compare, and all
Decimal128/Decimal256 arithmetic. These ship as **slow-tier kernels** so
the library is API-complete; users decide based on their workload mix.

### Location

Slow-tier kernels live in `wrapper/slow/`. There is no `raw/slow/`
package — slow-tier ops do not have a raw layer because they would not
benefit from one.

### Coding rules (relaxed vs raw)

Slow-tier kernels follow looser rules than raw kernels:

- **Allowed**: Arrow Java accessors (`vector.get(i)`,
  `vector.getValueLength(i)`, `vector.getOffsetBuffer()`), plain
  `for`/`while` loops, Arrow Java algorithm module, `BitVectorHelper`.
- **Still forbidden**: per-row allocation in the hot loop, boxing of
  primitives, streams, lambdas inside the inner loop, reflection,
  per-row exceptions for normal control flow. Allocation discipline
  still applies.

The point is: write plain readable Java, don't pretend to SIMD when you
can't.

### Pluggability

Slow ops that have plausible alternative backends (JNI to Arrow C++,
re2j, Hyperscan-Java, native ICU) expose an **interface plus default
plain-Java implementation**, named explicitly:

```java
public interface RegexMatcher {
    void match(VarCharVector input, String pattern, BitVector out);
    static RegexMatcher defaultMatcher() { return new JavaUtilRegexMatcher(); }
}
```

Callers can pass a custom impl per call or swap the static factory
globally. No `ServiceLoader`, no DI framework. This keeps the door open
for future backends without introducing registry infrastructure.

### Benchmark requirement

Every slow-tier kernel **must** ship with a benchmark comparing it
against PyArrow (and, when available, a native baseline). The benchmark
report must explicitly say "this op is in the slow tier" so users see
the honest gap. See `BENCHMARKS.md §Slow-tier benchmarks`.

### Borderline ops

A few ops are SIMD-able in theory but not worth the engineering cost
until benchmarks prove they're hot. Start them in the slow tier and
graduate to fast tier only when a real workload demands it. Current
borderline list: `length_chars` (UTF-8 codepoint count), simple `LIKE`
patterns (`prefix%`, `%suffix`, `%middle%`).

## Non-goals for early versions

Do not start by implementing everything.

Early versions should not prioritize:

- full Arrow Compute parity
- function registry / `Datum` abstraction / UDF infrastructure
- SQL engine or Catalyst-style optimizer
- hash joins
- sort engine
- complex nested types beyond `List<Utf8>`
- distributed execution
- grouped hash aggregation engine (the aggregation **state layout** is
  defined, but the hash table is not part of MVP)
- universal expression optimizer

Strings and decimal kernels are **in scope** through the two-tier kernel
design (see §"Slow-tier kernels" below): the fast tier covers
SIMD-friendly string ops and is part of the project's value prop; the
slow tier ships for API completeness, and its performance is *not*
claimed as part of the MVP value prop.

Start with:

```text
fixed-width primitives
+
bitmap utilities
+
simple expression fusion
+
fast-tier string ops
+
excellent benchmarks
```

## Preferred development order

1. Buffer and lifetime utilities
2. JMH harness
3. no-null primitive arithmetic kernels
4. nullable compute-all primitive arithmetic kernels
5. bitmap kernels
6. comparison kernels with boolean output
7. simple casts
8. fused expression prototype
9. nullable valid-only kernels
10. broader type coverage

## Safety reminders

Raw memory access can crash the JVM or corrupt data.

Always be explicit about:

- buffer lifetime
- buffer size
- byte order
- row count
- offsets
- aliasing
- ownership

Do not keep `MemorySegment` views beyond the lifetime of their Arrow buffers.

Do not assume `retain()` means exclusive ownership.

Do not mutate input buffers unless the API explicitly allows it.

### Default invariants

The following project-wide invariants apply unless a kernel explicitly
documents otherwise. Wrappers enforce them at the boundary; raw kernels
assume them:

- **Non-aliasing**: inputs and outputs do not alias. Wrappers must not
  pass overlapping segments to raw kernels.
- **Little-endian**: all raw kernels assume little-endian buffers (Arrow
  in-memory invariant). Big-endian hosts are out of MVP scope.
- **Zero slice offset**: wrappers reject vectors with non-zero slice
  offset (Arrow Java slices share buffers with their parent, but the raw
  `MemorySegment` view does not encode the slice offset).
- **Single-threaded**: wrappers and raw kernels are not thread-safe.
  Callers serialize per-batch execution.
- **Caller-owned outputs**: outputs are preallocated by the caller and
  assumed to be exclusively owned for the duration of the call.
- **Caller-owned input/output buffer lifetime**: per SPDD 13, callers
  must keep all input and output `FieldVector`s live for the duration of
  every wrapper call. Wrappers do not retain. Slow-tier wrappers in
  `wrapper/slow/` follow the same rule.

## Project motto

Write Java like a systems language:

```text
small classes
static kernels
primitive data
explicit memory
measured performance
no ceremony in hot paths
```

Modern Java is good enough for this.

---

## Arrow Java API first

Before adding a project-local helper, check whether Arrow Java already provides it.

Prefer Arrow Java APIs in dispatch, wrappers, tests, ingestion, interop, and non-hot code.

Use Arrow Java for:

- `ValueVector` / `FieldVector` lifecycle APIs
- buffer accessors
- `getValueCount`, `setValueCount`, `getNullCount`, `getValueCapacity`
- `getDataBuffer`, `getValidityBuffer`, `getOffsetBuffer`
- `getDataBufferAddress`, `getValidityBufferAddress`, `getOffsetBufferAddress`
- `BitVectorHelper` for validity buffer sizing and scalar bit operations
- `TransferPair` and `copyFrom` / `copyFromSafe` for non-hot copying
- `validate` / `validateFull` in tests
- Arrow Java `algorithm` module for search, sort, dedup, and dictionary encoding
- Arrow Java POJO schema/type classes: `Schema`, `Field`, `FieldType`, `ArrowType`

Do not create a parallel public type system.

Project-local helpers are allowed when they centralize unsafe memory handling, bridge Arrow buffers to `MemorySegment`, or implement raw hot-path SIMD loops.

Raw kernels remain Arrow-free.

See `ARROW_JAVA_API_USAGE.md`.
