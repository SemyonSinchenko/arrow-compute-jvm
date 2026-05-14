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
