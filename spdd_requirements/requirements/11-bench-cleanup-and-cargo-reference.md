# Requirement: Benchmark Suite Cleanup and Cargo+Criterion Native Reference

## Business requirement

Three concurrent failures in the current JMH benchmark suite undermine the project's claims and waste run time.

- The in-process FFM/JNI native baseline defined by `12-native-baseline.md` has no native library behind it. `NativeAddInt32Benchmark` and `NativeAddFloat64Benchmark` report rows-independent throughput (~1400 ops/ms across `rows ∈ {1024, 16384, 65536, 1048576}`) because the unavailability path silently allocates a `NativeBridgeUnavailableException` plus a `BenchmarkErrorResponse` and returns. Published results would read as "JVM beats native ~400×", the inverse of the claim the project actually wants to support.
- The dispatch layer (`Compute.add(...)` → `AddInt32.eval(...)`) has been measured as statistically zero-cost across every `(rows × nullPercent)` cell. C2 monomorphizes the single-type dispatch site; the `apiCompute*` cell duplicates `wrapperEval` and consumes roughly half of the dispatch-suite run time per JMH invocation.
- The Vector-API-vs-scalar question is closed. The anti-SuperWord `naiveMemorySegment` baseline shows a ~2.7× Vector-API advantage at small batches; gap converges at 1M rows under DRAM bandwidth bound. The naive cells exist as historical record only.

Replace the in-process native baseline with an out-of-process **arrow-rs + Cargo + Criterion** reference run on the same bench host with matched dimensions. Collapse the proven-redundant JMH cells across **all** kernel families. Record the physical DRAM-bandwidth ceiling once so 1M-row results are interpretable against the physical bound rather than against an imaginary in-process native number.

This requirement supersedes `12-native-baseline.md` for the kernel-level native comparison and amends the baseline matrix in `10-jmh-benchmarks.md`.

## Scope

1. **Out-of-process native reference.** A Cargo subproject at repo root: `arrow-rs-baseline/`. Uses `arrow` and `criterion` crates. Benchmarks: int32 add, float64 add. Row sizes `{1024, 16384, 65536, 1048576}`. Seed matches `BenchmarkProfiles.REQUIRED_SEED` (`0xC0FFEE`). Preallocated outputs, `std::hint::black_box` consumption, Criterion `iter_batched` / `BatchSize::LargeInput` so allocation stays outside the timed region (mirroring JMH `@Setup(Level.Trial)`). Single-threaded. Excluded from Gradle; never invoked by `./gradlew jmh`. Results merged into `BENCHMARKS.md` by hand from `cargo bench` output.

2. **In-process native flow retired.** All `Native*` benchmark classes and the FFM bridge are deleted from the JMH source tree. The `"Native"` layer keyword is dropped from suite validation. No JNI/FFM library is loaded by the JMH process. `arrowcompute.native.lib` system property and any references to `add_int32_array` / `add_float64_array` native symbols are removed.

3. **Dispatch-layer collapse, all kernel families.** Across every `*DispatchBenchmark`, the `apiCompute*` benchmark method is removed. A single regression-tripwire cell remains per dispatch class at the largest row size and `nullPercent=0`, named explicitly to mark intent (e.g., `dispatchSmoke`). The tripwire exists to detect future de-monomorphization when additional kernel types are added to a dispatcher.

4. **Raw-layer collapse, all kernel families.** Across every `*RawBenchmark`, the anti-SuperWord `naiveMemorySegment` method is removed from the JMH run. The naive scalar source code remains in `compute/raw/` as the Vector API recovery fallback per `CORE_DESIGN.md §Risks & assumptions § jdk.incubator.vector`; only the routine JMH benchmark cell is retired.

5. **Raw cells in dispatch benchmarks ignore nulls.** `rawComputeAll` parameters in `*DispatchBenchmark` classes run with `nullPercent=0` only. `BenchmarkSuiteValidator.validateParams` is relaxed to permit a single-value null profile for raw cells embedded in dispatch classes.

