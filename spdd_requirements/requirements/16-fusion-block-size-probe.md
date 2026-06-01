# Requirement: Block-size probe for runtime-codegen fusion (MulFloat64 chain)

## Business requirement

SPDD 15 established that Janino accepts `jdk.incubator.vector` imports and
that a Janino-compiled VectorAPI kernel runs at the same steady-state
throughput as its AOT-compiled twin (within noise). That answers the
*feasibility* question. The next question is the *design* question: **what is
the right fusion block size for this project's fusion strategy?**

The project's fusion stance is deliberately *not* Umbra/CedarDB-style
whole-pipeline codegen. Whole-pipeline codegen pays a heavy structural cost
(LLVM IR layer, manual SIMD lowering, slow query-time compile) and lands the
entire query plan inside one giant generated function. Umbra and CedarDB
absorb that cost because they target sub-second analytical workloads with
mature engineering investment. This project is positioned for long-running
JVM data engines (cf. `BENCHMARKS.md`: "single-threaded steady-state
warmed-up JVM"), where Janino is hot, compiled code is cached, and the C2
compiler has already warmed every reachable callsite.

In that setting a third strategy is structurally attractive: **block fusion**.
A query plan is split into vector-at-a-time *blocks* of k chained operations,
each block compiled to one Janino-generated VectorAPI method, with vectorized
hand-offs between blocks. Small blocks behave like X100 vector-at-a-time
interpretation; the boundary between blocks pays the X100 materialization
cost on purpose. The intra-block win is what SPDD 15 already proved at k=1:
no IR layer, no per-op materialization, HotSpot does register allocation and
inlining, Vector API does SIMD lowering.

The unknown is **how large k can grow before the strategy stops working**.
Two failure modes are visible from theory:

- **HotSpot inlining ceiling.** A k=50 fused method calls `DoubleVector.mul`
  50 times inline. C2's inlining budget (`MaxInlineSize`, `FreqInlineSize`,
  `MaxInlineLevel`) and bytecode-size heuristic ("hot method too big") may
  refuse to inline the body, falling back to C1-only or unrolled-but-not-
  inlined C2. The fused method then loses its register-resident advantage.
- **Janino source/bytecode size.** Spark's whole-stage codegen has a known
  `HUGE_METHOD_LIMIT` constant for the same reason. Java's hard ceiling is
  65535 bytes of bytecode per method (JVMS §4.7.3). Long before that, JIT
  heuristics degrade. The empirical breakpoint matters more than the hard
  limit.

This probe finds the breakpoint by sweeping k across `{5, 20, 50}` and
comparing four execution strategies on `x * x * ... * x` (k-fold multiply of
one Float64 vector against itself, left-associative):

- **A1 — naive JVM chain, reused output (ping-pong)** — k calls to
  `Compute.mul(...)`, ping-ponging between two pre-allocated output buffers.
  Models the X100-style "vector-at-a-time with per-task scratch reuse"
  baseline.
- **A2 — naive JVM chain, per-call alloc** — k calls to `Compute.mul(...)`,
  each producing a freshly-allocated output. Mirrors arrow-rs's API shape on
  the JVM side.
- **B — Janino-fused codegen** — single compiled method that performs the
  k-fold multiply in registers, written by a parameterised codegen class.
- **C — AOT-fused handwritten** — three handwritten Java raw kernels
  (`MulFloat64Chain5Raw`, `MulFloat64Chain20Raw`, `MulFloat64Chain50Raw`) that
  perform the same k-fold multiply with the SIMD body unrolled by hand. This
  is the *upper-bound anchor* — without it, a Janino-vs-naive gap cannot be
  attributed to fusion specifically, only to "the Janino path".
- **D — arrow-rs chain** — chained `arrow_arith::numeric::mul` in the
  `arrow-rs-baseline/` Cargo subproject, k times, with null-free inputs.
  Models the X100-style interpreted execution from a non-JVM, AOT-compiled
  vectorized interpreter.

The comparison answers four questions:

