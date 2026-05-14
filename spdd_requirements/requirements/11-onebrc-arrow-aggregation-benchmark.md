# Requirement: 1BRC-style Arrow Aggregation Benchmark

## Business requirement

Benchmark batch-level aggregation over preloaded Arrow data using a 1BRC-like data shape, without measuring file I/O or CSV parsing.

## Scope

Setup loads or generates Arrow batches. Measurement only performs aggregation.

## Setup phase, not measured

```text
CSV -> Arrow batches
temperature -> int32 scaled by 10
station   -> utf8 / dictionary / int id
```

Setup is allowed to allocate, build dictionaries, and lay out batches. None of this counts toward the measured timing.

## Measured phase

```text
Arrow batches -> aggregation state -> final in-memory result
```

Do not measure file read, CSV parsing, output formatting, disk write, or final sorting unless explicitly configured.

## Modes

Start with:

```text
global_temperature_min_max_sum_count
group_by_int_station_id_min_max_sum_count
```

Later add dictionary and utf8 station-key modes when the project chooses to build a hash table — that hash table is **explicitly out of MVP scope** (foundation `CORE_DESIGN.md §Aggregation state model`, `§Non-goals`). The `group_by_int_station_id` mode uses a pre-assigned int station id so the measured path is **purely state-update**, no hash lookup.

## Aggregation state shape

State follows SoA per `CORE_DESIGN.md §Aggregation state model`:

```java
long[]  counts;
int[]   mins;   // temperature is int32 scaled by 10
int[]   maxs;
long[]  sums;
```

Group id is the array index. State arrays are sized to the known group cardinality at setup; no runtime hash table.

## Baselines

Compare against:

- naive Java loop over preloaded Arrow batches (using Arrow Java accessors directly);
- PyArrow over preloaded Arrow table/batches.

PyArrow is a C++-backed practical baseline, not pure C++ peak performance (per foundation `BENCHMARKS.md §PyArrow baselines`).

## Correctness

Verify results against a simple reference implementation (naive Java loop over the same preloaded batches). Compare results as a **station → stats map** with no order requirement (per foundation `BENCHMARKS.md §Slow-tier benchmarks` — same correctness comparison rule applies here).

For temperature stats: `count`, `min`, `max`, `sum` checked exactly (int32 arithmetic, no FP).

## Non-goals

- Do not compete with optimized original 1BRC parsers.
- Do not include CSV parsing.
- Do not build a full hash aggregation engine — the int-station-id mode is the only mode that lives without one.
- Dictionary and utf8 group-key modes are stretch goals; ship them only when the hash table is in scope (post-MVP).

## Acceptance criteria

- Benchmark reports only aggregation time; setup is excluded.
- Data loading is outside measurement.
- At least one JVM implementation and one PyArrow baseline exist.
- Correctness check uses station → stats map equality (no order).
- SoA state shape is documented in the benchmark code and matches foundation.

## Cross-references

- `BENCHMARKS.md §Aggregation and batch-operation benchmarks`, §PyArrow baselines.
- `CORE_DESIGN.md §Aggregation state model`.