6. **DRAM-ceiling recording.** A one-shot `mbw -t 0 -n 5 1024` or STREAM Triad measurement on the canonical bench host. Numeric result and interpretation (read + read + write GB/s vs measured 1M-row throughput) recorded under a new section `BENCHMARKS.md §DRAM bandwidth ceiling`. Not automated; not part of `./gradlew jmh`. Re-recorded only when the bench host changes.

## Non-goals

- Building a Java↔native (JNI/FFM) bridge in this iteration. Pure boundary-cost questions (e.g., "how expensive is an empty FFM downcall?") belong to a future, separately-scoped requirement targeting boundary cost rather than kernel cost.
- Auto-running the Cargo subproject from Gradle or CI.
- Statistical cross-toolchain harness unification (merging JMH JSON with Criterion JSON automatically). Manual paste into `BENCHMARKS.md` is acceptable.
- Cross-host comparisons. Only same-host arrow-rs-vs-JVM numbers are publishable claims.
- Nullable native baseline (already non-goal in `12-`).
- Attacking the wrapper-layer JVM tax in `AddInt32.eval` itself. The ~248 ns/batch wrapper overhead measured at 1024 rows is a *materialization* tax; the strategically-aligned answer is fusion (see `16-fused-expression-spike.md`), not a tactical kernel rewrite. Out of scope here.

## Constraints

- Cargo subproject and JMH suite share: row sizes `{1024, 16384, 65536, 1048576}`, seed `0xC0FFEEL`, preallocated outputs, blackhole-equivalent consumption, single-threaded execution.
- Framing in `BENCHMARKS.md`: results presented as **"arrow-rs vectorized-interpreter reference"**, not "native vs JVM". Required to stay consistent with the project's positioning that the win to chase is fusion-vs-materialization rather than Hotspot-vs-LLVM. Forbidden claim: "Java is faster than Rust/C++".
- DRAM-ceiling reading captured once per host; re-recorded only on bench-host change.
- The Cargo subproject must build with a stable Rust toolchain (no nightly-only features), so the reference is reproducible on any contributor's machine.
- The single `dispatchSmoke` tripwire cell per dispatch class is mandatory; bare deletion of `apiCompute*` without a replacement tripwire is a non-acceptable form of step 3.

## Acceptance criteria

- `spdd_requirements/requirements/12-native-baseline.md` carries a `Superseded by 11-…` notice at the top.
- `10-jmh-benchmarks.md` `Non-goals` reference and `Baseline matrix` row are updated to point at the out-of-process reference rather than the deleted in-process native row. The stale `11-onebrc-...` reference in that file is corrected to `13-onebrc-...`.
- `BENCHMARKS.md` no longer describes `native_cpp_per_kernel` as a JMH-emitted row in `§Native-baseline benchmarks` or in `§Benchmark goals`. The section either points at the Cargo subproject or is retitled to reflect the out-of-process source.
- `BENCHMARKS.md §DRAM bandwidth ceiling` exists with a numeric reading from the canonical bench host.
- JMH suite contains no `Native*` benchmark classes. No `apiCompute*` cells outside the single `dispatchSmoke` tripwire per dispatch class. No scalar `naiveMemorySegment` cells in any `*RawBenchmark`.
- `arrow-rs-baseline/` directory exists at repo root with `Cargo.toml`, `benches/add_int32.rs`, `benches/add_float64.rs`, `README.md`. `cargo bench` produces a Criterion report on the bench host.
- `BenchmarkSuiteValidator` no longer accepts `"Native"` as a layer keyword.

## Cross-references

- Supersedes: `12-native-baseline.md`.
- Amends: `10-jmh-benchmarks.md` (`Baseline matrix` row, `Non-goals` reference, stale 1BRC pointer).
- Amends: `BENCHMARKS.md §Benchmark goals`, `§Native-baseline benchmarks` (retitled), and adds `§DRAM bandwidth ceiling`.
- Amends: `CORE_DESIGN.md §Risks & assumptions §Performance assumption` (the headline-risk trigger no longer references in-process per-kernel JNI).
- Project framing: see project memos on JVM-tax probe scope and on fusion-vs-interpreter positioning.
