# BENCHMARKS.md

This document describes the benchmark philosophy for the JVM-native Arrow compute project.

For when each benchmark suite is expected to land by iteration, see
`DEVELOPMENT_PLAN.md`.

The project targets long-running JVM data engines. The primary benchmark mode is:

```text
single-threaded
steady-state
warmed-up JVM
preloaded Arrow data
preallocated outputs
```

Cold-start behavior may be measured separately, but it is not the main optimization target.

## Benchmark goals

Benchmarks should answer specific questions. Each benchmark should be
designed against one of the following questions, and the report should
say which one. The right baseline depends on the question:

| Question | Right baseline |
|---|---|
| Is Vector API doing its job? | naive `MemorySegment` loop |
| Is wrapper overhead acceptable? | raw kernel |
| Is dispatch overhead acceptable? | wrapper |
| Is JVM-native beating per-kernel native (JNI/FFM)? | native baseline per kernel |
| Is the project useful vs ecosystem? | PyArrow compute chain |
| What is the gap for slow-tier ops? | PyArrow + (later) native / 3rd-party |

A benchmark that does not name its question and its baseline is a
benchmark that produces numbers nobody can interpret.

### Raw kernel benchmarks

Question:

```text
How fast is this Vector API loop compared with a naive Java loop?
```

### Wrapper benchmarks

Question:

```text
How much overhead does Arrow memory management, validity handling, and wrapper code add?
```

### Native-baseline benchmarks (JNI or FFM downcall)

Question:

```text
Is JVM-native execution faster than crossing into native Arrow C++ per kernel?
```

"Native baseline" covers both classic JNI and FFM downcalls. The
project may use either depending on what is least painful to wire; the
interpretation rule (overhead is included, not subtracted) is the same
for both.

### Macrobenchmarks

Question:

```text
How useful is this project for realistic batch-level compute workloads?
```

### Fusion benchmarks

Question:

```text
Can JVM-native fused expressions beat generic PyArrow / Arrow compute call chains?
```

## Threading model

All kernel benchmarks are single-threaded.

Kernels are single-threaded by design. They operate on one batch or slice at a time and never create threads.

Parallelism belongs to the caller or execution engine.

Single-threaded kernel benchmarks are therefore not an artificial limitation. They measure the intended unit of execution.

A separate scaling benchmark may test many independent batches across many worker threads, but that is not a raw kernel benchmark.

## Warmup policy

The primary target is steady-state throughput on long-running JVMs.

JMH benchmarks should use enough warmup for HotSpot/C2 to optimize:

```java
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
```

These values are starting points, not laws.

For macrobenchmarks, warmup should execute the same pipeline shape that measurement executes. Do not warm up a different expression, type, null profile, or batch size and then claim the measured path is warmed.

## Benchmark layers

Each benchmark must declare which layer it measures.

Recommended suites:

```text
micro/raw
micro/wrapper
integration/jni
macro/aggregation
macro/fusion
```

## Raw kernel benchmarks

Raw benchmarks compare:

```text
Vector API raw kernel
vs
naive Java MemorySegment loop
vs
optional naive Java array loop
```

Example:

```text
AddInt32Raw.vectorApi
AddInt32Raw.naiveMemorySegment
AddInt32Raw.naiveIntArray
```

The `naiveIntArray` benchmark is useful as a sanity check, but it is not Arrow-realistic.

The primary naive baseline should usually operate over `MemorySegment`.

Required dimensions:

```text
rows: 0, 1, 7, 8, 15, 16, 127, 1K, 16K, 64K, 1M
alignment: aligned-ish and unaligned offsets, where supported
scalar tails: row counts not divisible by species length
```

Raw benchmarks must avoid dead-code elimination. Use realistic output consumption.

## Wrapper benchmarks

Wrapper benchmarks compare:

```text
raw kernel
vs
Arrow-aware wrapper
vs
public Compute dispatch
```

Example:

```text
AddInt32Raw.computeAll
AddInt32.eval
Compute.add
```

Required dimensions:

