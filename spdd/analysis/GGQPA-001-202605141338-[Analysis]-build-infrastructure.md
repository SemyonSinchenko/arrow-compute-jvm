# SPDD Analysis: Build Infrastructure Foundation

## Original Business Requirement
# Requirement: Build Infrastructure

## Business requirement

Create initial Java project infrastructure so agents can safely add kernels, tests, and benchmarks in small SPDD iterations.

## Project identifiers

- **Project name** (Gradle root project): `arrow-compute`
- **Root package**: `io.github.semyonsinchenko.arrowcompute`

## Scope

Set up:

- Gradle (Kotlin DSL preferred);
- Java 25 toolchain;
- Vector API module support;
- FFM API enabled;
- JUnit 5;
- JMH (via `me.champeau.jmh` Gradle plugin);
- package skeleton mirroring `CORE_DESIGN.md §Package layout`;
- basic README commands;
- allocator-debug-mode default for tests.

## Required commands

```bash
./gradlew clean test
./gradlew check
./gradlew jmh
```

## Suggested packages

```text
io.github.semyonsinchenko.arrowcompute.compute
io.github.semyonsinchenko.arrowcompute.compute.dispatch
io.github.semyonsinchenko.arrowcompute.compute.wrapper.safe
io.github.semyonsinchenko.arrowcompute.compute.wrapper.validonly
io.github.semyonsinchenko.arrowcompute.compute.wrapper.agg
io.github.semyonsinchenko.arrowcompute.compute.wrapper.slow
io.github.semyonsinchenko.arrowcompute.compute.raw
io.github.semyonsinchenko.arrowcompute.compute.memory
```

Note: `raw/` is **flat** (no `safe/` or `validonly/` subpackages — null mode is encoded by method name, not package). `wrapper/slow/` is the slow-tier home.

## Required JVM flags

Compile, test, and JMH tasks:

```text
--add-modules jdk.incubator.vector
--enable-native-access=ALL-UNNAMED
```

Test JVM additionally sets:

```text
-Darrow.memory.debug.allocator=true
```

## Dependencies

Use only:

- Arrow Java modules:
  - `org.apache.arrow:arrow-vector`
  - `org.apache.arrow:arrow-memory-core`
  - `org.apache.arrow:arrow-memory-unsafe` (preferred over `netty`; final choice settled at implementation; `arrow-memory-netty` is acceptable if it pulls in fewer surprises in the target deploy environment)
  - `org.apache.arrow:arrow-algorithm`
- JUnit 5 (jupiter-api, jupiter-engine).
- JMH (`me.champeau.jmh` Gradle plugin manages jmh-core and the annotation processor).
- Optionally one assertion library (AssertJ recommended; final choice at impl).

Pin a single Arrow Java version (latest stable at implementation time) across all Arrow modules.

Do not add:

- frameworks (Spring, Guice, Quarkus, …);
- dependency injection;
- logging frameworks;
- runtime reflection libraries (Apache Commons BeanUtils, etc.);
- regex / search libraries beyond `java.util.regex` (re2j / Hyperscan-Java are deferred — `13-slow-tier-scaffold.md` documents the pluggable interface to add them later without changing public APIs).

### Property-based testing

Property-based tests are **desirable** per `AGENTS.md §Testing` and `CORE_DESIGN.md §Testing philosophy`, but the library choice is **TBD**. For MVP, use manual random tests with a fixed seed:

```java
static final long RANDOM_SEED = 0xC0FFEEL;
```

Authorize `net.jqwik:jqwik` or `com.pholser:junit-quickcheck` when a real need emerges; do not block the build infra iteration on this decision.

## Tests

Add a smoke test that:

- creates an Arrow allocator (a `RootAllocator` plus a child allocator),
- creates an `IntVector` with `allocateNew`,
- writes a handful of values,
- calls `vector.validate()` and `vector.validateFull()`,
- closes the vector and the allocators,
- runs with `-Darrow.memory.debug.allocator=true`; the test JVM must fail loudly if there is a leak.

## Non-goals

No compute logic, kernel abstractions, function registry, expression compiler, or JNI.

CI configuration is out of MVP scope. When added later, CI must run `./gradlew check` and (optionally) a JMH smoke at a reduced iteration count.

## Acceptance criteria

- `./gradlew test` passes.
- `./gradlew jmh` task is wired and can run.
- Package skeleton exists and matches the layout above.
- Smoke test creates and closes Arrow resources with allocator debug mode enabled; intentionally leaking a buffer in a follow-up test would fail.
- All required JVM flags are applied to compile / test / jmh tasks.

## Cross-references

- `CORE_DESIGN.md §Package layout`.
- `AGENTS.md §Java version and language style`.
- `ARROW_JAVA_API_USAGE.md §2 Memory management`.

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- Iteration-Based Delivery Plan: requirement execution is staged by numbered iterations in `DEVELOPMENT_PLAN.md` and this item is explicitly iteration 01 — foundational dependency for all later compute work.
- Layered Compute Architecture Contract: the intended package boundaries and layering already exist as design constraints in `CORE_DESIGN.md` (compute, dispatch, wrapper variants, raw, memory) — build infra must preserve these boundaries.
- Arrow Memory Safety Discipline: allocator lifecycle, retain/release semantics, and debug allocator mode are already documented in `ARROW_JAVA_API_USAGE.md` and `AGENTS.md` — tests must enforce this behavior early.
- Two-Tier Kernel Model: fast-tier vs slow-tier split is already codified in architecture docs — initial project scaffolding must not block either tier.

