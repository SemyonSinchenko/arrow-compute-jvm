# SPDD Analysis: Benchmark Suite Cleanup and Cargo Reference

## Original Business Requirement
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

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Benchmark governance policy: `BenchmarkSuiteValidator` and `BenchmarkProfiles` enforce row-set, null-profile, naming-layer, and seed invariants across suite runs.
- Layered benchmark taxonomy: `*RawBenchmark`, `*DispatchBenchmark`, and metadata (`layer`, `question`, `baseline`, `benchmarkId`) are already first-class concepts used uniformly across kernel families.
- In-process native baseline concept: `NativeAddInt32Benchmark`, `NativeAddFloat64Benchmark`, `NativeAddBridge`, and `FfmNativeAddBridge` currently model an optional FFM-backed native path within JMH.
- Baseline framing policy: `BENCHMARKS.md` already contains a strategic framing that positions arrow-rs as an out-of-process same-host reference and includes DRAM-ceiling interpretation guidance.
- Iteration linkage concept: requirements `10`, `11`, and `12` plus existing SPDD artifacts encode benchmark matrix evolution and supersession history.

#### New Concepts Required
- Out-of-process native reference project: `arrow-rs-baseline/` as a separate benchmark producer owned outside JMH execution flow, with same dimensions and seed policy.
- Dispatch regression tripwire: one explicit `dispatchSmoke` cell per dispatch class representing a guardrail concept rather than a full measurement matrix concept.
- Retired-but-preserved scalar fallback: keep naive scalar logic as recoverability/design fallback in raw kernel code, while removing it as a routine benchmark dimension.
- Host-anchored physical ceiling record: a bench-host DRAM-bandwidth reference concept that contextualizes 1M-row throughput claims.
- Documentation truthfulness contract: benchmark narratives must avoid language-war framing and stay aligned to "fusion-vs-materialization" positioning.

#### Key Business Rules
- Truthful baseline rule: JMH must not report synthetic native numbers from unavailable bridge paths; invalid in-process native path is removed, not benchmarked.
- Scope-decoupling rule: Cargo reference runs separately from `./gradlew jmh`; manual merge into `BENCHMARKS.md` is accepted governance behavior.
- Dispatch-minimization rule: remove `apiCompute*` matrix cells across dispatch families, but keep exactly one smoke/tripwire per class to catch future de-monomorphization.
- Raw-benchmark minimization rule: remove `naiveMemorySegment` benchmark methods from routine JMH runs across raw families while preserving scalar recovery capability in kernel sources.
- Null-profile harmonization rule: raw cells embedded in dispatch benchmarks run at `nullPercent=0` only; validator must explicitly permit that reduced profile.
- Host-comparability rule: publishable JVM-vs-arrow-rs comparison claims are same-host only and interpreted against measured DRAM limits at large row counts.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Reframe benchmark architecture from "all baselines in one JMH process" to "JMH for JVM layer questions + Cargo Criterion for native reference question," while keeping shared dimensions and reproducibility contracts.
- Apply a suite-wide benchmark-cell reduction strategy across all kernel families by removing proven redundant cells and preserving only regression-safety tripwires.
- Use existing benchmark metadata conventions (layer/question/baseline semantics) and requirement cross-references as the continuity mechanism, avoiding new benchmark infrastructure.
- Keep documentation and policy artifacts (`BENCHMARKS.md`, requirement docs, validator rules) as co-equal deliverables with source changes, since this requirement is benchmark-truthfulness governance as much as code cleanup.

#### Key Design Decisions
- Native comparison topology: in-process JNI/FFM vs out-of-process arrow-rs reference; in-process gives boundary-cost data but is currently unwired/noisy, out-of-process gives honest kernel-level comparability -> recommend out-of-process arrow-rs reference as strategic baseline source.
- Dispatch benchmark intent: full `apiCompute*` grid vs single smoke tripwire; full grid is costly and duplicates wrapper results under monomorphic dispatch, smoke retains future regression signal -> recommend single `dispatchSmoke` cell per dispatch class.
- Raw baseline retention in JMH: keep `naiveMemorySegment` routine cell vs retire it; keeping it preserves historical visibility but burns runtime after question closure, retiring it speeds suite and sharpens focus -> recommend retire from routine JMH while preserving scalar fallback code in raw layer.
- Validation strictness model: one global null-profile matrix vs per-scenario relaxation; global strictness is simple but blocks intentional raw-in-dispatch null=0 design, targeted relaxation keeps policy with explicit exception -> recommend scenario-aware validator relaxation for raw dispatch cells only.
- Benchmark results governance: automated cross-toolchain merge vs manual reporting; automation reduces human error but adds tooling scope and maintenance, manual merge is acceptable per non-goals -> recommend manual merge now, defer automation.

