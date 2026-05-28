# SPDD Analysis: Janino Runtime-Codegen Feasibility Probe for MulFloat64

## Original Business Requirement
# Requirement: Janino runtime-codegen feasibility probe for MulFloat64

## Business requirement

The project's empirical work through SPDD 01–14 measures the kernel-layer
"JVM tax" against AOT C++/Rust baselines. The emerging picture from those
benchmarks is that hand-written JVM kernels using `jdk.incubator.vector` plus
FFM `MemorySegment` sit in the same throughput class as arrow-rs and arrow-cpp
on tight numeric loops. That closes the *static* JVM-tax question for this
project's scope. The next question is structural: **is there a class of work
where the JVM has a structural advantage over AOT systems precisely because it
is a JVM?**

The most plausible candidate is operator fusion via runtime code generation:

- Vectorized interpreters (DuckDB, DataFusion, Velox) are not codegen engines.
  They dispatch vectorized primitives at runtime and materialize an Arrow
  buffer per primitive. An `a * b + c > t` expression chain costs the price of
  three full passes over memory plus three intermediate buffers.
- LLVM-IR codegen engines (Umbra, CedarDB) avoid that materialization, but pay
  a heavy structural price: a query-time IR layer, an LLVM build dependency,
  manual SIMD lowering inside the IR templates, slow compile, and a large
  engineering surface that has to be re-implemented per operator and per
  hardware backend.
- The JVM offers a structurally simpler third path. A query plan can emit a
  fused Java method, compile it in-process with Janino, and let HotSpot's C2
  do register allocation, instruction scheduling, and inlining; Vector API
  takes care of SIMD lowering. No IR layer. No LLVM dependency. No manual
  SIMD codegen. The fused method is a normal Java class loaded into a child
  classloader.

That third path only works if Janino accepts source that imports
`jdk.incubator.vector` and produces bytecode whose imports resolve at runtime
under the project's standard JVM args (`--add-modules jdk.incubator.vector`).
Janino's published syntax level is around Java 11/14 with partial support for
newer features; the project compiles against Java 25 with the incubator
module enabled. Whether `import jdk.incubator.vector.DoubleVector;` survives
Janino's parser, and whether the loaded class resolves `DoubleVector` at link
time, is an open and load-bearing question.

This requirement scopes a **feasibility probe**, not a fusion implementation.
The probe takes `MulFloat64Raw`'s source verbatim, holds it as a `String`
constant in a new `MulFloat64CodeGen` class, compiles it at JMH trial setup,
runs the compiled `computeAll` through a JMH cell that mirrors
`MulFloat64DispatchBenchmark`'s reused-output shape, and reports a single
binary result: *Janino can / cannot produce a VectorAPI-backed kernel that
matches the AOT version in steady-state throughput*. `MulFloat64Raw` is
chosen because it is the smallest VectorAPI kernel in the repo, already has
an AOT benchmark for direct comparison, and has no validity-bitmap
complexity to muddy the result.

The probe's outcome gates the design of the eventual fusion SPDD: a positive
result unlocks a Janino-based fusion approach; a negative result redirects
that effort toward `javax.tools.JavaCompiler` (the in-process JDK compiler) or
ByteBuddy/ASM bytecode emission, evaluated under separate downstream SPDDs.

This requirement supersedes nothing. It amends `CORE_DESIGN.md §Expression
fusion` and `BENCHMARKS.md §Phase 5: Fusion` only to record the existence of
the probe and the dependency direction. It does not modify any kernel,
wrapper, dispatch, or existing benchmark.

## Scope

1. **`MulFloat64CodeGen` class in a `codegen` subpackage of `compute`.**
   Package placement is `io.github.semyonsinchenko.arrowcompute.compute.codegen`,
   sibling of `compute.raw` and `compute.wrapper`. The class holds the Java
   source of `MulFloat64Raw` (including its `SPECIES`, `FLOAT64_LE`, and
   `BYTE_ORDER` constants and the `computeAll` method body) as a `public
   static final String` constant. The class name in the source string is
   distinct from `MulFloat64Raw` to keep the dynamically-loaded class
   independent of the AOT class on the classpath.

