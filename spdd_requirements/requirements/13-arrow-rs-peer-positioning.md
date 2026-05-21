# Requirement: Reposition as arrow-rs peer; adopt caller-owns-buffer contract

## Business requirement

The project's audience is library and engine developers building JVM data systems — custom query engines, JVM analogues of Polars/DataFusion, Spark-internal compute paths — not interactive analysts wiring `Compute.add(...)` into notebooks. This was implicit in the choice of `MemorySegment` + Vector API + manual lifetime management, but several foundation-doc claims still frame the wrapper layer as a safety boundary protecting "casual users." That framing is removed.

The project aims at the **arrow-rs / arrow-cpp** layer of the columnar-compute stack, not at the **DataFusion / Polars** layer above it. Both arrow-rs and arrow-cpp define their kernels around a documented "caller owns the input arrays for the duration of the kernel call" contract; the JVM project adopts the same contract.

Benchmark data from the JMH suite versus the `arrow-rs-baseline/` reference shows the current Arrow-aware wrapper layer costs approximately 2× over the raw kernel at every measured row size — at 1024 rows where per-call overhead dominates, and at 1 048 576 rows where the kernel is DRAM-bandwidth-bound. The cost is structural, not a JIT failure:

- `BufferRefs.retain(left, right, out)` performs six atomic refcount increments per call. These are full memory fences on x86; HotSpot cannot elide them without violating memory-ordering correctness.
- `Validity.markAllValid(out, n)` writes `n` bits of `0xFF` to the output validity buffer on the `null_count == 0` happy path — 128 KB of extra writes at 1 M rows on the bandwidth-bound path.
- Three per-call `MemorySegment.ofAddress(...).reinterpret(...)` invocations create short-lived `MemorySegment` objects; escape analysis usually but not always eliminates them.
- `Checks.zeroSliceOffset(...)` and `BufferRefs.retain(...)` accept `FieldVector...` varargs and allocate a per-call `FieldVector[]`; `BufferRefs` further allocates an internal `ArrayList<ArrowBuf>`.
- The polymorphic `FieldVector` parameter at the public-facade level — necessary for ergonomics — currently also appears inside the wrapper layer in some adapters, leaving a bimorphic/megamorphic call-site risk in place.

Removing this overhead requires a contract change, not a perf hack. The structural change is: wrappers no longer retain Arrow buffers; callers ensure inputs and outputs remain live for the call duration. Validity buffers are not eagerly materialized when `null_count == 0`, per the Arrow IPC spec. Wrapper entry points take concrete vector types so dispatch stays monomorphic from the wrapper inward.

This requirement supersedes the implicit safety-boundary framing in `CORE_DESIGN.md §Arrow-aware wrapper layer` and amends `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`, `spdd_requirements/README.md §Core constraints`, and the framing footnote and forward reference in `11-bench-cleanup-and-cargo-reference.md`. It does not contradict the JVM-tax-probe scope or the fusion-vs-interpreter positioning; it sharpens the distinction between structural wrapper cost (removed here) and per-pipeline materialization cost (still addressed by future fusion work).

## Scope

1. **Caller-owned input and output buffers.** New safety contract: callers must keep all input and output `FieldVector`s live for the entire wrapper call. Wrappers no longer retain buffers. The `BufferRefs.retain(left, right, out)` step is deleted from the wrapper hot path. `BufferRefs` itself survives as a public utility for callers (tests, ingestion paths, async hand-off) that genuinely need explicit retain/release pairing — it is no longer invoked from inside the wrapper layer.

2. **Lazy validity per Arrow IPC spec.** On the `null_count == 0` happy path, wrappers do not call `Validity.markAllValid(out, n)`. The output vector follows the Arrow IPC convention: when `null_count == 0`, the validity-buffer contents are unspecified and consumers must check `null_count` before reading the validity buffer. Wrappers set the output's null count to `0` explicitly so the convention is observable downstream. On the `null_count > 0` path, validity propagation runs as today via `Validity.propagateBinary` / `Validity.propagateUnary`.

3. **Monomorphic wrapper entry points.** Wrappers accept concrete vector types (`IntVector`, `Float8Vector`, `BigIntVector`, `BitVector`, `VarCharVector`, etc.) in their `eval(...)` signatures, not `FieldVector`. The public `Compute.*` facade keeps `FieldVector` parameters for ergonomics; dispatch resolves to a concrete-typed wrapper from there. The wrapper-to-raw call chain is therefore monomorphic from the wrapper entry inward, regardless of how many vector subtypes flow through the facade. Today's wrapper code already satisfies this in most places; the SPDD locks it in as an invariant so future drift toward polymorphic wrapper signatures is prohibited.

