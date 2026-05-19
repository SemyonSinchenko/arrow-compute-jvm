# SPDD Analysis: JMH Benchmark Suite Baseline Matrix

## Original Business Requirement
# Requirement: JMH Benchmark Suite

## Business requirement

Create a benchmark suite that honestly measures raw kernels, wrappers, dispatch, and baselines using the foundation baseline matrix.

## Scope

Implement benchmarks for:

```text
raw kernel vs naive Java
wrapper vs raw
dispatch vs wrapper
```

Cover the kernels delivered through iteration 08 (fixed-width arithmetic + simple aggregations) plus the fast-tier string scaffold from `09-fast-tier-string-scaffold.md`.

## Baseline matrix

Every benchmark declares which row of `BENCHMARKS.md §Benchmark goals` baseline matrix it answers:

| Question | Right baseline |
|---|---|
| Is Vector API doing its job? | naive `MemorySegment` loop |
| Is wrapper overhead acceptable? | raw kernel |
| Is dispatch overhead acceptable? | wrapper |
| Is JVM-native beating per-kernel native? | native baseline per kernel |
| Is the project useful vs ecosystem? | PyArrow compute chain |
| What is the gap for slow-tier ops? | PyArrow + (later) native / 3rd-party |

A benchmark that does not name its question and its baseline is rejected.

## Rules

- Use preallocated outputs.
- Do not include setup allocation in measured methods.
- Use sufficient warmup (`@Warmup(iterations = 10, time = 1)`; `@Measurement(iterations = 10, time = 1)`; `@Fork(3)` as starting points).
- Consume outputs via `Blackhole.consume(out)` or a small reduction over the output (e.g., `Blackhole.consume(out.get(LAST_INDEX))`). **Never discard**.
- Report row count, type, null profile, output allocation policy.
- Single-threaded by default.
- Random data generators use a fixed seed: `0xC0FFEEL`. Reproducible runs are mandatory.

## Required dimensions

```text
rows: 1K, 16K, 64K, 1M
nulls: 0%, 1%, 10%, 30% for wrapper benchmarks
```

Raw `computeAll` kernels (no validity input) do not need null profiles.

For aggregation wrappers, add a 100% null profile to exercise the all-null fast-skip.

## Benchmark naming

Per `BENCHMARKS.md §Benchmark naming` — class names encode layer and scenario:

```text
AddInt32RawVectorBenchmark
AddInt32RawNaiveBenchmark
AddInt32WrapperBenchmark
AddInt32DispatchBenchmark
SumInt64WrapperBenchmark
StartsWithUtf8RawBenchmark
StartsWithUtf8WrapperBenchmark
```

Avoid vague names (`FastBenchmark`, `PerformanceTest`).

## Baselines

For every serious raw kernel include:

- Vector API raw kernel;
- naive `MemorySegment` Java loop.

Optional: naive Java `int[]` / `long[]` array loop (sanity check, not Arrow-realistic).

## Output

A single `./gradlew jmh` invocation runs the suite. Results may be JSON or CSV (settled at implementation); the schema must include layer, type, row count, null profile, and the question being answered.

## Non-goals

- Macrobenchmarks (the 1BRC benchmark is `11-onebrc-arrow-aggregation-benchmark.md`).
- Native baseline (`12-native-baseline.md`).
- Slow-tier benchmarks (handled by their own iterations — `13-slow-tier-scaffold.md`, `14-slow-tier-decimal128-add.md`).
- Fusion benchmark (`15-fused-expression-spike.md`).

## Acceptance criteria

- JMH suite runs end-to-end.
- Results are labeled by layer in the benchmark class name and in the result JSON/CSV.
- Dead-code elimination is addressed for every benchmark.
- Benchmarks use fixed seed and produce reproducible numbers across runs.
- Every benchmark documents the baseline matrix row it answers.

## Cross-references

- `BENCHMARKS.md §Benchmark goals` (baseline matrix), §Raw kernel benchmarks, §Wrapper benchmarks, §Benchmark naming, §Benchmark anti-patterns.
- `AGENTS.md §Benchmarking`.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Benchmark Layer Vertical: raw-vs-naive and path benchmarks already exist across arithmetic, division, aggregation, and starts-with (`src/jmh/java/.../*RawBenchmark.java`, `src/jmh/java/.../*PathBenchmark.java`) and already exercise `raw -> wrapper -> Compute` flows.
- Kernel Layer Taxonomy: raw kernels (`compute/raw`), wrapper kernels (`compute/wrapper/*`), explicit dispatch classes (`compute/dispatch/*`), and public facade (`compute/Compute`) are stable and directly benchmarkable as separate business layers.
- Reproducibility Discipline: most benchmark datasets use fixed seeds (commonly `0xC0FFEEL`) and preallocated vectors/segments, matching benchmark-governance goals.
- Benchmark Governance Artifacts: `BENCHMARKS.md` already defines baseline-question mapping, naming rules, and anti-patterns; this is the current policy source for benchmark interpretability.
- Gradle JMH Integration: `build.gradle.kts` already wires `./gradlew jmh` and JMH plugin execution, so suite assembly is an extension of existing infrastructure rather than greenfield.

#### New Concepts Required
- Baseline Matrix Traceability Contract: each benchmark must explicitly declare which baseline-question row it answers as a first-class reporting dimension, not just via informal comments.
- Layer-Explicit Benchmark Naming Model: benchmark names must encode layer/scenario consistently (raw vector vs raw naive vs wrapper vs dispatch), replacing generic `*PathBenchmark` naming where needed.
- Unified Result Metadata Model: suite output must carry layer, type, row profile, null profile, output-allocation policy, and question label in machine-readable form (JSON/CSV).
- Iteration-Coverage Benchmark Portfolio: benchmark scope must intentionally cover iteration 08 kernels plus the iteration 09 fast-tier string scaffold as a managed portfolio, not as isolated microbenchmarks.

