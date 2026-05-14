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