4. **Segment extraction is amortized.** The three `SegmentViews.data(vector, byteSize)` calls per binary-kernel invocation are restructured so the `MemorySegment` view is either (a) cached as a lazy field on a wrapper-layer adapter for the vector, or (b) extracted once at the wrapper entry and passed by reference into the raw kernel. The Java-implementation iteration chooses the mechanism; the SPDD requires that `MemorySegment.ofAddress(...).reinterpret(...)` is not invoked three times per binary-kernel call on the steady-state hot path. The `SegmentViews` lifetime invariant (no escape from the call) remains in force.

5. **Checks allocate nothing.** `Checks.sameValueCount`, `Checks.outputCapacity`, and `Checks.zeroSliceOffset` remain for correctness but are restructured so they do not allocate per call — no varargs `FieldVector[]`, no internal `ArrayList`. Each runs in O(1) per binary call. Optionally, the project may gate these checks behind a JVM system property (`-Darrowcompute.checks=on|off`, default `on`) so engines wiring the library into an inner pipeline loop can disable them after their own integration tests pass — matching the `debug_assertions` pattern in arrow-rs. The toggle is optional within this SPDD; the no-allocation requirement is mandatory.

6. **Public documentation of the contract.** Javadoc on every `Compute.*` static method and on every fast-tier `<Op><Type>.eval(...)` wrapper method documents in plain English that the caller is responsible for buffer lifetime and that the wrapper does not retain. Javadoc cites the analogous contract in arrow-rs / arrow-cpp so readers crossing from those ecosystems find the inheritance explicit.

7. **Benchmark coverage of the new contract.** Each fast-tier dispatch benchmark gains a `wrapperEvalThin` cell that measures the new thin-wrapper path. The existing `wrapperEval` cell either becomes a comparison baseline against the legacy heavy path (until the heavy path is removed) or is repurposed in place. The `BENCHMARKS.md` headline claim is updated, after implementation lands, from "wrapper carries a materialization tax" to "thin wrapper at native parity within a stated margin across all row sizes." The margin is set by the actual measurement, not by this SPDD.

8. **Safe-mode wrapper is explicitly deferred.** A future "safe-mode" wrapper for ad-hoc / interactive callers — equivalent to today's heavy wrapper, renamed and isolated to its own package — is documented as a possible v2 affordance. It is **not** part of MVP. The SPDD names it only so MVP wrapper-layer design does not close the door on it.

## Non-goals

