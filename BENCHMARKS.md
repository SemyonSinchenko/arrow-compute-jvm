# BENCHMARKS.md

This document describes the benchmark philosophy for the JVM-native Arrow compute project.

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
| Is JVM-native within reach of an out-of-process vectorized-interpreter reference? | `arrow-rs-baseline/` Cargo+Criterion subproject |
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

### Native reference (out-of-process, arrow-rs)

Question:

```text
Are JVM-native kernels within reach of a same-host out-of-process
vectorized-interpreter reference (arrow-rs + Criterion)?
```

The native reference lives in the `arrow-rs-baseline/` Cargo subproject
(see `spdd_requirements/requirements/11-bench-cleanup-and-cargo-reference.md`).
It is **out-of-process** by design: in-process JNI/FFM bridging is not
measured in the routine suite. JVM and Rust harnesses each run their
own process on the same bench host, with matched row sizes, seed, and
single-threaded steady-state mode. Neither side subtracts boundary
cost — because there is no shared boundary to subtract.

Forbidden claim: "Java is faster than Rust/C++." Permitted claim:
"JVM-native kernels are within a small factor of the arrow-rs
vectorized-interpreter reference on the same host in steady state."

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

Sub-question (block-size): at what chain depth k does Janino-fused codegen
stop tracking a handwritten AOT-fused anchor, and at what k does it stop
beating the naive reused-output chain? See SPDD 16
(`16-fusion-block-size-probe.md`), which sweeps `k ∈ {5, 20, 50}` over a
single-input multiply chain against four cells (naive ping-pong, naive
per-call alloc, Janino-fused, AOT-fused) plus an arrow-rs chain reference.
The two break-even points feed a future fusion-optimizer's block-sizing
heuristic.

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
macro/aggregation
macro/fusion
```

The out-of-process arrow-rs reference (`arrow-rs-baseline/`) is run
separately via `cargo bench` and is not a JMH suite.

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
Arrow-aware wrapper
vs
public Compute dispatch
```

Examples (per `*DispatchBenchmark`):

```text
wrapperEvalNewOutput      # output allocated inside @Benchmark (apples-vs-arrow-rs)
wrapperEvalReusedOutput   # output preallocated in @Setup(Trial) and reused (matches project API contract)
dispatchSmoke             # regression tripwire for monomorphic dispatch
```

Required dimensions:

```text
rows: 1K, 16K, 64K, 1M
nulls: 0%, 30%
output allocation policy:
  - wrapperEvalNewOutput    : allocated inside measured method
  - wrapperEvalReusedOutput : preallocated in @Setup(Trial), reused per invocation
```

Input buffers are prepared in `@Setup(Level.Trial)` for both cells.
`wrapperEvalNewOutput` allocates output per invocation; `wrapperEvalReusedOutput`
holds the output in `@State` and reuses it. The per-invocation delta between
the two cells measures the cost of Arrow Java's per-call buffer allocation
at that row size. See SPDD 14 (`spdd_requirements/requirements/14-output-allocation-scenarios.md`).

## Output allocation policy

Two scenarios are measured separately and reported with explicit labels.

**Scenario A — per-call output allocation** (`wrapperEvalNewOutput`)

Output `FieldVector` is constructed and `allocateNew(rows)`-ed inside
each `@Benchmark` method invocation, then closed at the end of the
method. This matches arrow-rs's API shape: `arrow_arith::numeric::*`
returns a fresh `PrimitiveArray<T>` per call, allocating data (and,
when nulls are present, validity) on every kernel invocation. Use
this cell for apples-to-apples comparison with the
`arrow-rs-baseline/` reference.

**Scenario B — reused output** (`wrapperEvalReusedOutput`)

Output `FieldVector` is constructed and `allocateNew(maxRows)`-ed once
in `@Setup(Level.Trial)`, held in `@State`, and reused on every
`@Benchmark` invocation. This matches the project's API contract:
`Compute.add(left, right, out)` puts output ownership on the caller,
and the standard usage pattern in JVM data engines is to allocate an
output once (per-task scratch, per-operator state, ThreadLocal pool)
and reuse it across many wrapper calls in a pipeline batch loop.
arrow-rs cannot run this scenario because its API returns by value;
the asymmetry is a design point of this project, not a comparison
flaw.

**Why both ship**

- Reporting only Scenario A flatters arrow-rs and understates the
  JVM library's design advantage.
- Reporting only Scenario B is unfair to arrow-rs (different API shape).
- Each cell answers a different question. Both numbers ship and are
  labeled with their policy. See SPDD 14
  (`spdd_requirements/requirements/14-output-allocation-scenarios.md`).

## Native reference (out-of-process, arrow-rs)

The native reference lives outside the JMH suite, in the
`arrow-rs-baseline/` Cargo subproject. See
`spdd_requirements/requirements/11-bench-cleanup-and-cargo-reference.md`
for the spec.

This is a scenario benchmark, not a pure Java-vs-C++/Rust benchmark.

Correct interpretation:

```text
JVM-native kernel (same host, in JVM process)
vs
arrow-rs vectorized-interpreter kernel (same host, in Rust process)
```

Incorrect interpretation:

```text
Java is faster/slower than Rust/C++ in general
```

Reported rows in this comparison:

```text
java_wrapper
arrow_rs_reference (out-of-process; from cargo bench)
```