#### Alternatives Considered
- Keep in-process native classes but mark results as "informational": rejected because unwired fallback path still risks misleading publication and violates requirement intent.
- Remove all dispatch API cells with no replacement: rejected because requirement mandates one per-class tripwire to detect future dispatch polymorphism regressions.
- Keep naive raw cells only for selected kernel families: rejected because requirement demands collapse across all raw benchmark families for consistency and runtime savings.
- Trigger Cargo benchmarks from Gradle/CI immediately: rejected due to explicit non-goal and higher operational complexity in this phase.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- "All kernel families" boundary: list of mandatory benchmark classes to update is implied but not explicitly enumerated, risking uneven cleanup.
- Dispatch tripwire semantics: requirement names `dispatchSmoke` intent and largest-row/null=0 boundary, but does not define exact method naming/metadata format beyond intent.
- DRAM ceiling source choice: permits `mbw` or STREAM Triad without mandating one canonical tool/output format, which can affect comparability across future host updates.
- Cargo result merge policy: "merged by hand" is allowed, but no acceptance format is specified for how much Criterion detail must be carried into `BENCHMARKS.md`.

#### Edge Cases
- Aggregation dispatch class currently supports `nullPercent=100`; collapsing raw cells to `nullPercent=0` while wrapper cells keep broader null profiles can create mixed-parameter classes that need validator clarity.
- String dispatch/raw benchmarks include extra dimension (`needleLength`); dispatch collapse and raw-cell retirement must not unintentionally remove this domain-specific signal.
- Empty/native-unavailable paths currently return structured error responses in native benchmarks; deleting classes removes this behavior and any tooling depending on those outputs.
- Bench-host change events may occur without process discipline, making DRAM-ceiling values stale relative to new JMH/Cargo runs.

#### Technical Risks
- Coverage regression risk: broad class/method deletion across JMH files can accidentally remove required metadata fields or break result labeling conventions -> mitigation direction: retain metadata contract and run suite-level validation after cleanup.
- Policy drift risk: docs and validator may diverge (e.g., docs retire Native while validator still accepts it) -> mitigation direction: treat `BENCHMARKS.md`, requirements docs, and validator updates as one atomic governance change set.
- Comparability risk: Cargo benchmarks may drift from JMH dimensions (rows, seed, preallocation semantics) over time -> mitigation direction: codify shared profile constants in Cargo README and keep explicit cross-check checklist in docs.
- Interpretability risk: without recorded DRAM numeric value, 1M-row plateau can be misread as kernel weakness rather than memory ceiling -> mitigation direction: require numeric host reading and formula narrative in benchmark report.
- Toolchain adoption risk: stable Rust requirement may still fail on contributor hosts lacking Rust toolchain -> mitigation direction: clearly document optional/local execution expectations and host prerequisites.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `spdd_requirements/requirements/12-native-baseline.md` carries a `Superseded by 11-…` notice at the top. | Yes | Already satisfied in current codebase; must remain intact during updates. |
| 2 | `10-jmh-benchmarks.md` `Non-goals` reference and `Baseline matrix` row are updated to point at out-of-process reference; stale onebrc pointer corrected. | Partial | Baseline row and native non-goal already point to 11; stale onebrc pointer is still inconsistent in prior artifacts and must be normalized. |
| 3 | `BENCHMARKS.md` no longer describes `native_cpp_per_kernel` as JMH-emitted in goals/native section. | Partial | Current `BENCHMARKS.md` largely reframed, but references must be audited to ensure no residual JMH-native wording remains in all sections. |
| 4 | `BENCHMARKS.md §DRAM bandwidth ceiling` exists with numeric reading from canonical host. | Partial | Section exists, but currently template-oriented; numeric host value still missing. |
| 5 | JMH suite has no `Native*` classes, no `apiCompute*` outside single tripwire, no `naiveMemorySegment` in any `*RawBenchmark`. | No | Current suite still contains `Native*` classes, multiple `apiCompute*` methods across dispatch classes, and `naiveMemorySegment` methods across raw classes. |
| 6 | `arrow-rs-baseline/` exists with Cargo files and runnable Criterion benches. | No | No `arrow-rs-baseline/` subproject detected in repository root yet. |
| 7 | `BenchmarkSuiteValidator` no longer accepts `"Native"` as layer keyword. | No | Validator still accepts class names containing `Native`. |