- A parallel `Compute.Safe.add(...)` API in MVP. Considered as a v2 affordance only.
- Removing `BufferRefs` entirely from the codebase. It survives as a public utility for callers whose buffers genuinely cross thread or stage boundaries.
- Changes to the `wrapper/slow/` tier hot loop. The contract change applies to fast-tier wrappers. Slow-tier wrappers inherit the caller-owned-buffer contract (so callers don't have to retain twice) but their internal shape is otherwise untouched.
- A registry, `Datum`, or `KernelHandle` system. Pre-resolved-handle work is already documented as v2 in `CORE_DESIGN.md §Pre-resolved kernel handle (v2)` and is not pulled into this SPDD.
- A pluggable allocator-debug toggle inside `Compute.*`. Allocator debug stays a JVM system-property concern, set by the caller, not a wrapper-API concern.
- Any modification to the raw kernel layer. Raw kernels are unaffected; the change is entirely above the raw boundary.
- Re-litigating the JVM-tax-probe scope or the fusion-vs-interpreter framing. Both positions stand. SPDD 13 only sharpens the per-call vs per-pipeline cost distinction.

## Constraints

- All amendments to foundation docs (`CORE_DESIGN.md`, `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`, `spdd_requirements/README.md`) listed in §Cross-references must land in the same change set as this SPDD. Foundation docs are ground truth per `spdd_requirements/README.md`; an SPDD that contradicts the foundation without amending it is broken by the project's own rules.
- The new contract is documented before any wrapper code changes. Public Javadoc for `Compute.*` is part of the SPDD's deliverable surface and not deferred to the Java-implementation iteration.
- Tests continue to run with `-Darrow.memory.debug.allocator=true`. Allocator-leak detection moves from "the wrapper retains, so leaks surface at wrapper close" to "the caller retains, so leaks surface at the caller's batch close." Test-suite expectations may require adjustment in the implementation iteration; the debug-mode invariant itself stands.
- Framing in §Business requirement is "the project is a peer to arrow-rs," not "the project is a faster arrow-rs." Headline claims stay on the JVM-tax-probe axis: fusion-vs-materialization, not Hotspot-vs-LLVM.
- The forward reference in `11-bench-cleanup-and-cargo-reference.md` line 49 to a future `13-onebrc-...` collides with this SPDD's id. As part of acceptance, that line is retargeted to a new id (the next free slot after 13); the future 1BRC SPDD lands under that id.
- The framing footnote in `11-bench-cleanup-and-cargo-reference.md` line 36 ("the strategically-aligned answer is fusion") needs a one-line amendment acknowledging that SPDD 13 has since separated the structural wrapper cost (removed here) from the materialization cost (still addressed by fusion). The amendment refines the prior framing rather than contradicting it.

## Acceptance criteria

- `spdd_requirements/requirements/13-arrow-rs-peer-positioning.md` exists with the structure above.
- `CORE_DESIGN.md` amended:
  - §Public API layer: note that dispatch resolves to concrete-typed wrappers from the facade.
  - §Arrow-aware wrapper layer: opening claim reframed; retain/release removed from responsibilities; `BufferRefs` paragraph rewritten; example code replaced with the thin-wrapper shape.
  - §Memory and lifetime model: opening adds the caller-owned-buffer invariant.
  - §`SegmentViews` lifetime invariant: dependency reworded from "`BufferRefs` retain/release pairing" to "the caller's buffer-lifetime contract."
  - §`BufferRefs` subsection: demoted to "public utility for callers."
  - §`Validity` subsection: notes that `markAllValid` is no longer called from the wrapper happy path.
  - §Design decisions: "Wrappers are the Arrow safety boundary" entry rewritten; `MemorySegment.ofAddress` entry updated.
  - §Risks & assumptions, performance assumption: kill-criterion measurement target is the thin wrapper.
- `AGENTS.md` amended:
  - §Arrow buffer access example reframed as caller responsibility.
  - §Lifetime invariant for `MemorySegment` views: dependency reworded.
  - §API design: "casual users" phrasing removed or replaced.
  - §Default invariants: new bullet for caller-owned input/output buffers.
- `ARROW_JAVA_API_USAGE.md` §2 amended: refcount discipline is a caller concern. §15 adds one line confirming slow-tier wrappers follow the same caller-owned-buffer contract.
- `spdd_requirements/README.md` §Core constraints lines 49–51 amended: retain bullet replaced; `MemorySegment` views bullet reworded; allocator-debug bullet preserved.
- `11-bench-cleanup-and-cargo-reference.md` line 49 `13-onebrc-...` reference retargeted to the next free id; line 36 framing-footnote amendment added.
- `12-native-baseline.md` `Superseded by` notice unchanged; SPDD 13 does not affect it.
- No Java source-code changes are part of this SPDD's acceptance. Java code changes ship under their own downstream iteration; this SPDD's deliverables are the requirement doc plus the enumerated foundation-doc amendments only.

## Cross-references

- **Supersedes**: nothing. No prior SPDD locked in the safe-wrapper contract; that contract lived only in foundation docs.
- **Amends**:
  - `CORE_DESIGN.md` — §Public API layer, §Arrow-aware wrapper layer, §Memory and lifetime model, §Design decisions, §Risks & assumptions.
  - `AGENTS.md` — §Arrow buffer access, §Lifetime invariant for MemorySegment views, §API design, §Default invariants.
  - `ARROW_JAVA_API_USAGE.md` — §2 Memory management, §15 Slow tier uses Arrow Java fully.
  - `spdd_requirements/README.md` — §Core constraints repeated across iterations (retain and `MemorySegment` view bullets).
  - `11-bench-cleanup-and-cargo-reference.md` — line 36 framing footnote, line 49 forward-reference retargeting.
  - `BENCHMARKS.md` — headline-claim update from "wrapper materialization tax" to "thin wrapper at parity," to be written by the downstream implementation iteration once measurements are available.
- **Project framing**: project memories on JVM-tax-probe scope and fusion-vs-interpreter positioning remain authoritative; SPDD 13 sharpens the per-call vs per-pipeline cost distinction without invalidating either.
- **Forward refs**: any future `14-onebrc-...` (or whichever next free id is assigned) SPDD inherits the renumbered slot from SPDD 11's prior forward reference.