Neither side subtracts boundary cost because there is no shared
boundary — JVM and arrow-rs each run in their own process on the same
bench host. The earlier in-process JNI/FFM measurement (see superseded
`12-native-baseline.md`) is retired: it was unwired and produced
exception-allocation noise rather than kernel numbers.

Boundary cost as a standalone question (cost of an empty FFM downcall
or JNI call) is out of scope here and belongs to a future requirement
focused specifically on call-boundary cost.

**Output-allocation asymmetry note.** `arrow_arith::numeric::*` returns
a fresh `PrimitiveArray<T>` per call; there is no preallocated-output
path through arrow-arith. So the per-call-alloc JVM cell
(`wrapperEvalNewOutput`) is the apples-vs-arrow-rs comparison; the
reused-output JVM cell (`wrapperEvalReusedOutput`) has no native
counterpart. That asymmetry is a design point of this project, not a
comparison flaw — see SPDD 14
(`spdd_requirements/requirements/14-output-allocation-scenarios.md`)
and §Output allocation policy above.

**Cross-library comparison scope.** This library is JVM-native; it is
not an attempt to mimic arrow-rs / arrow-cpp semantics. **Float64 cells
(`add_float64`, `mul_float64`) are the recommended headline cross-library
comparison** — both libraries default to IEEE-754 unchecked arithmetic
there, so the kernel work is genuinely apples-to-apples. Integer and
other cells (e.g., `add_int32`) are **developer-facing diagnostics for
in-library tracking**, not headline cross-library numbers: the JVM
library follows JVM-idiomatic defaults (wrapping arithmetic per
JLS § 4.2.2, where `Integer.MAX_VALUE + 1 == Integer.MIN_VALUE`), while
arrow-rs defaults to overflow-checking per the Arrow Compute spec.
Visible gaps on those cells reflect different ecosystem defaults rather
than implementation quality, and they should not be reported as
cross-library performance claims without an explicit semantics-matched
counterpart (e.g., a future `wrapperEvalReusedOutputChecked` cell using
`AddInt32CheckedRaw` per `CORE_DESIGN.md §Options and flags`).

## DRAM bandwidth ceiling

At 1M rows the JVM raw kernel is memory-bandwidth-bound, not
compute-bound. To interpret the 1M-row throughput honestly, the bench
host's physical DRAM ceiling must be measured and recorded once.

Procedure (one-shot per bench host):

```text
mbw -t 0 -n 5 1024            # MEMCPY mode, 5 iterations, 1024 MiB block
                              # OR equivalent STREAM Triad run
```

Record in this file (the bench host hardware should match the host
that produced the JMH numbers in `build/results/jmh/`):

```text
Host:                  <CPU model, RAM type>
mbw / STREAM result:   <GB/s>
JMH raw 1M-row peak:   <ops/ms × 1048576 × 12 bytes/row = GB/s>
Fraction of ceiling:   <%>
```

A 1M-row add kernel reads two input arrays and writes one output —
`3 × elementWidth` bytes per row. For int32, that is 12 bytes/row;
for float64, 24 bytes/row.

Re-record only when the bench host changes.

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
output allocation policy ({per-call | reused})  # required for wrapper benchmarks per SPDD 14
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
- reporting only one output-allocation policy and calling it "the" wrapper number when the API contract supports reuse (see SPDD 14)

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

Wrapper benchmarks report two output-allocation cells per dispatch
class (per SPDD 14): `wrapperEvalNewOutput` (per-call alloc,
apples-vs-arrow-rs) and `wrapperEvalReusedOutput` (preallocated,
matches the project's API contract). Wrapper-cost claims are reported
per output-allocation policy, not as a single number.

### Phase 3: Out-of-process native reference

```text
AddInt32 JVM vs arrow-rs reference (same host)
AddFloat64 JVM vs arrow-rs reference (same host)
```

Run from the `arrow-rs-baseline/` Cargo subproject via `cargo bench`.
In-process JNI/FFM bridging is not part of the routine benchmark suite
(see superseded `spdd_requirements/requirements/12-native-baseline.md`).

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

SPDD 15 (`15-janino-runtime-codegen-feasibility-probe.md`) established the
codegen substrate: Janino accepts `jdk.incubator.vector` imports and the
resulting bytecode runs at AOT-equivalent throughput under the project's
standard JVM args.

The first fusion benchmark is SPDD 16 (`16-fusion-block-size-probe.md`),
which probes the *block-size dimension*: how many chained ops can a single
Janino-fused method absorb before HotSpot's inlining budget or Janino's
source/bytecode size start to degrade throughput against a handwritten
AOT-fused anchor? Four JVM cells (naive ping-pong reused output, naive
per-call alloc, Janino-fused reused output, AOT-fused-handwritten reused
output) plus an arrow-rs chain reference. `k ∈ {5, 20, 50}`. The probe
reports two break-even points that feed a future fusion-optimizer
SPDD's block-sizing heuristic.

The probe benchmark lane is intentionally narrow: `mul-float64-codegen` reused
output with conventional row sizes (`1024`, `16384`, `65536`, `1048576`) and
`nullPercent=0` to match existing suite conventions.

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
raw kernel vs naive Java (one-time, recorded in this file)
JVM-native kernel vs arrow-rs out-of-process reference (same host)
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