1. **(Reuse-within-stage)** A1 vs A2 at constant k — how much of the
   X100-style win comes from output reuse versus from interpretation?
2. **(Fusion-vs-interpretation)** B vs A1, C vs A1 at constant k — does
   fusion actually beat the naive reused-output chain? C is the upper bound,
   B is the Janino realization of it.
3. **(Codegen scaling — the block-size question)** B vs C as k grows from 5
   to 50 — does Janino+HotSpot keep up with the handwritten anchor as the
   fused method grows? The k at which B falls measurably below C is the
   project's empirical block-size cap.
4. **(Cross-runtime structural claim)** B vs D — does JVM-fused beat an
   AOT-compiled vectorized interpreter without a fusion layer? This is the
   strategic positioning probe against the X100/MonetDB paradigm.

This probe consumes SPDD 15's feasibility result; produces empirical data
that a future SPDD will turn into an optimizer heuristic; and ships no
optimizer of its own. Compile latency is explicitly out of scope per the
project's steady-state target — see the Non-goals section for the citation.

This requirement supersedes nothing. It amends `CORE_DESIGN.md §Expression
fusion` to record the block-fusion stance and amends `BENCHMARKS.md
§Fusion benchmarks` and `§Phase 5: Fusion` to make Phase 5's design
concrete (it was provisional pending SPDD 15's outcome).

## Scope

1. **Three AOT-fused handwritten raw kernels** in
   `io.github.semyonsinchenko.arrowcompute.compute.raw`:

   - `MulFloat64Chain5Raw`
   - `MulFloat64Chain20Raw`
   - `MulFloat64Chain50Raw`

   Each follows the `MulFloat64Raw` shape:
   ```text
   public final class MulFloat64Chain<K>Raw {
       public static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
       public static final ValueLayout.OfDouble FLOAT64_LE = ...;
       public static final ByteOrder BYTE_ORDER = ...;
       public static void computeAll(MemorySegment x, MemorySegment out, int n) { ... }
   }
   ```

   Body: SIMD loop with `K` left-associative inline multiplies per lane-load,
   followed by scalar tail with `K` left-associative multiplies per element.
   No loop nest over k — the multiplies are unrolled. Single input `x`,
   single output `out`. Output equals `x^K` per row.

2. **Parameterised Janino codegen class** in
   `io.github.semyonsinchenko.arrowcompute.compute.codegen`:

   - `MulFloat64ChainCodeGen` (sibling of `MulFloat64CodeGen`).
   - `loadComputeAllHandle(int k)` — returns a `MethodHandle` for a freshly
     compiled `computeAll(MemorySegment x, MemorySegment out, int n)` that
     implements the k-fold left-associative multiply.
   - Each call to `loadComputeAllHandle(k)` produces a separate
     `SimpleCompiler` invocation and a separate dynamic class name (e.g.
     `MulFloat64Chain<K>Dynamic`). No cross-k reuse of bytecode.
   - Source string is built by string concatenation: K inline statements per
     SIMD body and K inline statements per scalar tail. Generator function
     is pure and deterministic so the parity test sees stable output.

3. **JMH benchmark** in
   `src/jmh/java/io/github/semyonsinchenko/arrowcompute/bench/`:

   - `MulFloat64ChainBenchmark` (single file, four cells).
   - `@Param int k ∈ {5, 20, 50}`.
   - `@Param int rows ∈ {1024, 16384, 65536, 1048576}` (matches existing
     `MulFloat64DispatchBenchmark` grid).
   - `@Param int nullPercent ∈ {0}` (single value; see Non-goals).
   - `@Setup(Level.Trial)`: builds input `Float8Vector x`; pre-allocates
     `Float8Vector out1`, `Float8Vector out2` (for ping-pong); pre-allocates
     `Float8Vector reusedOut` (for B and C); compiles
     `MulFloat64ChainCodeGen` for the current `k` and stores the
     `MethodHandle`; selects the AOT-fused kernel class for the current `k`
     via a small switch (`switch (k) { case 5 -> MulFloat64Chain5Raw.class …`).
   - Cells:
     - `naiveChainReusedPingPong` — Cell A1. Calls
       `Compute.mul(currentIn, x, currentOut)` k times, ping-ponging
       `out1`/`out2`. Consumes the final buffer (parity of k decides which).
     - `naiveChainPerCallAlloc` — Cell A2. Each of the k `Compute.mul(...)`
       calls allocates a fresh `Float8Vector` inside the measured method;
       all intermediates and the final output are closed at end of the
       method.
     - `fusedJaninoReusedOutput` — Cell B. One `MethodHandle.invokeExact(...)`
       into the Janino-compiled `computeAll(x, reusedOut, n)`. Reused output.
     - `fusedAotReusedOutput` — Cell C. One direct static call into
       `MulFloat64Chain<K>Raw.computeAll(x, reusedOut, n)`. Reused output.
   - Each cell consumes the relevant output `Float8Vector` via
     `Blackhole.consume(out)` exactly once at the end. Dead-code-elimination
     prevention is identical across cells so cell-to-cell deltas are honest.
   - Implements `BenchmarkMetadataProvider`: `layer() = "fusion"`,
     `question() = "How does fused codegen scale with block size?"`,
     `benchmarkId() = "mul-float64-chain"`, plus the existing required
     metadata.

4. **arrow-rs reference** in `arrow-rs-baseline/`:

   - One new bench file (location and naming per the conventions established
     in SPDD 11) that exposes `mul_float64_chain_k5`, `_k20`, `_k50` cases.
   - For each `k`: chain `let mut result = x.clone(); for _ in 0..k { result
     = arrow_arith::numeric::mul(&result, &x)?; }`. Black-box-consume the
     final array.
   - Inputs constructed without a validity buffer so arrow-rs allocates only
     data (matches the JVM cells' `nullPercent = 0`).
   - Same `rows` grid as JVM cells. Same single-threaded, steady-state,
     warmed-up Criterion run as existing arrow-rs cells.

5. **Parity test** at
   `src/test/java/io/github/semyonsinchenko/arrowcompute/compute/codegen/MulFloat64ChainCodeGenTest.java`:

   - For each `k ∈ {5, 20, 50}`: compile the Janino kernel via
     `MulFloat64ChainCodeGen.loadComputeAllHandle(k)`, take the matching
     `MulFloat64Chain<K>Raw.computeAll`, and run both against the same
     fixed-input `MemorySegment` (size = SPECIES.length() + 3 to exercise
     the scalar tail). Input fixture includes the same special values as
     SPDD 15's parity test (NaN, ±Inf, -0.0, very-small, very-large, normal
     values).
   - Assert byte-equality between AOT and Janino outputs. This proves
     left-associative order is preserved by both implementations and that
     neither has tree-shaped the multiply chain.
   - Additionally, for `k = 5` only: run the naive chain (`MulFloat64Raw`
     ping-pong) on the same fixture and assert byte-equality with the
     AOT-fused output. This ties the naive-chain interpretation of the
     workload to the fused interpretation at one chain depth so the
     benchmark cells A1/A2/B/C are demonstrably solving the same problem.

6. **Inlining diagnostics in the implementation iteration's PR**:

   - The implementation iteration runs the benchmark once with
     `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
     -XX:+PrintCompilation` (or the equivalent JFR / `jcmd Compiler.print`
     workflow) and attaches a summary to the PR description.
   - The summary names, for each k:
     - whether the fused method was fully C2-compiled,
     - whether all `DoubleVector.mul` callsites were inlined,
     - whether any "hot method too big" / "callee too large" message was
       emitted against the fused method,
     - the approximate bytecode size of the fused method.
   - This is captured in the SPDD acceptance criteria as a *reporting*
     deliverable, not a *performance* deliverable. The numbers report
     themselves; the inlining trace explains them.

7. **Reporting**:

   - The implementation iteration's PR description names, in a single
     summary block:
     - throughput for each cell at each (k, rows) point,
     - the `k` at which Janino-fused (B) throughput falls below
       AOT-fused-handwritten (C) by more than the project's notebook noise
       envelope (±15%) at the largest row size,
     - the `k` at which Janino-fused (B) stops beating the naive reused-
       output chain (A1) at the largest row size — the block-size *floor*
       below which fusion is not worth the codegen overhead at all,
     - whether (B) beats arrow-rs chain (D) at any k.
   - The two break-even points (B-vs-A1 floor and B-vs-C ceiling) are the
     two numbers a future fusion-optimizer SPDD will consume as block-size
     bounds.

## Non-goals

- **Compile latency as a measured axis.** The project's benchmark policy is
  steady-state on a warmed-up JVM (`BENCHMARKS.md §1`: "single-threaded
  steady-state warmed-up JVM preloaded Arrow data preallocated outputs";
  "Cold-start behavior may be measured separately, but it is not the main
  optimization target"). Janino compile cost is acknowledged as a one-time
  setup cost amortised over the run. An interactive-query latency model
  where compile cost is in the user-visible critical path is not the target
  use case for the codegen probe. Worth re-evaluating if the project's
  target use case ever expands to interactive sub-second analytics.

- **Mixed-operator fused chains.** `a * b + c * d`-style fusion (multiple
  ops, multiple inputs) is the natural next step but is out of scope here.
  This probe holds shape constant (single input, k multiplies) so the
  k-dimension is the only thing changing across measurements. A follow-up
  SPDD covers mixed-operator fusion.

- **Multiple distinct input vectors.** The chain is `x * x * ... * x` with
  one input vector. `a * b * c * ... * z` with k distinct inputs would
  exercise k-way input-buffer load pressure rather than k-way register
  multiplies, and is a different question. Same SIMD body, different memory
  access pattern.

- **`nullPercent > 0`.** Null-handling in fused chains (fused validity
  propagation, validity bitmap traffic across the k multiplies) is a
  follow-up SPDD. Holding `nullPercent = 0` keeps this probe focused on
  the compute-loop side of fusion.

- **Generalisation of `MulFloat64ChainCodeGen` to other operators.** The
  class is single-op (`*` only). A future codegen class for `+`, `-`, `>`,
  etc. is the next SPDD's job.

- **Building the fusion optimizer or its heuristic.** This SPDD *finds* the
  block-size bounds; it does not build a planner, an expression tree, an
  IR, or any abstraction layer that would be load-bearing for an optimizer.

- **Compile-cost amortisation across queries.** Caching of compiled fused
  blocks across queries is interesting but orthogonal — it is a *cache
  design* question, not a *block size* question.

- **Re-running SPDD 15.** SPDD 15's single-kernel parity result is taken
  as established. This probe does not re-litigate Janino vs AOT at k=1.

## Constraints

- **Left-associativity is mandatory in all four cells.** The AOT-fused
  kernels emit `(((x*x)*x)*x)…` literally in source. The Janino codegen
  emits the same. The naive chain produces it by construction. The parity
  test relies on byte-equality of the output `MemorySegment`s, which only
  holds if associativity matches; this is non-negotiable. Tree-shaping for
  parallelism is explicitly forbidden by this constraint.

- **Single-input shape.** All four cells take `x` as the only Float64 input
  vector. There is no second input.

- **`nullPercent = 0` everywhere, including arrow-rs.** arrow-rs must run
  with null-free inputs so it does not allocate validity buffers per
  `numeric::mul` call. This is the fair comparison; with nulls, the
  asymmetry would be about validity management, not fusion.

- **Naive chain uses `Compute.mul(...)`, not `MulFloat64.eval(...)`.** The
  dispatch facade is the public API surface; SPDD 15's dispatchSmoke cell
  established it as a zero-cost abstraction within noise. Going through
  the public API path makes A1 and A2 reflect how a user actually writes
  the chain.

- **Per-k compile granularity.** `MulFloat64ChainCodeGen.loadComputeAllHandle(k)`
  produces a fresh dynamic class per `k` invocation. No shared bytecode
  across k. This is what answers the "Spark hardcoded codegen limit"
  analogue — the breakpoint is per-method, so each k is its own method.

- **Ping-pong buffer correctness.** Cell A1 allocates `out1` and `out2`
  once in `@Setup`. The chain `Compute.mul(currentIn, x, currentOut)` reads
  from one buffer and writes to the other; the first iteration reads `x`
  and writes `out1`, subsequent iterations alternate. The final result is
  in `out1` if `k` is odd and in `out2` if `k` is even (or vice versa
  depending on bootstrap convention — fix one convention and write it
  down). Consume the correct final buffer.

- **JVM args unchanged.** All JMH cells inherit `sharedJvmArgs` from the
  project's existing build configuration. No probe-specific
  `-XX:MaxInlineSize=...` tuning — the question is what HotSpot does at
  default settings, which is the deployment-realistic configuration. If
  inlining fails at k=50 under defaults, that is the finding to report,
  not something to tune around.

- **No third-party dependencies beyond what SPDD 15 already added.** Janino
  is already on the `jmh`/`testRuntimeOnly` classpaths from SPDD 15. No
  new build-file deps.

## Acceptance criteria

- `spdd_requirements/requirements/16-fusion-block-size-probe.md` exists
  with the structure above.

- `CORE_DESIGN.md §Expression fusion` is amended with one paragraph that:
  - Records the project's *block-fusion* design stance (not whole-pipeline
    fusion in the Umbra/CedarDB sense).
  - States that the block size is determined empirically by SPDD 16, not
    decided up front.
  - Names SPDD 16 by number for traceability.

- `BENCHMARKS.md §Fusion benchmarks` (the one-question subsection near the
  top of the doc) is amended with a forward-reference to SPDD 16's chain-
  depth dimension and to the block-size question.

- `BENCHMARKS.md §Phase 5: Fusion` is amended: the "provisional pending
  SPDD 15" language is replaced with a concrete reference to SPDD 16 as
  the first fusion benchmark, naming the four cells (A1/A2/B/C) and the
  arrow-rs chain reference.

- No source-code Java changes, Rust changes, build-file changes, or
  benchmark code changes are part of this SPDD's acceptance. The classes,
  benchmark, Rust bench, and tests named in Scope ship under a downstream
  implementation iteration.

## Cross-references

- **Supersedes**: nothing.
- **References**:
  - SPDD 11 (`11-bench-cleanup-and-cargo-reference.md`) — the
    `arrow-rs-baseline/` subproject's location and conventions.
  - SPDD 13 (`13-arrow-rs-peer-positioning.md`) — caller-owns-buffer
    contract used by cells A1, B, C.
  - SPDD 14 (`14-output-allocation-scenarios.md`) — the per-call vs
    reused-output two-cell pattern that A1/A2 generalise across a chain.
  - SPDD 15 (`15-janino-runtime-codegen-feasibility-probe.md`) —
    prerequisite. Janino's ability to compile VectorAPI code at k=1 is
    consumed here; this SPDD does not re-litigate it.
- **Amends**:
  - `CORE_DESIGN.md` — §Expression fusion (one paragraph naming the
    block-fusion stance and pointing at SPDD 16).
  - `BENCHMARKS.md` — §Fusion benchmarks (one-paragraph addition on
    chain-depth dimension) and §Phase 5: Fusion (concrete replacement for
    the "provisional" language).
- **Does not amend**: `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`,
  `spdd_requirements/README.md`. The probe is orthogonal to hot-path
  coding rules, Arrow-Java API policy, and the SPDD workflow itself.
- **Project framing**: this is the first SPDD to make the project's
  *fusion strategy* concrete — block fusion at empirically-determined
  k, with vectorized hand-offs between blocks. The two break-even points
  (B-vs-A1 floor and B-vs-C ceiling) reported by this probe are direct
  inputs to a future fusion-optimizer SPDD's block-sizing heuristic.
