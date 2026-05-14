# Requirement: JVM-native Arrow Compute MVP

## Business requirement

Build a small JVM-native Arrow compute spike that evaluates fixed-width primitive kernels and a curated set of string kernels directly over Arrow buffers using modern Java, `MemorySegment`, and Vector API. Establish both the **fast tier** (SIMD `raw/` kernels) and the **slow tier** (`wrapper/slow/` plain-Java kernels) so the library is API-complete; users decide based on their workload mix.

## Scope

Include:

- build infrastructure (project `arrow-compute`, root package `io.github.semyonsinchenko.arrowcompute`);
- Arrow Java memory bridge (`BufferRefs.retain`, `SegmentViews`, `Validity`, `Bitmap`, `Checks`, `Errors`);
- one raw kernel without Arrow dependencies (`AddInt32Raw`);
- one public compute call over Arrow vectors (`Compute.add`);
- several null-safe scalar kernels (arithmetic, comparisons);
- one valid-only scalar kernel (`DivInt32`);
- one simple aggregation (`SumInt64`);
- at least one fast-tier string kernel (`StartsWithUtf8`);
- at least one slow-tier kernel with pluggable interface (`RegexMatchUtf8` behind `RegexMatcher`);
- one slow-tier numeric kernel (`AddDecimal128`);
- JMH benchmarks with the foundation baseline matrix;
- one macrobenchmark over preloaded Arrow data (1BRC-style aggregation);
- one fused-expression benchmark;
- one native-baseline (JNI or FFM) comparison.

## Non-goals

Do not build:

- full Arrow Compute parity;
- function registry / `Datum` / UDF infrastructure;
- SQL engine or Catalyst-style optimizer;
- joins of any kind;
- sort engine;
- distributed execution;
- grouped hash aggregation engine (the SoA state layout is defined; the hash table is not).

Strings and decimal are **in scope** via the two-tier kernel design. Fast-tier string ops (e.g., `StartsWithUtf8`) are part of the value proposition; slow-tier ops (regex, Decimal128 arithmetic, Unicode) ship for completeness with honest benchmark gaps.

## Constraints

- Raw kernels are small, standalone, Arrow-free classes in flat `raw/` package; entry-point name is `computeAll` (or `noNulls`/`skipNulls`/`validOnly`).
- Slow-tier kernels live in `wrapper/slow/`, no raw layer.
- Wrappers handle Arrow Java vectors, memory lifetime (`BufferRefs.retain`), validity, segment creation; entry-point is `eval`.
- Dispatch is explicit, outside the hot path, public (consumers may extend).
- Kernels are single-threaded; parallelism belongs to callers.
- Use Arrow Java APIs where available in non-hot code (see `ARROW_JAVA_API_USAGE.md`).
- Options pass as primitive flags at wrapper signatures only; no options objects.
- Tests run with `-Darrow.memory.debug.allocator=true`.

## Acceptance criteria

- Layer-specific tests exist (raw without Arrow, wrapper with Arrow, dispatch).
- JMH compares raw kernels against naive Java with the baseline matrix from `BENCHMARKS.md §Benchmark goals`.
- Wrapper and dispatch overhead are measurable.
- At least one PyArrow or native-baseline comparison exists for fast-tier ops.
- Every slow-tier kernel ships with a PyArrow-baseline benchmark explicitly labeled SLOW tier and cross-referencing `CORE_DESIGN.md §Two-tier kernel design`.
- Results are documented honestly, with no claims that contradict `BENCHMARKS.md` interpretation rules.

## Cross-references

- `AGENTS.md` — agent-contributor rules.
- `CORE_DESIGN.md` — architecture, two-tier kernel design, design decisions, risks.
- `BENCHMARKS.md` — baseline matrix, slow-tier benchmarks.
- `ARROW_JAVA_API_USAGE.md` — Arrow Java reuse policy.
- `DEVELOPMENT_PLAN.md` — full picture of iterations.
