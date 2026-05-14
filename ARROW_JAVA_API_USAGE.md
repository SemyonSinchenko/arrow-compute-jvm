# Arrow Java API Usage Notes

This document lists Arrow Java APIs and utilities that should be used before introducing project-local helpers.

This file defines API-usage policy; iteration order and dependency planning
live in `DEVELOPMENT_PLAN.md`.

The project should not reimplement Arrow Java functionality in dispatch/wrapper code unless:

1. Arrow Java does not provide the needed operation.
2. Arrow Java provides it but it is unsuitable for the raw hot path.
3. A benchmark proves that a custom implementation is required.
4. The custom helper centralizes unsafe memory handling that Arrow Java does not expose in the needed form.

Raw kernels remain Arrow-free. This document mainly applies to dispatch, wrappers, memory utilities, tests, and non-hot orchestration code.

---

## 1. Guiding rule

Before creating a utility class, check whether Arrow Java already has the equivalent.

Prefer Arrow Java for:

- vector allocation and capacity management
- buffer access
- validity bitmap sizing and simple bit operations
- transfer/copy operations
- vector validation
- schema/type metadata
- dictionary encoding utilities
- sorting/searching/dedup algorithms when those are the task
- C Data Interface interop
- dataset loading
- memory accounting and debug tools

Project-local helpers are allowed when they provide a narrower bridge into raw kernels, for example:

```text
Arrow vector -> checked MemorySegment view
```

or when they implement SIMD compute kernels that Arrow Java does not provide.

---

## 2. Memory management

Use Arrow Java memory semantics as the source of truth.

Important Arrow Java concepts:

- `ArrowBuf` represents a contiguous region of direct memory.
- `BufferAllocator` accounts for memory and should be passed through APIs instead of hardcoding `RootAllocator`.
- Applications usually create one `RootAllocator` at program start and child allocators where useful.
- Arrow Java uses manual reference counting for direct memory.
- `ArrowBuf.getReferenceManager().retain()` increments the reference count.
- `ArrowBuf.getReferenceManager().release()` decrements it.
- Vectors and allocators implement `AutoCloseable`.
- Arrow has allocator debug mode: `-Darrow.memory.debug.allocator=true`.

Project rules:

- Follow Arrow Java allocator/refcount rules.
- Use `try-with-resources`.
- Prefer child allocators in tests that validate leak behavior.
- Do not bypass `BufferAllocator` for Arrow-owned output allocation.
- Do not keep raw `MemorySegment` views past Arrow buffer lifetime.

---

## 3. FieldVector and ValueVector APIs to prefer

Arrow Java already exposes buffer and vector lifecycle APIs.

Use these in wrappers and dispatch:

```java
vector.getValueCount();
vector.setValueCount(n);
vector.getNullCount();
vector.getValueCapacity();
vector.getBufferSize();
vector.getBufferSizeFor(n);
vector.getDataBuffer();
vector.getValidityBuffer();
vector.getOffsetBuffer();
fieldVector.getDataBufferAddress();
fieldVector.getValidityBufferAddress();
fieldVector.getOffsetBufferAddress();
fieldVector.getFieldBuffers();
vector.allocateNew();
vector.allocateNewSafe();
vector.setInitialCapacity(n);
vector.reAlloc();
vector.reset();
vector.clear();
vector.close();
vector.validate();
vector.validateFull();
```

Do not invent separate type metadata when Arrow Java already provides:

```java
vector.getField();
vector.getMinorType();
vector.getField().getType();
```

Dispatch should primarily use Arrow Java vector classes and Arrow Java type metadata.

### Slice offsets

MVP wrappers reject vectors that carry a non-zero slice offset (see
`CORE_DESIGN.md §Raw kernel layer`). Arrow Java does **not** expose a
universal `getSliceOffset()` on `FieldVector`; the detection mechanism
is type-dependent. The check is centralized in
`Checks.zeroSliceOffset(FieldVector... vectors)` (varargs) and runs at
the wrapper boundary before raw kernels touch the buffers.

Concrete per-type behavior:

- **Fixed-width** (`BaseFixedWidthVector` — `IntVector`, `BigIntVector`,
  `Float4Vector`, `Float8Vector`, `DecimalVector`, ...): the slice
  concept does not apply post-`TransferPair.splitAndTransfer` because
  the new vector owns its own buffer with logical row 0. MVP wrappers
  document the assumption "row 0 starts at byte 0 of the data buffer"
  and `Checks.zeroSliceOffset` is a no-op for these types.
