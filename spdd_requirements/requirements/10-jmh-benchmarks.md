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
| Is JVM-native within reach of an out-of-process vectorized-interpreter reference? | arrow-rs Criterion subproject (see `11-bench-cleanup-and-cargo-reference.md`) |
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

- Macrobenchmarks (the 1BRC benchmark is `13-onebrc-arrow-aggregation-benchmark.md`).
- Out-of-process native reference (`11-bench-cleanup-and-cargo-reference.md`); `12-native-baseline.md` superseded.
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