2. **Loader entry point.** A single helper (a method on `MulFloat64CodeGen`
   or a small `JaninoLoader` companion) that:
   - Instantiates `org.codehaus.janino.SimpleCompiler`.
   - Calls `cook(String)` with the source string.
   - Resolves the class from the compiler's `ClassLoader`.
   - Returns a `MethodHandle` (preferred for the hot path) bound to
     `computeAll(MemorySegment, MemorySegment, MemorySegment, int)`.
   The loader API is intentionally narrow — one method, one return type.
   It is not a general codegen framework.

3. **`MulFloat64CodeGenBenchmark`** under
   `src/jmh/java/io/github/semyonsinchenko/arrowcompute/bench/`. Shape:
   - Same `@Param` grid as `MulFloat64DispatchBenchmark` (`rows ∈ {1024,
     16384, 65536, 1048576}`, `nullPercent ∈ {0, 30}`).
   - Janino compile and classload happen in `@Setup(Level.Trial)` and are
     **not** measured.
   - One `@Benchmark` cell `wrapperEvalReusedOutput` that invokes the
     compiled kernel via the `MethodHandle`, using a reused output
     `Float8Vector` allocated in setup (matches SPDD 14's reused-output
     convention).
   - Implements `BenchmarkMetadataProvider` with `layer() = "codegen"`,
     `question() = "Does Janino accept VectorAPI imports?"`, and
     `benchmarkId() = "mul-float64-codegen"`.

4. **Parity test** at
   `src/test/java/io/github/semyonsinchenko/arrowcompute/compute/codegen/MulFloat64CodeGenTest.java`.
   The test compiles the source string via the same loader the benchmark
   uses, then runs both the Janino-compiled `computeAll` and the
   classpath `MulFloat64Raw.computeAll` against the same fixed inputs
   (including NaN, +Inf, -Inf, -0.0 special values, and a length that
   exercises the SPECIES tail), and asserts byte-equality of the output
   `MemorySegment`. This is the regression safety net against the source
   string drifting from `MulFloat64Raw.java`.

5. **Build dependency.** `org.codehaus.janino:janino` is added to
   `build.gradle.kts`. Default configuration scope is
   `testRuntimeOnly` + the JMH plugin's `jmh` configuration, so Janino does
   not leak into the `implementation` classpath shipped to library users.
   If the implementation iteration finds that the loader API must be
   reachable from a `main` source set (for example, to support a future
   non-probe codegen surface), the escalation to `implementation` is
   documented in that iteration's PR description and is not blocked by
   this SPDD.

6. **JVM-args propagation.** The benchmark and test inherit the existing
   `sharedJvmArgs` (`--add-modules jdk.incubator.vector`,
   `--enable-native-access=ALL-UNNAMED`,
   `--add-opens=java.base/java.nio=ALL-UNNAMED`). The implementation
   iteration verifies and documents that the Janino-spawned classloader's
   parent has `jdk.incubator.vector` readable — i.e. that the unnamed
   module of the child loader inherits the `--add-modules` directive.

7. **Reporting.** The probe ships two artefacts:
   - A binary outcome line in the implementation iteration's PR
     description: *Janino accepts `jdk.incubator.vector` imports: yes / no*.
   - One throughput row at `rows = 1048576`, `nullPercent = 0`, reused
     output, captured against the AOT `MulFloat64Raw.computeAll` cell in
     the same JMH run. Success threshold: within ±10% of the AOT cell.
     A larger gap is not a failure of the probe — it is a finding to
     report, since it would mean HotSpot does not optimize the
     dynamically-loaded class as aggressively as the statically-compiled
     one.

## Non-goals

- Building a fusion engine, an expression tree, an SSA layer, or a
  template engine. The source string is a verbatim copy of
  `MulFloat64Raw`'s body. There is no generated structure.
- Generalising to other kernels. Only MulFloat64 is in scope. AddInt32,
  CompareInt32Greater, StartsWithUtf8, etc. are explicitly out of scope.