- **Variable-width** (`BaseVariableWidthVector` — `VarCharVector`,
  `VarBinaryVector`): check `getOffsetBuffer().getInt(0) == 0`. A
  non-zero first offset indicates the vector logically starts mid-data;
  reject with `IllegalArgumentException`.
- **Dictionary-encoded** vectors: out of MVP scope per
  `CORE_DESIGN.md §Non-goals`; dispatch rejects them with
  `UnsupportedOperationException` before slice-offset checking would
  even apply.

Raw kernels themselves never inspect slice offsets and never hold an
Arrow Java vector reference.

---

## 4. SegmentViews is still allowed, but it must be thin

`SegmentViews` should not duplicate Arrow Java vector logic.

Its job is only to safely bridge:

```text
ArrowBuf / FieldVector address + checked byte size
  -> MemorySegment
```

Allowed responsibilities:

- check requested byte size against `ArrowBuf.capacity()`
- use Arrow Java address methods
- create `MemorySegment.ofAddress(address).reinterpret(byteSize)`
- centralize this dangerous operation so raw kernels never do it

Forbidden responsibilities:

- type dispatch
- allocation policy
- validity semantics
- arithmetic
- copying
- transfer logic

---

## 5. BitVectorHelper before custom bit helpers

Arrow Java has `BitVectorHelper`.

Use it for non-hot or wrapper-level operations such as:

```java
BitVectorHelper.byteIndex(index);
BitVectorHelper.bitIndex(index);
BitVectorHelper.getValidityBufferSize(valueCount);
BitVectorHelper.getNullCount(validityBuffer, valueCount);
BitVectorHelper.checkAllBitsEqualTo(validityBuffer, valueCount, true);
BitVectorHelper.get(buffer, index);
BitVectorHelper.setBit(validityBuffer, index);
BitVectorHelper.unsetBit(validityBuffer, index);
BitVectorHelper.setValidityBit(validityBuffer, index, value);
BitVectorHelper.concatBits(input1, bits1, input2, bits2, output);
```

Project-local `Bitmap` is still allowed for hot or word-wise operations not directly provided by Arrow Java, such as:

```text
bitmap AND
bitmap OR
bitmap AND_NOT
bulk fill
word-wise output validity construction
raw MemorySegment bitmap operations
```

Rule:

```text
Use BitVectorHelper for correctness, tests, sizing, scalar bit operations, and wrapper code.
Use project Bitmap only for operations needed by raw kernels or performance-critical bitmap loops.
```

If project `Bitmap` duplicates an Arrow Java method, delete the project method or justify it with a benchmark.

---

## 6. TransferPair and copy APIs before custom copying

Arrow Java provides vector copy/transfer APIs.

Use these outside raw kernels:

```java
vector.getTransferPair(allocator);
vector.getTransferPair(name, allocator);
vector.getTransferPair(field, allocator);
vector.makeTransferPair(target);
transferPair.transfer();
transferPair.splitAndTransfer(start, length);
transferPair.copyValueSafe(from, to);
vector.copyFrom(fromIndex, thisIndex, fromVector);
vector.copyFromSafe(fromIndex, thisIndex, fromVector);
```

Use these for:

- slicing/splitting vectors
- copying values in tests
- fallback paths
- building expected outputs
- non-hot transformations
- interop code

Do not use them inside raw SIMD kernels.

---

## 7. Validation APIs

Use Arrow Java validation in wrapper/integration tests:

```java
vector.validate();
vector.validateFull();
```

Validation is not part of hot-path execution.

Recommended pattern:

```java
Compute.add(left, right, out);
out.validateFull();
```

in tests.

Do not call validation in raw kernels or hot measured benchmark paths.

---

## 8. Algorithm module

Arrow Java has an `algorithm` module with utilities for:

- vector element equality
- vector ordering comparison
- linear search
- binary search
- parallel search
- range search
- in-place sorting
- out-of-place sorting
- index sorting
- deduplication
- dictionary encoding

Use Arrow Java algorithms when the task is search/sort/dedup/dictionary encoding and performance is acceptable.

Do not implement a custom sorter/searcher/dictionary encoder before checking the Arrow Java algorithm module.

For compute kernels, especially primitive arithmetic and fused expressions, custom raw kernels still make sense because Arrow Java algorithm utilities are not a replacement for Arrow C++ Compute-style SIMD kernels.