#### New Concepts Required
- Build System Baseline: a concrete Gradle-based build/runtime contract that standardizes compile, test, and benchmark execution for the project.
- Runtime Flag Contract: a project-wide JVM-module/native-access policy that guarantees Vector API and FFM usability across compile/test/benchmark tasks.
- Benchmark Harness Integration: JMH task wiring as a first-class capability, so performance work is available from the first implementation iteration.
- Infrastructure Smoke Validation: a minimal Arrow allocator/vector lifecycle test used as a gate for memory-safety regressions.

#### Key Business Rules
- Architecture Alignment Rule: package skeleton must mirror the documented package layout, especially flat `raw/` and `wrapper/slow/` placement.
- Dependency Guardrail Rule: only approved Arrow/JUnit/JMH dependencies are allowed; frameworks/DI/logging/reflection libraries are intentionally excluded.
- Execution Contract Rule: `test`, `check`, and `jmh` commands must all be runnable via Gradle.
- Safety-First Testing Rule: test runtime must default to allocator debug mode to fail loudly on memory-lifecycle leaks.
- Forward-Compatibility Rule: decisions in this iteration must enable downstream iterations (bridge, raw kernels, wrappers, benchmarks) without restructuring.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Establish a lean JVM library baseline first (build, test, benchmark, package topology), then use that as the stable platform for all later kernel and wrapper iterations defined in `DEVELOPMENT_PLAN.md`.
- Follow documented architecture contracts (`CORE_DESIGN.md`, `AGENTS.md`, `ARROW_JAVA_API_USAGE.md`) as source-of-truth, so infrastructure enforces the same rules the runtime code will later depend on.
- Use a simple flow: developer command invocation -> Gradle task pipeline -> compile/test/benchmark runtime with required JVM flags -> pass/fail feedback as iteration gate.

#### Key Design Decisions
- Build tool and DSL choice: Gradle with Kotlin DSL vs alternatives (Maven/Groovy) -> recommendation: Gradle Kotlin DSL to match requirement and keep strong static configuration ergonomics.
- Arrow memory backend choice: `arrow-memory-unsafe` preferred vs `arrow-memory-netty` fallback -> recommendation: start with `unsafe` to minimize moving parts, keep netty as explicit fallback if environment friction appears.
- Package skeleton timing: create all target packages now vs incremental package creation -> recommendation: create full skeleton now to reduce churn and align contributors around a stable layout.
- Test strictness policy: enforce allocator debug mode by default vs optional opt-in -> recommendation: enforce by default to catch lifecycle defects before any raw/wrapper kernels are introduced.
- Benchmark onboarding timing: wire JMH immediately vs defer until later iterations -> recommendation: wire now because iteration 10 depends on benchmark conventions and early visibility reduces performance blind spots.

#### Alternatives Considered
- Delay JMH until first kernel exists: rejected because it creates later integration risk and weakens iteration-level completeness.
- Introduce framework-based project scaffolding: rejected due to explicit non-goals and hot-path dependency discipline.
- Build only minimal package directories needed for current files: rejected because it increases renaming/refactoring risk across multiple planned iterations.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Arrow memory backend finalization: requirement prefers `arrow-memory-unsafe` but allows `arrow-memory-netty`; final selection criteria for target deployment environment are not fully specified.
- Assertion library choice: AssertJ is recommended but optional; acceptance criteria do not state whether any assertion library must be present.
- README command depth: "basic README commands" is underspecified (whether only command listing is needed or operational guidance too).
- Toolchain exactness: Java 25 is required, but acceptable behavior on machines without Java 25 provisioning is not explicitly defined.

#### Edge Cases
- Environment module-flag drift: compile/test/jmh may pass in one task but fail in another if flags are inconsistently applied.
- Leak-detection false confidence: smoke test could pass while missing a deliberate-negative validation path unless follow-up leak test strategy is clarified.
- Empty-codebase bootstrap: current repository has no build files or source tree yet, so initial setup must avoid hidden assumptions about existing project structure.
- Cross-platform execution differences: native-access and incubator module flags may behave differently across local and CI environments once CI is introduced.

#### Technical Risks
- JVM flag misconfiguration risk: missing incubator/native-access flags can block Vector API or FFM usage later; mitigation direction is centralized task-level JVM configuration.
- Dependency version skew risk: mismatched Arrow module versions can cause runtime incompatibility; mitigation direction is single pinned Arrow version across modules.
- Premature architectural drift risk: if package layout diverges early, later iterations inherit migration overhead; mitigation direction is strict adherence to documented package topology.
- Memory lifecycle regression risk: without enforced allocator debug mode, retain/release errors can remain latent; mitigation direction is default test JVM debug allocator setting.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | `./gradlew test` passes. | Yes | Depends on correct Java 25 toolchain and smoke test stability. |
| 2 | `./gradlew jmh` task is wired and can run. | Yes | Requires plugin wiring and compatible JVM flags in benchmark runtime. |
| 3 | Package skeleton exists and matches the layout above. | Yes | Directly addressable from documented package map in requirement and `CORE_DESIGN.md`. |
| 4 | Smoke test creates and closes Arrow resources with allocator debug mode enabled; intentionally leaking a buffer in a follow-up test would fail. | Partial | "follow-up test" behavior is stated but exact verification mechanism is not fully specified in acceptance text. |
| 5 | All required JVM flags are applied to compile / test / jmh tasks. | Yes | Needs explicit consistency checks across all relevant Gradle tasks. |