- Committing to Janino as the long-term codegen tool. If Janino fails on
  the incubator import or produces unloadable bytecode, the next probe
  SPDD evaluates `javax.tools.JavaCompiler` and a separate SPDD evaluates
  ByteBuddy/ASM bytecode emission. This SPDD's outcome is the input to
  those decisions, not a replacement for them.
- Modifying `MulFloat64Raw.java`, `MulFloat64.java`, `Compute.java`,
  `MulFloat64DispatchBenchmark`, or any existing kernel/wrapper/dispatch
  class. The probe lives alongside, not on top of, the AOT path.
- Treating compile latency as a measured quantity. Compile cost is
  acknowledged (it is a query-time cost in a real fusion engine) but the
  probe measures steady-state throughput only. A separate SPDD can
  characterise compile latency once feasibility is established.
- Reporting against arrow-rs / arrow-cpp / DataFusion / DuckDB. The
  probe's reference baseline is the project's own AOT `MulFloat64Raw`.
  Comparing dynamically-generated JVM code to an AOT native baseline
  before HotSpot-vs-Janino is settled would confuse the result.
- Output-allocation matrix. The probe ships the reused-output cell only.
  SPDD 14's per-call cell is a wrapper-level concern; reproducing it for
  the codegen probe adds noise without answering the binary question.
- Mixing the probe into the public `Compute.*` dispatch. The codegen
  loader is not wired into the dispatch facade.

## Constraints

- **String fidelity.** The source string in `MulFloat64CodeGen` is the
  verbatim body of `MulFloat64Raw` — `SPECIES`, `FLOAT64_LE`,
  `BYTE_ORDER`, and the `computeAll` method. The parity test (Scope §4)
  exists to fail the build the moment the two diverge. If
  `MulFloat64Raw` changes in a downstream iteration, the source string
  must be updated in the same change set; the parity test is the
  enforcement mechanism, not a tracker for manual sync.
- **Probe-scoped dependency.** `janino` is added to
  `testRuntimeOnly` + the `jmh` configuration. It does not appear on the
  `implementation` configuration unless the implementation iteration
  documents why.
- **Standard JVM args.** The probe runs under the existing `sharedJvmArgs`.
  Probe-specific JVM flags would invalidate the result — the question
  being probed is whether Janino works under the project's actual run
  configuration.
- **In-process compile.** The benchmark JVM is the same JVM that compiles
  the string and runs the resulting class. Out-of-process compilation is
  a different trust and packaging model and is out of scope.
- **One benchmark cell, not a matrix.** The probe is a feasibility check.
  Full characterization remains the AOT `MulFloat64DispatchBenchmark`'s
  job; SPDD 14's two-cell convention is not duplicated here because the
  per-call-alloc cell adds no information about Janino feasibility.
- **No fusion-engine creep.** The probe does not introduce an expression
  tree, a code-template DSL, a kernel registry, or any abstraction layer
  that would be load-bearing for a future fusion design. The next SPDD
  that proposes such a layer does so with its own scope and review.

## Acceptance criteria

- `spdd_requirements/requirements/15-janino-runtime-codegen-feasibility-probe.md`
  exists with the structure above.
- `CORE_DESIGN.md §Expression fusion` is amended with one paragraph that
  names Janino-based runtime codegen as a candidate fusion implementation
  path, lists the three sub-options (Janino + Java source,
  `javax.tools.JavaCompiler` + Java source, ByteBuddy/ASM bytecode), and
  references SPDD 15 as the probe that decides between them. The
  paragraph names SPDD 15 by number so traceability survives later edits.
- `BENCHMARKS.md §Phase 5: Fusion` is amended with one forward-reference
  paragraph: SPDD 15 is listed as a prerequisite feasibility probe whose
  outcome determines the fusion-codegen path. Until SPDD 15 ships its
  binary result, the Phase 5 design is undetermined.
- No source-code Java changes, build-file changes, or benchmark code
  changes are part of this SPDD's acceptance. The classes, benchmark,
  test, and dependency named in `Scope` ship under a downstream
  implementation iteration.

## Cross-references

