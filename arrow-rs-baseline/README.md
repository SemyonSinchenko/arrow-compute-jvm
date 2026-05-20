# arrow-rs-baseline

Out-of-process native reference benchmarks for `arrow-compute-jvm`.

## Scope

- Benchmarks: int32 add, float64 add
- Rows: `1024`, `16384`, `65536`, `1048576`
- Seed: `0xC0FFEE`
- Execution: single-threaded benchmark process
- Input lifecycle: arrays are created once per row-size case and reused across iterations
- Output lifecycle: output is allocated by Arrow compute API on each call

This project is intentionally not wired into Gradle and is not run by
`./gradlew jmh`. Results are copied manually into `BENCHMARKS.md`.

## Run

```bash
cargo bench
```

Fresh run with no history and machine-readable outputs:

```bash
make native_bench
make native_bench BENCH=add_int32
```

Outputs are written to `results/native_bench/latest/`:

- `console.log`
- `summary.json`
- `summary.csv`

Cleanup:

```bash
make native_bench_clean
```

## Reporting contract

- Use same-host comparisons only (JMH and Cargo results from the same machine).
- Frame results as an "arrow-rs vectorized-interpreter reference".
- Do not claim "Java is faster than Rust/C++".

## JMH scale alignment

Criterion benches set `Throughput::Elements(rows)` so output includes element
throughput (`elem/s`). Convert to JMH-style `ops/ms` with:

`ops/ms = elem/s / rows / 1000`

This means the Rust reference currently includes output materialization cost,
while JVM `kernel_steady_state` lanes may reuse output buffers.