---

## 9. Schema and type metadata

Use Arrow Java POJO types:

```java
Schema
Field
FieldType
ArrowType
DictionaryEncoding
ExtensionTypeRegistry
```

Do not create a parallel public type system.

Internal compact dispatch keys may be introduced later only if direct dispatch becomes painful or benchmark data shows that type checks matter.

Initial dispatch should use:

```java
instanceof IntVector
instanceof Float8Vector
vector.getField().getType()
vector.getMinorType()
```

---

## 10. Dataset, IPC, and C Data Interface

For ingestion and interop, prefer Arrow Java modules:

- Dataset for reading datasets and CSV where useful.
- IPC readers/writers for Arrow streams/files.
- C Data Interface for cross-language/native interop.
- Flight/Flight SQL for transport if needed.

These are outside raw kernel design.

Do not build custom CSV, IPC, or C Data interop unless the benchmark explicitly targets parser/interop work.

---

## 11. Output allocation and capacity

Use Arrow Java vector allocation APIs:

```java
out.setInitialCapacity(n);
out.allocateNew();
out.allocateNewSafe();
out.reAlloc();
out.getValueCapacity();
out.setValueCount(n);
```

Project rules:

- Hot kernels write to preallocated output buffers.
- Wrappers check capacity or allocate in explicitly named convenience paths.
- Benchmarks must distinguish preallocated-output mode from allocate-output mode.
- Raw kernels never allocate Arrow vectors or Arrow buffers.

---

## 12. What should remain custom

The project should still implement:

- raw SIMD arithmetic kernels
- raw comparison kernels
- raw aggregation kernels
- word-wise bitmap operations needed by kernels
- MemorySegment bridge from ArrowBuf with strict bounds checks
- fused expression kernels
- benchmark harnesses
- dispatch/wrapper glue

These are project-specific and not replaced by Arrow Java utility APIs.

---

## 13. Checklist before adding a helper

Before adding a utility method, ask:

1. Does Arrow Java already expose this?
2. Is the operation in dispatch/wrapper/test code?
3. Is this method in a raw hot loop?
4. Does Arrow Java's API use ArrowBuf while raw code needs MemorySegment?
5. Is the custom code justified by safety, layering, or benchmark data?

If the operation is non-hot and Arrow Java supports it, use Arrow Java.

If the operation is hot and Arrow Java only provides scalar/object-oriented access, a custom raw implementation may be appropriate.

---

## 14. Documentation rule

When adding a custom helper that overlaps with Arrow Java, document why it exists.

Example:

```java
// Deliberately not using BitVectorHelper here:
// this is a raw MemorySegment word-wise AND used in hot validity propagation.
```

Example:

```java
// Use BitVectorHelper.getValidityBufferSize instead of duplicating sizing logic.
```

---

## 15. Slow tier uses Arrow Java fully

The project ships a **two-tier kernel design** (see `CORE_DESIGN.md
§Two-tier kernel design`). Slow-tier kernels live in `wrapper/slow/`
and have **no raw layer**.

This is the place in the codebase where Arrow Java APIs should be used
without apology:

- Walk vectors with `vector.get(i)`, `vector.getValueLength(i)`,
  `vector.getDataBuffer()`, `vector.getOffsetBuffer()`.
- Use the `algorithm` module (see §8) for search, sort, dedup,
  dictionary encoding when those are the task.
- Use `BitVectorHelper` (see §5) for scalar bit operations and
  validity sizing.
- Use `TransferPair` / `copyFromSafe` (see §6) for any value copying
  the slow-tier op needs.

Slow-tier kernels do *not* need to:

- create `MemorySegment` views (the operation is not SIMD-able);
- avoid Arrow imports (they are wrapper-level, not raw);
- avoid per-vector allocation outside the inner loop (e.g.,
  precompiling a regex `Pattern` once per call is fine).

What still applies:

- No per-row allocation inside the inner loop. No boxing. No
  per-row streams or lambdas. No reflection.
- Pluggable slow ops use interface + default impl (see
  `CORE_DESIGN.md §Two-tier kernel design`). The default
  implementation typically uses `java.util.regex` (for regex),
  `java.lang.String` byte access, `java.math.BigInteger` (for
  Decimal128 if no two-long primitive helper exists yet).
- Benchmarks compare against PyArrow / native baselines so the
  honest gap is visible.

Fast-tier raw kernels remain Arrow-free; this section explicitly does
**not** relax that rule.
