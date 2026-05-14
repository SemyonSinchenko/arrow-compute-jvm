# Requirement: Native Baseline (JNI or FFM downcall)

## Business requirement

Measure whether JVM-native kernels beat crossing into native C++ per kernel. This is the headline scenario question — answering it well is core to the project's value proposition.

## Scope

Create a minimal native baseline for one or two primitive operations. The native call may be implemented as classic **JNI** or as an **FFM downcall** (Java 22+); the interpretation rule is the same for both — the overhead is part of the design cost and is **not** subtracted.

Start with `AddInt32` and `AddFloat64`.

## Benchmark comparisons

```text
java_raw_vector
java_wrapper
java_compute_dispatch
native_cpp_per_kernel
```

If JNI and FFM are both wired (unlikely in this iteration), report them as `native_jni_per_kernel` and `native_ffm_per_kernel`.

## Interpretation

This benchmark measures JVM-native execution vs native C++ through the JVM/native boundary. It does **not** measure Java raw loop vs pure C++ raw loop.

Native-boundary overhead (JNI marshalling or FFM downcall stub cost) **must not be subtracted**. It is part of the design cost — the whole project pitch is that the JVM-native path avoids that boundary.

Correct claim: "JVM-native execution outperforms per-kernel native calls in this steady-state scenario."

Forbidden claim: "Java is faster than C++."

## Dimensions

```text
rows: 1K, 16K, 64K, 1M
nulls: 0% initially
```

Nullable native baseline may be deferred. The headline answer (does JVM-native beat per-kernel native?) does not require null profiles.

## Native implementation notes

For implementation choice:

- **JNI**: classic, more boilerplate, well-understood overhead.
- **FFM downcall** (`Linker`, `MethodHandle`, `Arena`): less boilerplate, lower per-call overhead, requires `--enable-native-access=ALL-UNNAMED` (already in foundation flag set).

Pick one for MVP; the spdd does not mandate which. The native C++ side can be hand-rolled (`add_int32_array(int32_t* a, int32_t* b, int32_t* out, int n)`) — full Arrow C++ integration is out of scope.

## Non-goals

- Full Arrow C++ integration layer (skip the rest of Arrow C++ Compute — we only need a single `add` kernel implemented natively).
- Claims that Java is faster than C++ in general.
- Blocking MVP on JNI difficulty — if both JNI and FFM are too costly to wire in this iteration, defer with a written reason and a follow-up plan.
- Nullable native variant.

## Acceptance criteria

- At least one native-per-kernel benchmark exists OR is explicitly deferred with a documented reason and a follow-up plan.
- Reports state that native-boundary overhead is included.
- JVM benchmarks remain runnable without the native side if the native build is optional.
- Headline comparison (JVM-native vs native-per-kernel) is reportable at 1K / 16K / 64K / 1M rows.

## Cross-references

- `BENCHMARKS.md §Native-baseline benchmarks`.
- `CORE_DESIGN.md §Risks & assumptions` (FFM dependency).
