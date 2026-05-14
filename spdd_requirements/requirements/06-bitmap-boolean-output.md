# Requirement: Bitmap and Boolean Output Support

## Business requirement

Support Arrow-style bit-packed boolean output for comparison kernels and validity operations.

## Scope

Implement or refine bitmap support and comparison output support.

## Arrow Java first

Use `BitVectorHelper` (foundation `ARROW_JAVA_API_USAGE.md §5`) for:

- validity buffer sizing (`getValidityBufferSize`);
- scalar get/set in non-hot code (`get`, `setBit`, `unsetBit`, `setValidityBit`);
- null count checks (`getNullCount`, `checkAllBitsEqualTo`);
- test helpers.

Use project `Bitmap` only for:

- word-wise operations on raw `MemorySegment` buffers (AND, OR, AND_NOT, NOT);
- hot loops where per-call Arrow Java overhead matters.

## Required behavior

Comparison kernels (when added later — e.g., `GreaterInt32`) write Arrow-compatible boolean output. Account for:

- bit-packed value buffer (1 bit per row, LSB-first within a byte);
- bit-packed output validity buffer (separate from value buffer);
- tail bits in the last byte for non-multiple-of-8 row counts;
- non-multiple-of-64 row counts (last word in word-wise writes);
- correct interaction with `BitVector.setValueCount(n)` — Arrow Java's `setValueCount` may clear or set tail bits. Wrappers either:
  (a) call `setValueCount(n)` **before** the kernel writes data and then make tail-aware writes, OR
  (b) call `setValueCount(n)` **after** the kernel writes data, accepting that Arrow Java may clear tail bits beyond row `n-1` (which is exactly what we want for indices outside the valid range).

The implementation picks one approach and documents it in the wrapper class javadoc. Pattern (b) is usually safer; the kernel writes the full last word, and `setValueCount(n)` cleans up.

## Tests

Run with `-Darrow.memory.debug.allocator=true`. Cover:

- all-false output;
- all-true output;
- alternating values;
- random values (fixed seed `0xC0FFEEL`);
- row counts not divisible by 8 (tail-bit correctness);
- row counts not divisible by 64 (word-wise tail correctness);
- null propagation through binary bitmap ops;
- `BitVector.validateFull()` on the output;
- intentional tail-bit corruption regression test (e.g., write an out-of-range bit, assert `validateFull()` catches it).

## Benchmarks

- Bitmap AND (project `Bitmap.and`) vs scalar Java loop;
- Boolean output packing (e.g., a hypothetical `GreaterInt32Raw` that produces bit-packed output) vs naive Java byte-per-bool then pack;
- Comparison raw kernel vs naive Java (when comparison kernels land — this iteration may stub the comparison or defer to a follow-up).

## Non-goals

- Complex boolean Kleene logic (three-valued logic for nullable booleans). Standard two-valued AND/OR/NOT only.
- Filter / take kernels (consumer of bit-packed booleans; out of scope here).
- Comparison kernels themselves — they may land in this iteration if scope permits, or in a follow-up.

## Acceptance criteria

- Boolean output matches Arrow layout (LSB-first within byte, validity in a separate bit buffer).
- Tail bits are correct for non-multiple-of-8 and non-multiple-of-64 row counts.
- `BitVector.validateFull()` passes on all wrapper outputs.
- `Bitmap` operations have unit tests covering AND/OR/AND_NOT/NOT and tail-bit edges.

## Cross-references

- `CORE_DESIGN.md §Bitmap`, §Validity bitmap rules.
- `AGENTS.md §Validity bitmap rules`, §Boolean output.
- `ARROW_JAVA_API_USAGE.md §5 BitVectorHelper`.