#### Key Business Rules
- Benchmark Interpretability Rule: any benchmark lacking explicit question+baseline mapping is invalid for decision-making.
- Layer Isolation Rule: wrapper overhead is evaluated against raw; dispatch overhead is evaluated against wrapper; raw vectorization is evaluated against naive `MemorySegment` loop.
- Measurement Hygiene Rule: measured methods must exclude setup allocation and must consume outputs to prevent dead-code elimination.
- Reproducibility Rule: fixed-seed generation and single-threaded default are mandatory comparability constraints.
- Coverage Rule: wrapper benchmarks require null-profile matrix, and aggregation wrappers must include an all-null profile to validate fast-skip behavior in performance context.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Evolve the current benchmark set into a policy-driven suite organized by benchmark layer and baseline question, keeping the existing architecture split (`raw`, `wrapper`, `dispatch/facade`) as the primary measurement axis.
- Standardize benchmark identity and result metadata so each run can be interpreted without reading source code comments.
- Align benchmark dimensions with requirement matrix (rows, nulls, kernel families) while preserving current no-allocation and preallocated-output conventions already used in codebase.

#### Key Design Decisions
- Benchmark organization by business question vs by operation family: question-first improves comparability and governance but requires stricter naming/metadata discipline -> recommend question-first because requirement acceptance is baseline-matrix centric.
- Keep current per-operation benchmark classes vs central mega-benchmark class: per-operation classes increase file count but preserve locality and kernel ownership clarity -> recommend per-operation classes to match existing code conventions.
- Encode layer in class names vs only in benchmark method names/comments: class-level encoding is more rigid but makes reporting and filtering stable -> recommend class-level encoding to satisfy AC and `BENCHMARKS.md` naming policy.
- Use global JMH defaults in Gradle vs per-class warmup/fork annotations: global settings reduce drift but may underfit special cases -> recommend setting requirement-aligned defaults globally, with explicit per-class override only when justified.
- Output format choice JSON vs CSV: JSON is richer for structured metadata; CSV is simpler for spreadsheet workflows -> recommend JSON as primary with optional CSV export, because question/baseline labels are easier to preserve structurally.

#### Alternatives Considered
- Keep existing benchmark names and rely on documentation mapping: rejected because requirement explicitly requires layer labeling in class names and output artifacts.
- Add native/PyArrow baselines now for completeness: rejected for this iteration because requirement marks native/slow-tier/macro/fusion baseline work as non-goals.
- Build a bespoke benchmark runner outside JMH plugin: rejected because current Gradle JMH integration already satisfies execution needs and keeps operational overhead low.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Required row matrix conflict: requirement says `1K/16K/64K/1M`, but several existing benchmarks use `4096/16384/65536/262144`; migration boundary is not explicitly prioritized.
- Warmup policy conflict: requirement references `10/10/3` as starting points while current Gradle JMH defaults are `3/4/2`; governance source of truth for this iteration needs explicit decision.
- Baseline declaration location is underspecified: unclear whether declaration must be in class name, class-level Javadoc, JMH params, or result schema field (likely all for auditability).
- Scope phrase "cover kernels delivered through iteration 08" is broad; exact mandatory kernel list is not explicitly enumerated in this requirement.

#### Edge Cases
- Aggregation all-null profile (100%) must be included without accidentally benchmarking exception paths or output-allocation branches.
- String benchmarks with empty/short rows vs needle lengths can skew comparability if dataset shape differs across layers.
- Very small and very large batches can expose different overhead regimes; missing 1M in wrapper/path benchmarks creates interpretation gaps.
- Anti-vectorized naive baselines (as in one existing benchmark) can bias "Vector API doing its job" conclusions if not clearly labeled as intentional lower-bound baselines.

#### Technical Risks
- Naming migration risk: renaming existing `*PathBenchmark` classes to layer-specific names can break existing include filters and reporting scripts -> mitigate by planned alias period or synchronized script updates.
- Metadata drift risk: without a shared reporting contract, class comments and output files can disagree on baseline question labels -> mitigate by deriving report labels from a single benchmark metadata source.
- Comparability risk from mixed seeds and data generators: one benchmark currently uses a different seed style; inconsistent generators weaken cross-operation conclusions -> mitigate by enforcing one fixed seed policy and documenting allowed exceptions.
- Configuration risk: JMH global defaults in Gradle may silently diverge from requirement recommendations over time -> mitigate by explicit benchmark-suite policy checks in CI or review checklist.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | JMH suite runs end-to-end. | Yes | Existing `me.champeau.jmh` plugin and `./gradlew jmh` integration already present. |
| 2 | Results are labeled by layer in the benchmark class name and in the result JSON/CSV. | Partial | Current code uses `*PathBenchmark` naming and no explicit JSON/CSV schema fields for layer/question yet. |
| 3 | Dead-code elimination is addressed for every benchmark. | Partial | Most benchmarks consume outputs, but consistency and explicit DCE rationale are uneven across classes. |
| 4 | Benchmarks use fixed seed and produce reproducible numbers across runs. | Partial | Many classes use fixed seeds, but at least one benchmark uses a different constant pattern; suite-wide policy enforcement is missing. |
| 5 | Every benchmark documents the baseline matrix row it answers. | Partial | Some classes include question/baseline comments, but this is not uniformly encoded or report-visible across all benchmarks. |
