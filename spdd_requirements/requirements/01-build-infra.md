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