- **Supersedes**: nothing.
- **References**:
  - SPDD 13 (`13-arrow-rs-peer-positioning.md`) — the caller-owns-buffer
    contract that the codegen benchmark's reused-output cell relies on.
  - SPDD 14 (`14-output-allocation-scenarios.md`) — the
    `wrapperEvalReusedOutput` naming convention that the codegen
    benchmark adopts.
- **Amends**:
  - `CORE_DESIGN.md` — §Expression fusion (one paragraph naming the
    three runtime-codegen sub-options and pointing at SPDD 15).
  - `BENCHMARKS.md` — §Phase 5: Fusion (one paragraph naming SPDD 15 as
    a prerequisite probe).
- **Does not amend**: `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`,
  `spdd_requirements/README.md`. The probe is orthogonal to hot-path
  coding rules, Arrow-Java API policy, and the SPDD workflow itself.
- **Project framing**: this is the first SPDD aimed at the *structural*
  JVM-tax question rather than the static-kernel one. A positive probe
  result unlocks a fusion design SPDD; a negative result redirects
  fusion-codegen exploration to JavaCompiler API or bytecode emission
  under separate downstream SPDDs.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Raw SIMD kernel baseline (`MulFloat64Raw`): canonical no-null float64 multiply kernel used as the AOT throughput and correctness baseline for the probe.
- Wrapper execution contract (`MulFloat64`): wrapper-level null propagation and caller-owned output lifecycle that establishes the reused-output benchmark shape the probe must mirror.
- Dispatch benchmark pattern (`MulFloat64DispatchBenchmark` + `BenchmarkMetadataProvider`): existing JMH parameter grid, reused-output lane naming, and metadata conventions that the probe benchmark should align with.
- Fusion roadmap placeholders (`CORE_DESIGN.md`, `BENCHMARKS.md`): existing strategic framing already treats runtime codegen path selection as gated by feasibility evidence.
- Caller-owned buffer governance (SPDD 13/14 artifacts): benchmark semantics and reporting language already distinguish API-shape fairness versus reused-output contract realism.

#### New Concepts Required
- Runtime-compiled kernel artifact (`MulFloat64CodeGen`): a probe-scoped representation of a fused-kernel candidate compiled in-process from Java source.
- Codegen loader boundary (Janino loader entry point): narrow one-method contract that converts source text into an invokable compute handle without creating a general framework.
- Codegen benchmark lane (`MulFloat64CodeGenBenchmark`): a dedicated "codegen" benchmark layer for binary feasibility signaling and AOT-relative throughput comparison.
- Fidelity guardrail (parity test between dynamic and AOT kernels): explicit drift-detection mechanism ensuring source-string fidelity to `MulFloat64Raw` over time.

#### Key Business Rules
- Feasibility-first rule: this SPDD answers "Janino viable or not" for Vector API imports before any fusion engine design commitment.
- Isolation rule: compile/classload overhead is intentionally excluded from measured throughput; only steady-state execution equivalence is evaluated.
- Contract-continuity rule: probe benchmark must follow existing reused-output and shared JVM-args conventions so results are comparable and trustworthy.
- Scope containment rule: probe must not alter existing raw/wrapper/dispatch code paths or public `Compute.*` behavior.
- Decision-gating rule: probe outcome directly determines whether next fusion SPDD explores Janino path or switches to JavaCompiler/ByteBuddy-ASM alternatives.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Introduce a parallel, probe-only codegen slice alongside current AOT kernels: source-string holder + narrow loader + dedicated benchmark + parity test, while preserving all existing compute pathways untouched.
- Reuse existing benchmark and documentation conventions (rows/null grid, reused-output policy, metadata schema, shared JVM flags) to make the result decision-grade within current project governance.
- Treat outcome as architecture input, not feature delivery: this phase validates viability of one runtime-codegen sub-path rather than implementing fusion itself.