```text
rows: 1K, 16K, 64K, 1M
nulls: 0%, 1%, 10%, 30%, all-null
output: preallocated
```

Do not allocate output vectors inside the measured method unless the benchmark is explicitly about allocation.

## Native-baseline benchmarks (JNI or FFM)

These benchmarks compare JVM-native execution with crossing into
native C++. The native call may be implemented via JNI or via FFM
downcall (Java 22+); the interpretation rules below are the same for
both.

This is a scenario benchmark, not a pure Java-vs-C++ benchmark.

Correct interpretation:

```text
JVM-native kernel
vs
C++ kernel through JVM boundary
```

Incorrect interpretation:

```text
Java is faster/slower than C++ in general
```

Benchmark variants:

```text
native_per_kernel
native_per_expression
native_fused, optional
```

For each kernel where practical:

```text
java_raw_vector
java_wrapper
java_compute_dispatch
native_cpp_per_kernel
```

For expression chains:

```text
java_fused
java_chain
native_cpp_chain
native_cpp_fused, optional
```

Native-boundary overhead (JNI marshalling or FFM downcall stub cost)
**should not be subtracted** in scenario benchmarks. It is part of the
design cost — the whole point of the project is that the JVM-native
path avoids that boundary.

## Naive Java baselines

Naive Java baselines are required.

They should be simple and honest:

```java
for (int i = 0; i < n; i++) {
    out[i] = left[i] + right[i];
}
```

or for `MemorySegment`:

```java
for (int i = 0; i < n; i++) {
    long off = (long) i * Integer.BYTES;
    int a = left.get(INT, off);
    int b = right.get(INT, off);
    out.set(INT, off, a + b);
}
```

Naive baselines should not use streams, collections, or boxed values.

## Aggregation and batch-operation benchmarks

Aggregation benchmarks are macrobenchmarks over preloaded Arrow data.

They should not measure:

- file I/O
- CSV parsing
- output formatting
- disk writes

Setup may read or create Arrow data. Measurement starts after data is already in memory.

### 1BRC-style benchmark

The 1BRC-style benchmark should use the 1BRC data shape:

```text
station: string/dictionary/int id
temperature: numeric, preferably int32 scaled by 10
```

Setup, not measured:

```text
CSV -> Arrow batches
temperature normalization
optional dictionary encoding
```

Measured:

```text
Arrow batches -> min/max/sum/count aggregation state
```

Default measured mode should not include final output sorting or writing.

Recommended modes:

```text
global_temperature_min_max_sum_count
group_by_utf8_station_min_max_sum_count
group_by_dictionary_station_min_max_sum_count
group_by_int_station_id_min_max_sum_count
```

| Mode | Measures |
|---|---|
| `global_temperature_*` | numeric aggregation kernel throughput |
| `group_by_utf8_station_*` | string-key grouping + aggregation |
| `group_by_dictionary_station_*` | dictionary/integer-key grouping path |
| `group_by_int_station_id_*` | mostly aggregation-state update path |

Baselines:

```text
naive Java over preloaded data
PyArrow over preloaded Arrow table/batches
```

PyArrow is acceptable as a C++-backed practical baseline. Do not present PyArrow as pure C++ peak performance.

## Fusion benchmarks

Fusion benchmarks are the best place to show the JVM-native design advantage.

They compare:

```text
JVM one-pass fused kernel
vs
JVM chain of kernels
vs
PyArrow compute chain
vs
JNI Arrow C++ compute chain
```

Optional:

```text
handwritten native C++ fused kernel
```

Example expressions:

```text
E1: out = a + b
E2: out = a * scale + b
E3: out = max(a * scale + b - c, 0)
E4: out = ((a + b) * c - d) > threshold
E5: out = ((a + b) * c - d) > threshold AND is_valid(e)
```

Required dimensions:

```text
rows: 1K, 16K, 64K, 1M
nulls: 0%, 1%, 10%, 30%
types: float64, float32, int32 where practical
batch profile: stable schema and stable null profile
```

Correct interpretation:

