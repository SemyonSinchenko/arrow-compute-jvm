# SPDD Analysis: Two Output-Allocation Scenarios for Wrapper Benchmarks

## Original Business Requirement
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

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Wrapper benchmark cell taxonomy: `BENCHMARKS.md` already defines two wrapper scenarios (`wrapperEvalNewOutput`, `wrapperEvalReusedOutput`) and policy-specific interpretation for native comparison.
- Fast-tier dispatch benchmark class pattern: `*DispatchBenchmark` classes exist for arithmetic, division, aggregation, and UTF-8 ops with shared JMH dimensions and `dispatchSmoke` lane.
- Caller-owned buffer contract baseline: SPDD 13 and existing architecture docs establish that output ownership belongs to caller, making reused-output measurement a first-class valid scenario.
- Benchmark governance concepts: reporting rules, anti-patterns, and roadmap phase entries are already used as normative benchmark policy.
- Arrow-rs comparison boundary: native reference section already recognizes API asymmetry between by-value native return and preallocated JVM output.

#### New Concepts Required
- Dual wrapper execution lanes in code, not docs only: benchmark classes need explicit paired wrapper lanes that mirror the two reporting scenarios.
- Output-allocation policy as mandatory benchmark metadata in run artifacts, not just in narrative documentation.
- Scenario-specific claim model: wrapper-performance statements must be emitted per scenario rather than as a single blended "wrapper number".
- Naming lock-in as governance artifact: method names become part of benchmark comparability contract across iterations.

#### Key Business Rules
- Fairness rule: apples-to-apples JVM vs arrow-rs comparisons are only valid for per-call allocation scenario.
- Value-proposition rule: project-contract benchmarking must include reused-output scenario to reflect intended engine usage.
- Isolation rule: scenario delta must isolate output-allocation policy while holding inputs, dimensions, and JMH settings constant.
- Semantics rule: reused-output scenario must not add manual output reset behavior beyond wrapper-defined semantics.
- Scope rule: this SPDD governs benchmark policy and benchmark harness behavior, not raw kernels, wrapper internals, or public compute facade behavior.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Treat this requirement as benchmark-governance alignment across three layers: benchmark documentation, benchmark method naming, and benchmark class behavior.
- Keep the existing dispatch benchmark architecture and dimension grid intact, then introduce allocation policy as the only intentional variable between wrapper lanes.
- Maintain dual framing in reporting: one scenario for external peer comparison and one scenario for contract-realistic JVM engine usage.

#### Key Design Decisions
- Single wrapper lane vs dual wrapper lanes: single lane is easier to maintain but cannot represent both fairness and contract realism; recommend dual lanes with explicit names because each answers a different business question.
- Policy encoded in prose only vs policy encoded in code naming and reporting fields: prose-only is ambiguous and drifts over time; recommend hard naming contract plus required report field for durable comparability.
- Wrapper reset in reused scenario vs strict reliance on wrapper semantics: explicit reset may hide wrapper correctness bugs and contaminate performance interpretation; recommend no manual reset and treat any required reset as wrapper defect.
- Broaden scope to slow-tier/native changes vs constrain to fast-tier wrapper reporting: broader scope adds noise and delays decision value; recommend fast-tier-only scope as stated by requirement.

#### Alternatives Considered
- Report only per-call allocation scenario: rejected because it understates the project API contract advantage and misrepresents expected engine usage.
- Report only reused-output scenario: rejected because it is not comparable to arrow-rs API shape and creates unfair external claims.
- Add third pooling scenario now: rejected because it complicates reporting before closing the primary fairness-vs-contract gap.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Acceptance scope tension: scope requests dispatch benchmark lane changes, while acceptance also states no Java source changes are part of this SPDD deliverable; governance intent is clear but delivery boundary is mixed.
- Reporting artifact boundary: requirement mandates policy as reported dimension, but does not fully specify whether enforcement is doc-level, metadata API-level, or CI-level.
- Derived delta line status: recommended as useful diagnostic, but optionality may create inconsistent publication practices across benchmark reports.

#### Edge Cases
- Reused-output correctness over variable row counts: trial-level preallocation with per-invocation reuse across multiple `rows` values depends on wrappers correctly setting `valueCount` and null state every call.
- Null-profile transitions: moving between `nullPercent=0` and `30` in reused output can expose stale validity assumptions if wrappers fail to overwrite state as contract expects.
- String/boolean output differences: bit-packed outputs (for UTF-8 startsWith) may show different allocation sensitivity patterns than fixed-width arithmetic, affecting interpretation of cross-op scenario deltas.

#### Technical Risks
- Code-documentation drift risk: `BENCHMARKS.md` is already aligned to SPDD 14 wording, but current benchmark classes still use `wrapperEvalThin`, so published policy and executable harness are out of sync.
- Comparability risk across historical runs: renaming benchmark methods changes series continuity unless migration notes map old and new lane names.
- Measurement contamination risk: if reused-output lane accidentally allocates/cleans resources in measured method, scenario delta becomes invalid and may overstate allocation effects.
- Governance enforcement risk: without automated checks, future benchmark additions may omit one policy lane or reintroduce ambiguous naming.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `14-output-allocation-scenarios.md` exists with required structure | Yes | Present at `spdd_requirements/requirements/14-output-allocation-scenarios.md`. |
| 2 | `BENCHMARKS.md` amended across wrapper benchmarks, output policy section, reporting rules, anti-patterns, roadmap phase, native reference | Yes | Current `BENCHMARKS.md` includes the required sections and SPDD 14 framing. |
| 3 | `BENCHMARKS.md` amendments reference SPDD 14 explicitly | Yes | Multiple explicit SPDD 14 references are present in required sections. |
| 4 | No Java source changes are part of SPDD acceptance deliverable | Partial | Repository already contains benchmark Java classes with `wrapperEvalThin`; downstream implementation boundary is still active and code/docs are not yet fully converged to the named two-lane convention in code. |