#### Key Design Decisions
- Janino probe vs immediate multi-compiler bakeoff: single-tool probe gives fastest structural signal with minimal new surface; recommend Janino-first as scoped by requirement, defer alternatives to downstream SPDDs.
- Probe-only codegen package vs integrating into `Compute.*`: integration now would blur non-goal boundaries and risk accidental production coupling; recommend isolated `compute.codegen` placement.
- `MethodHandle` invocation boundary vs broader abstraction: narrow handle return keeps hot-path-friendly invocation and avoids premature framework design; recommend one-method loader API.
- Reused-output only benchmark lane vs matrix expansion: single lane directly answers feasibility question with less noise; recommend staying with reused-output-only for SPDD 15 and preserving full matrix in existing AOT benchmarks.
- Probe dependency scope (`testRuntimeOnly`/`jmh`) vs `implementation`: narrow scope minimizes shipped surface and consumer impact; recommend escalation only if future non-probe API requires it.

#### Alternatives Considered
- `javax.tools.JavaCompiler` as initial probe path: rejected for this SPDD because requirement explicitly positions Janino as the first structural simplification candidate to validate.
- ByteBuddy/ASM bytecode-first path: rejected for now due to higher engineering complexity and weaker readability/fidelity for a first feasibility check.
- Building a generic fusion/code-template subsystem now: rejected as scope creep; it would pre-commit architecture before proving basic compiler/module compatibility.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Throughput success interpretation: requirement states ±10% as threshold but also says larger gap is still a valid finding; clarification needed on whether "feasible" means import/link success only or includes performance target attainment.
- Drift governance boundary: parity test enforces raw-kernel/source-string synchronization, but ownership of updates when `MulFloat64Raw` changes is process-defined rather than mechanically coupled.
- Loader placement choice: requirement allows method on `MulFloat64CodeGen` or companion loader class; this leaves maintainability/style decision open for downstream implementation.

#### Edge Cases
- Classloader/module readability edge: Janino-compiled class may compile but fail link-time resolution of Vector API types if module readability inheritance behaves differently than expected.
- Numeric special-value parity edge: NaN payloads, signed zero, and Infinity behavior must match byte-level output parity across vector loop and scalar tail paths.
- Tail-length parity edge: non-multiple-of-species inputs can pass main-lane parity but diverge in scalar tail if string source drifts subtly from AOT kernel.
- Benchmark-shape edge: reused-output correctness depends on wrapper contract semantics remaining stable under null-profile transitions across trial params.

#### Technical Risks
- Janino language/runtime compatibility risk: Janino parser or bytecode generation may not fully support source shape/import semantics needed for Java 25 + incubator usage -> mitigation direction: keep probe binary and fail-fast with explicit yes/no outcome.
- JIT optimization asymmetry risk: dynamically loaded class may receive weaker inlining/profile optimization than AOT class -> mitigation direction: report throughput delta explicitly and treat it as architectural evidence, not probe failure.
- Dependency-surface risk: moving Janino beyond benchmark/test scopes could unintentionally expand shipped dependency footprint -> mitigation direction: keep strict probe-scoped dependency policy unless future SPDD intentionally changes it.
- Benchmark comparability risk: if codegen benchmark diverges from existing metadata/dimension conventions, result may be hard to compare against AOT baseline -> mitigation direction: inherit existing `BenchmarkMetadataProvider` and param conventions.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | Requirement file exists at `spdd_requirements/requirements/15-janino-runtime-codegen-feasibility-probe.md` with defined structure | Yes | File exists and contains full sections (`Business requirement`, `Scope`, `Non-goals`, `Constraints`, `Acceptance criteria`, `Cross-references`). |
| 2 | `CORE_DESIGN.md §Expression fusion` amended with SPDD-15-referenced Janino/JavaCompiler/ByteBuddy-ASM paragraph | Yes | Present at `CORE_DESIGN.md` around expression-fusion section with explicit SPDD 15 reference and three sub-options. |
| 3 | `BENCHMARKS.md §Phase 5: Fusion` amended with SPDD 15 prerequisite forward reference | Yes | Present at `BENCHMARKS.md` Phase 5 section, explicitly gating design on SPDD 15 outcome. |
| 4 | No Java/build/benchmark implementation changes are part of this SPDD acceptance | Yes | Current codebase has no `compute.codegen` classes or Janino dependency yet; implementation remains intentionally deferred to downstream iteration. |
