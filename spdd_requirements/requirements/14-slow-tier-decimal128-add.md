# Requirement: Slow-Tier Decimal128 — AddDecimal128

## Business requirement

First numeric slow-tier op. Establishes the two-long Decimal128 arithmetic pattern (Java has no `__int128`) and validates the slow-tier kernel template (from `13-slow-tier-scaffold.md`) on numeric input rather than string input.

Decimal128 arithmetic is honestly slower than native (Vector API has no 128-bit integer lane); the project ships it for API completeness with an honest PyArrow benchmark gap.

## Scope

Create the full vertical for `AddDecimal128` over Arrow Java `DecimalVector`:

```text
wrapper/slow/AddDecimal128.java
dispatch/DecimalDispatch.java
Compute.add(...)   (extended with new dispatch branch for DecimalVector)
```

No `raw/` layer for this op (slow tier).

No new pluggable interface — `RegexMatcher` (from `13-slow-tier-scaffold.md`) is the template, but for `AddDecimal128` there is no plausible JNI/3rd-party split worth designing in this iteration. Future `AddDecimal128Native` would replace the wrapper, not slot behind an interface.

## Wrapper behavior

`AddDecimal128.eval(DecimalVector left, DecimalVector right, DecimalVector out)`:

- Validate that left, right, out have matching precision and scale; throw `IllegalArgumentException` otherwise.
- `Checks.outputCapacity(out, n)`.
- `Checks.zeroSliceOffset(left, right)`.
- Validity: propagate binary via `Validity.propagateBinary(left, right, out, n)` (or `markAllValid` if both inputs `getNullCount() == 0`).
- Walk rows in a plain `for` loop:
  - Read two longs per Decimal128 value from the data buffer (16 bytes per value, little-endian).
  - Perform two-long add with carry (manual carry; `Long.addUnsigned` not needed because Java overflow semantics give the wraparound result; the carry into the high half is derived from compare).
  - Write the result two-long pair to the output buffer.
- `out.setValueCount(n)`.

Implementation hint (for clarity, not contract):

```java
long aLo = ...; long aHi = ...;
long bLo = ...; long bHi = ...;
long sumLo = aLo + bLo;
long carry = ((aLo & bLo) | ((aLo | bLo) & ~sumLo)) >>> 63;
long sumHi = aHi + bHi + carry;
```

The wrapper may use Arrow Java accessors (`vector.getObject(i)` returns `BigDecimal`) only in tests / fallback paths, not in the hot loop. Hot loop uses direct buffer access.

## Semantics

- **Precision and scale**: must match across inputs and output. Mismatch throws before the loop.
- **Overflow**: Java two-long wraparound (matches Arrow's default for unchecked Decimal128 add). Checked variant `AddDecimal128Checked` deferred.
- **Null policy**: null-propagating; output validity = `left_validity & right_validity`. Output data on null lanes is don't-care.
- **Endianness**: little-endian buffers (Arrow invariant).
- **Non-aliasing**: inputs and output do not overlap (project invariant; wrapper-level contract plus caller convention). `Checks.zeroSliceOffset` enforces slice correctness, not alias detection.

## Public API

Extend `Compute.add(...)` dispatch to route `DecimalVector + DecimalVector → DecimalVector` to `AddDecimal128`. No new public method shape.

## Tests

Wrapper tests with Arrow (no separate raw tests — slow tier):

- Empty, single row, multiple rows.
- Positive + positive, negative + positive, positive + negative, negative + negative.
- Near-overflow boundaries (max+1, min-1, max+max).
- Mixed null patterns: all-valid, sparse nulls, dense nulls, all-null.
- Precision/scale mismatch throws before the loop.
- Output validity correct.
- Allocator-debug-mode test JVM; no leaks.
- Reference correctness: `BigInteger` add for the expected values; assert bit-exact equality of the two-long pair against the reference.

Edge cases that need explicit coverage:

- Two values whose low halves sum to exactly `Long.MIN_VALUE` (carry boundary).
- Negative numbers represented as two's complement across both longs.

## Benchmarks

- Project plain-Java vs PyArrow `pa.compute.add` over preloaded Decimal128 batches.

Required dimensions:

- Rows: 1K, 16K, 64K, 1M.
- Null profiles: 0%, 10%.
- Precision: 18-digit (fits in one long), 38-digit (full Decimal128).

Report explicitly labels result as **SLOW tier** and cross-references `CORE_DESIGN.md §Two-tier kernel design`. Print numbers, do not editorialize gap size.

## Non-goals

- `AddDecimal128Checked` (overflow-detecting variant) deferred.
- `SubDecimal128`, `MulDecimal128`, `DivDecimal128` deferred.
- Comparisons (`<`, `==`, …) on Decimal128 deferred.
- Casts to/from float / int / string deferred.
- Decimal256 deferred.
- A shared two-long primitive helper class (`UInt128.addWithCarry`) is **not required** for this iteration; inline the carry math. A helper can be extracted once a second Decimal128 op needs it.

## Acceptance criteria

- `Compute.add(left, right, out)` works for `DecimalVector` end-to-end.
- Wrapper tests pass, including the carry-boundary and two's-complement edge cases.
- JMH benchmark runs and produces interpretable SLOW-tier labeled results vs PyArrow.
- Two-long add-with-carry pattern is documented well enough that future Decimal128 ops (sub, mul, etc.) reuse it.

## Cross-references

- `AGENTS.md §Slow-tier kernels`, §Default invariants.
- `CORE_DESIGN.md §Two-tier kernel design`.
- `ARROW_JAVA_API_USAGE.md §15 Slow tier uses Arrow Java fully`.
- `BENCHMARKS.md §Slow-tier benchmarks`.
- `13-slow-tier-scaffold.md` — sibling slow-tier op (regex). This spdd adopts the slow-tier coding rules but does not need the interface-pluggability mechanism.