```text
JVM-native fused expressions can outperform generic PyArrow/Arrow compute call chains
in steady-state JVM data-engine scenarios.
```

Do not claim:

```text
Java is faster than hand-tuned C++.
```

## Slow-tier benchmarks

The project ships a two-tier kernel design (see `CORE_DESIGN.md
§Two-tier kernel design`). Fast-tier kernels carry the value
proposition; slow-tier kernels ship for API completeness. Slow-tier
benchmarks measure the honest gap to native — they exist to let users
choose based on their workload mix, not to make a competitive claim.

### Question

```text
For ops the JVM can't SIMD well (regex, full Unicode strings,
Decimal128/256 arithmetic), how much slower is the project than
PyArrow or a native implementation?
```

### What to measure

For each slow-tier op:

```text
project_plain_java
pyarrow_equivalent
native_via_jni_or_ffm, when available
re2j_or_other_3rd_party, when available
```

Required dimensions match the wrapper benchmarks (row counts, null
profiles), plus operation-specific dimensions:

- For regex / `LIKE`: pattern complexity (literal, single anchor,
  alternation, backreference). Compile time is **excluded** from
  measurement when the pattern is constant across the batch.
- For decimal: precision and scale.
- For Unicode-aware lower/upper: ASCII-only fraction in the input.

### Reporting

Every slow-tier benchmark report must include the literal line:

```text
This op is in the SLOW tier — see CORE_DESIGN.md §Two-tier kernel design.
Project plain-Java implementation. Native gap is expected.
```

Do not editorialize gap size in the report. Print the numbers, label
the tier, and let users decide whether the gap is acceptable for
their workload.

### When to graduate

A slow-tier op may move to the fast tier (SIMD raw kernel) when, and
only when:

- A benchmarked workload shows the op is hot enough that the gap
  matters in the end-to-end measurement.
- A SIMD design exists that fits the project's raw-kernel rules.

Both conditions must hold. "Could be SIMD'd in theory" is not enough;
"PyArrow is faster" is not enough.

## Graviton and architecture benchmarks

Run fusion and macrobenchmarks on:

```text
x86_64
AWS Graviton arm64
```

The Graviton benchmark is scenario-relevant because deployment often looks like:

```text
Maven JAR on HotSpot/C2/Vector API
vs
released PyArrow / Arrow C++ wheel on arm64
```

This is not a pure compiler contest. It is a deployment scenario benchmark.

Recommended AWS targets:

```text
c7g.*  Graviton3
c8g.*  Graviton4, if available
```

Start with single-threaded benchmarks.

Record:

```text
instance type
CPU model
OS
kernel version
JDK vendor/version
Java flags
PyArrow version
Arrow Java version
batch size
thread count
```

At minimum Java needs:

```text
--add-modules jdk.incubator.vector
```

## PyArrow baselines

PyArrow baselines represent a practical, released, C++-backed Arrow implementation.

Use PyArrow for:

```text
aggregation macrobenchmarks
fusion chain baselines
preloaded Arrow table/batch processing
```

Do not use PyArrow to make claims about raw C++ loop peak performance.

When comparing against PyArrow:

- preload data before measurement
- use the same logical types where possible
- report schema
- report batch sizes
- avoid including CSV read unless the benchmark explicitly says it includes read
- avoid final output writing unless the benchmark explicitly says it includes write

## Arrow C++ / SIMD diagnostics

On x86, Arrow C++ may use runtime SIMD dispatch.

Diagnostic runs may pin Arrow SIMD behavior using environment variables where supported:

```text
ARROW_USER_SIMD_LEVEL=NONE
ARROW_USER_SIMD_LEVEL=SSE4_2
ARROW_USER_SIMD_LEVEL=AVX
ARROW_USER_SIMD_LEVEL=AVX2
ARROW_USER_SIMD_LEVEL=AVX512
```

These runs are diagnostic, not the primary scenario benchmark.

Primary scenario benchmark should compare against default released PyArrow behavior.

## Benchmark naming

Use names that encode the layer and scenario.

Examples:

