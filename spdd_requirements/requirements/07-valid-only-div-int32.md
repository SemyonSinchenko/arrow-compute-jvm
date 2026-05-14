# Requirement: First Valid-Only Kernel — DivInt32

## Business requirement

Implement the first kernel that must not execute on null slots. Integer division is not safe on arbitrary null-slot data because divisor data may contain zero. This iteration also establishes the **precheck-before-loop** error contract that all future checked kernels follow.

## Scope

Create:

```text
raw/DivInt32Raw.java
wrapper/validonly/DivInt32.java
dispatch/DivideDispatch.java
```

Raw kernel lives in the **flat** `raw/` package. Wrapper lives in `wrapper/validonly/` (foundation `CORE_DESIGN.md §Package layout`).

Support `IntVector / IntVector → IntVector`.

## Semantics

Integer division is not safe on arbitrary null-slot data because divisor data may contain zero. Additionally, `Integer.MIN_VALUE / -1` traps with overflow.

### No-null path

`raw/DivInt32Raw.noNulls(MemorySegment left, MemorySegment right, MemorySegment out, int n)`:

- Per-row Java integer division `left[i] / right[i]`.
- Wrapper has already prechecked `right` (see below); the raw kernel does not re-check.
- Vector API where it helps; integer division may fall back to scalar.

### Nullable valid-only path

`raw/DivInt32Raw.validOnly(MemorySegment left, MemorySegment right, MemorySegment out, MemorySegment activeValidity, int n)`:

- Compute division only where `activeValidity[i] == 1`.
- Inactive (null) rows: do not read divisor, do not throw, do not compute. Output data is don't-care.
- For MVP this path may be scalar (no Vector API).

`activeValidity` = `left_validity & right_validity`.

## Precheck-before-loop error contract

Foundation `AGENTS.md §Error handling §Precheck-before-loop rule` and `CORE_DESIGN.md §Safe vs valid-only kernels §Precheck-before-loop for checked kernels` define the contract. Concrete behavior for `DivInt32`:

The **wrapper** prechecks **active rows** (rows where `activeValidity[i] == 1`) for:

- divisor = 0
- `(left[i] == Integer.MIN_VALUE) && (right[i] == -1)` (overflow trap pattern)

If any active row violates either condition:

- the wrapper throws `ArithmeticException` (built via `Errors.divByZero(rowIndex)` or `Errors.overflow(rowIndex)`);
- the exception message carries the row index of the **first** offender;
- the compute loop never runs;
- `out` is not partially written;
- `setValueCount(n)` is not called.

Inactive null rows are **never** inspected and **never** trigger throws. They produce null output regardless of underlying bytes.

Precheck happens **before** the try-with-resources that creates `MemorySegment` views — or inside it but before the raw call — implementer's choice; correctness requirement is "no compute begins before precheck passes."

No per-row `try` / `throw` in the hot loop.

## Wrapper behavior

`DivInt32.eval(IntVector left, IntVector right, IntVector out)`:

1. `int n = Checks.sameValueCount(left, right);`
2. `Checks.outputCapacity(out, n);`
3. `Checks.zeroSliceOffset(left, right);`
4. `try (var refs = BufferRefs.retain(left, right, out)) { ... }`.
5. Build `activeValidity` segment:
   - If both inputs have `nullCount == 0`, `activeValidity` is conceptually all-ones; the wrapper uses the no-null code path.
   - Otherwise compute `activeValidity = left_validity & right_validity` (word-wise via `Bitmap.and` or `Validity.propagateBinary`).
6. **Precheck** `right` over active rows (and the `MIN_VALUE / -1` trap pattern). Throw on first offender.
7. Write `out_validity = activeValidity`.
8. Call the raw kernel:
   - no-null path: `DivInt32Raw.noNulls(leftData, rightData, outData, n);`
   - valid-only path: `DivInt32Raw.validOnly(leftData, rightData, outData, activeValidityData, n);`
9. `out.setValueCount(n);`.

## Output validity

```text
out_validity = left_validity & right_validity
```

Output data on null lanes is don't-care.

## Tests

Raw tests without Arrow (`Arena.ofConfined()` fixtures):

- normal division (positive / positive, negative / positive, etc.);
- zero divisor present in no-null path → expected to be caught by **wrapper** precheck; raw kernel test exercises this by passing pre-validated buffers (i.e., raw kernel tests do not test the precheck — that's a wrapper test);
- scalar tails;
- negatives, boundary values (`Integer.MIN_VALUE`, `Integer.MAX_VALUE`);
- `MIN_VALUE / -1` trap pattern (same comment — wrapper-tested);
- separate input/output segments.

Wrapper tests with Arrow (`-Darrow.memory.debug.allocator=true`):

- **Inactive null divisor with zero data does not throw** (precheck skips null rows);
- **Active divisor zero throws `ArithmeticException` with the first-offender row index** before the loop;
- **`MIN_VALUE / -1` on an active row throws `ArithmeticException` with row index** before the loop;
- **Output is not partially written when precheck throws** (use a writable-but-not-readable canary in `out`, or assert `getValueCount()` was not bumped);
- All-valid normal division;
- Sparse-null divisor where all active rows are non-zero → succeeds, output validity correct;
- Dense-null divisor where some null rows contain zero data → succeeds (null rows skipped);
- Output value count set after success.

## Benchmarks

- No-null checked path: raw vs naive Java;
- Nullable valid-only path: wrapper end-to-end;
- (Optional) naive Java baseline for valid-only.

Required dims per `BENCHMARKS.md §Wrapper benchmarks`. Null profiles: 0%, 1%, 10%, 30% for the valid-only path.

## Non-goals

- All-division-type suite (DivInt64, DivFloat64 — float division is null-safe, handled separately).
- Vector API optimization for the valid-only path beyond what's trivial.
- Modulo (Rem) is a sibling iteration, not this one.

## Acceptance criteria

- `Compute.divide(left, right, out)` works for int32 vectors.
- Wrapper tests prove the precheck-before-loop contract holds (no partial writes on failure, no per-row throws in hot loop, inactive nulls never trigger throws).
- Nullable semantics differ correctly from safe kernels.
- Tests prove no division happens for inactive null rows (zero divisor in a null row produces null output, not an exception).
- The precheck contract is concrete enough that the next checked kernel (e.g., a future `AddInt32Checked`) reuses it without re-deriving.

## Cross-references

- `CORE_DESIGN.md §Safe vs valid-only kernels §Precheck-before-loop for checked kernels`.
- `AGENTS.md §Error handling §Precheck-before-loop rule`, §Null handling modes.
