# Requirement: Two output-allocation scenarios in wrapper benchmarks; report both

## Business requirement

The project's API contract intends output reuse. `CORE_DESIGN.md` documents `Compute.add(left, right, out)` as preallocated-output-by-caller. SPDD 13 made buffer lifetime the caller's responsibility. The standard usage pattern in JVM data engines — Spark task scratch buffers, Flink operator state, Trino driver-local buffers, ThreadLocal output pools — is to allocate an output once and reuse it across many wrapper calls in a pipeline batch loop. This is industry practice in JVM data engines, not a benchmark trick.

arrow-rs's API does not support output reuse. `arrow_arith::numeric::add` returns `PrimitiveArray<T>` by value; the buffer is allocated inside the kernel and the caller cannot pre-supply it. Comparing JVM-with-reused-output against arrow-rs is not apples-to-apples; comparing JVM-with-per-call-alloc against arrow-rs is.

One number cannot honestly cover both scenarios. The current `wrapperEval` cell is the per-call-alloc cell. It mirrors arrow-rs's API shape, but it does not measure the JVM library's design advantage and does not reflect how the API is actually used by engines. Reporting only this one number flatters arrow-rs (hides the API's amortization advantage) and understates the JVM library's value proposition. Reporting only a reused-output number would be unfair to arrow-rs.

The fix is structural: each fast-tier dispatch benchmark reports **two** wrapper cells with explicit, unambiguous labels — one with per-call output allocation (apples-vs-arrow-rs) and one with reused output (matches the project's contract). `BENCHMARKS.md §Wrapper benchmarks` and `§Reporting rules` are updated so the output-allocation policy is a first-class reported dimension, not a footnote.

This requirement supersedes nothing and amends `BENCHMARKS.md`. It does not modify the raw-kernel layer, the wrapper layer, the `Compute.*` facade, or any code outside benchmark harnesses.

## Scope

1. **Two cells per fast-tier dispatch benchmark.** Each `*DispatchBenchmark` (e.g., `AddInt32DispatchBenchmark`, `MulFloat64DispatchBenchmark`, `StartsWithUtf8DispatchBenchmark`, etc.) gains a second wrapper cell.

   - **Cell A — `wrapperEvalNewOutput`**: output `FieldVector` is constructed and `allocateNew(rows)`-ed inside the `@Benchmark` method. This is today's `wrapperEval` (renamed for clarity). Output is closed at the end of the method. Matches arrow-rs's per-call allocation semantics.
   - **Cell B — `wrapperEvalReusedOutput`**: output `FieldVector` is constructed and `allocateNew(maxRows)`-ed once in `@Setup(Level.Trial)` and held in `@State`. The `@Benchmark` method reuses it on every invocation. No allocation or close inside the measured method. Matches the project's API contract.

2. **Both cells consume the output via `Blackhole` identically.** `bh.consume(out)` after the wrapper call in both cells. The dead-code-elimination prevention strategy is the same so the cell-to-cell delta is honest.

3. **Reused-output cell does not modify wrapper semantics.** Per SPDD 13, the wrapper sets `out.setNullCount(0)` on the happy path (no validity write) and `out.setValueCount(n)` always. The reused-output cell relies on the wrapper to overwrite the output state correctly across invocations; there is no extra reset call (no `out.reset()`, no manual zero-fill). If the wrapper requires such a reset to produce correct output on a reused buffer, that's a wrapper bug to be filed separately, not benchmark-state management.

4. **Same input policy across cells.** Input `FieldVector`s for both `left` and `right` are constructed in `@Setup(Level.Trial)` and reused. This already matches today's pattern; nothing changes for inputs.

5. **Same dimensions across cells.** Both cells run over `rows ∈ {1024, 16384, 65536, 1048576}` and `nullPercent ∈ {0, 30}` (the existing dimension grid). No new dimensions added.

6. **Output-allocation policy is a first-class reported dimension.** `BENCHMARKS.md §Wrapper benchmarks` and `§Reporting rules` are amended so every wrapper benchmark report includes an explicit "output allocation policy: { per-call | reused }" line. Existing reporting fields (rows, null profile, type, etc.) stay; this becomes one more.

7. **Headline-claim split in `BENCHMARKS.md`.** Where the SPDD-13 acceptance criteria call for an eventual headline-claim update from "wrapper materialization tax" to "thin wrapper at parity," the post-SPDD-14 headline becomes two claims:

   - "Scenario A (per-call output alloc, apples-vs-arrow-rs shape): thin wrapper within stated margin of arrow-rs at 1M rows."
   - "Scenario B (reused output, matches project API contract): thin wrapper at native parity or better at 1M rows, on bandwidth-bound workloads."

   Numbers are filled by the downstream implementation iteration once measurements are available.

8. **Benchmark naming makes the policy obvious.** Cells are named `wrapperEvalNewOutput` and `wrapperEvalReusedOutput` (or equivalent unambiguous names). Generic names like `wrapperEvalFast` or `wrapperEvalOptimized` are prohibited because they hide the scenario.

## Non-goals

- Shipping a ThreadLocal output-pool implementation in `Compute.*` API. The library does not ship a pool. Engines that want one build it themselves; the wrapper contract permits any reuse strategy.
- Modifying the native Rust benchmark to add a preallocated-output path. arrow-rs's API doesn't support it; the comparison stays at "per-call alloc on both sides" for Cell A. The Cell B asymmetry is the point.
- Resetting `FieldVector` internal state between iterations in Cell B beyond what the wrapper already does. The wrapper sets `valueCount` and `nullCount`; that's enough. If the wrapper requires a manual reset to produce correct results on a reused buffer, that's a wrapper bug — file separately.
- Changing the raw kernel layer, wrapper layer, or `Compute.*` facade. This SPDD is benchmark-only.
- Adding a third "scenario C" with caller-owned-pool or other patterns. Two cells are enough to tell the story; further variants are not justified by current measurement gaps.
- Touching slow-tier (`wrapper/slow/`) benchmarks. Slow-tier reporting policy is unchanged.

## Constraints

- Both cells use the same JMH `@State`, `@Setup`, `@Param`, `@Warmup`, `@Measurement`, and `@Fork` settings. The only difference between them is the output-allocation policy.
- `BENCHMARKS.md §Wrapper benchmarks` currently says "output: allocated inside measured method" and "Wrapper and dispatch lanes allocate output per invocation and keep input buffers prepared in trial setup." This is correct for Cell A and wrong for Cell B; the amendment must split the policy by cell.
- `BENCHMARKS.md §Benchmark anti-patterns` gains a bullet: "reporting only one output-allocation policy and calling it 'the' wrapper number when the API contract supports reuse."
- `BENCHMARKS.md §Reporting rules` includes "output allocation policy ({per-call|reused})" as a required reported field.
- `BENCHMARKS.md §Roadmap Phase 2` mentions both cells; "raw vs wrapper vs public dispatch" gains a sub-axis for output policy.
- The naming convention (`wrapperEvalNewOutput`, `wrapperEvalReusedOutput`) is locked in this SPDD so dispatch-benchmark renames stay grep-friendly. The current `wrapperEval` method name is retired in favor of the two explicit variants; no benchmark keeps the bare `wrapperEval` name.
- Comparison between the two cells inside a single dispatch class is itself a useful diagnostic: `wrapperEvalNewOutput - wrapperEvalReusedOutput` per row count = the project's per-call Arrow-Java allocation cost at that size. This delta is worth reporting as a derived line in the published benchmark table, but the SPDD does not mandate it; it is a recommended reporting convention.

## Acceptance criteria

- `spdd_requirements/requirements/14-output-allocation-scenarios.md` exists with the structure above.
- `BENCHMARKS.md` amended:
  - §Wrapper benchmarks: two cells described with explicit policy per cell. The placeholder cells (`wrapperEvalThin`, `dispatchSmoke`) are renamed/expanded to `wrapperEvalNewOutput`, `wrapperEvalReusedOutput`, and `dispatchSmoke`. "Output: allocated inside measured method" replaced with per-cell policy.
  - New §Output allocation policy subsection placed after §Wrapper benchmarks. Documents the design intent (caller pre-allocates, reuses across iterations), the engine-reality of ThreadLocal output reuse, the arrow-rs API asymmetry, and the two-cell reporting rule.
  - §Reporting rules: adds "output allocation policy ({per-call|reused})" as a required reported field.
  - §Benchmark anti-patterns: adds bullet about hiding the output-allocation policy.
  - §Roadmap Phase 2: mentions both cells; clarifies that the wrapper-cost claim is a function of output policy.
  - §Native reference: adds a short note that arrow-rs's `numeric::*` allocates per call by API design, so Cell A is the apples-vs-arrow-rs cell and Cell B has no native equivalent (and that asymmetry is the project's design point, not a comparison flaw).
- The amendments to `BENCHMARKS.md` reference SPDD 14 explicitly so the rationale is traceable.
- No source-code Java changes are part of this SPDD's acceptance. JMH benchmark code changes ship under their own downstream iteration; this SPDD's deliverables are the requirement doc plus the enumerated `BENCHMARKS.md` amendments only.

## Cross-references

- **Supersedes**: nothing.
- **References**: SPDD 13 (`13-arrow-rs-peer-positioning.md`) — the caller-owns-buffer contract introduced there is what makes Cell B meaningful.
- **Amends**: `BENCHMARKS.md` — §Wrapper benchmarks, §Reporting rules, §Benchmark anti-patterns, §Roadmap Phase 2, §Native reference. New §Output allocation policy subsection added.
- **Does not amend**: `CORE_DESIGN.md`, `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`, `spdd_requirements/README.md`. Those docs are already consistent with the design contract this SPDD only operationalizes in benchmarks.
- **Project framing**: this sharpens SPDD 13's "peer to arrow-rs with a different API contract" framing. The honest comparison is two-axis: (a) same-API-shape (Cell A), (b) project-contract-shape (Cell B). Both numbers ship.