```text
AddInt32RawVectorBenchmark
AddInt32RawNaiveBenchmark
AddInt32WrapperBenchmark
AddInt32JniBenchmark
FusedProjectionBenchmark
OneBrcAggregationBenchmark
PyArrowOneBrcBaseline
GravitonFusedProjectionBenchmark
```

Avoid vague names:

```text
FastBenchmark
CppComparison
PerformanceTest
```

## Reporting rules

Every benchmark report should include:

```text
what layer is measured
input row count
batch size
null profile
data type
output allocation policy
warmup settings
JDK version
CPU model
OS
whether PyArrow includes read/parse or not
whether JNI overhead is included or not
whether expression is fused or chained
```

Use careful language.

Good:

```text
The JVM fused kernel outperforms the PyArrow compute chain for this expression on warmed-up Graviton3.
```

Bad:

```text
Java is faster than C++.
```

## Benchmark anti-patterns

Avoid:

- measuring allocation when claiming kernel throughput
- including CSV parsing when claiming aggregation throughput
- including final sorting/writing unless explicitly stated
- comparing fused JVM against non-fused C++ without saying so
- comparing warmed JVM against cold Python and calling it universal
- using tiny batches only
- using huge batches only
- omitting null profiles
- omitting output consumption
- ignoring dead-code elimination
- mixing single-thread and multi-thread results
- changing schemas between implementations

## Roadmap

### Phase 1: Raw kernels

```text
AddInt32Raw
AddFloat64Raw
MulFloat64Raw
GreaterFloat64Raw
Bitmap.and
```

Compare:

```text
Vector API vs naive MemorySegment
```

### Phase 2: Wrappers and dispatch

```text
AddInt32.eval
Compute.add
```

Compare:

```text
raw vs wrapper vs public dispatch
```

### Phase 3: JNI

```text
AddInt32 JVM vs JNI per kernel
AddFloat64 JVM vs JNI per kernel
```

### Phase 4: Aggregations

```text
global min/max/sum/count
1BRC-style group-by over preloaded Arrow batches
```

Compare:

```text
JVM vs naive Java vs PyArrow
```

### Phase 4b: Slow-tier baselines

```text
at least one regex op (regex_match)
at least one Decimal128 op (Decimal128 add or mul)
at least one Unicode-aware string op (Unicode lower or upper)
```

Compare:

```text
project plain-Java vs PyArrow [vs JNI/FFM native, optional]
```

These benchmarks set the honest baseline gap before any optimization
work happens. They also produce the user-facing "how bad is bad"
numbers so workload-mix decisions are informed.

### Phase 5: Fusion

```text
fused projection/filter expressions
```

Compare:

```text
JVM fused vs JVM chain vs PyArrow chain vs JNI chain
```

Run on:

```text
x86_64
AWS Graviton arm64
```

### External-dependency risk note

Vector API (`jdk.incubator.vector`) and FFM
`MemorySegment.reinterpret` are load-bearing dependencies — see
`CORE_DESIGN.md §Risks & assumptions`. Scalar `MemorySegment` loops
are the documented fallback path and are already a benchmarked
baseline. If Vector API is reshaped or deprecated, the project
continues to work at scalar speed using the already-implemented
naive baselines; only the SIMD speedup is at risk, not the project.

## Summary

Benchmarks should be honest about the scenario.

The project is optimized for:

```text
warmed-up
single-threaded kernel execution
inside long-running JVM data engines
over preloaded Arrow buffers
with caller-managed parallelism
```

The most important comparisons are:

```text
raw kernel vs naive Java
JVM-native kernel vs JNI per-kernel
batch aggregation vs naive Java and PyArrow
fused JVM expression vs PyArrow/Arrow compute chain
Graviton JAR vs released PyArrow wheel
```

The benchmark story is not:

```text
Java beats C++ everywhere.
```

The benchmark story is:

```text
A JVM-native, JIT-specialized, fused Arrow compute path can be faster
and simpler to deploy than generic native compute calls in JVM data-engine scenarios.
```
