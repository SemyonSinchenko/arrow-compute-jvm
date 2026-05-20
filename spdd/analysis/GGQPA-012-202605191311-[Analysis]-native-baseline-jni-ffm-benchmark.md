# SPDD Analysis: Native Baseline (JNI or FFM downcall)

## Original Business Requirement
# Requirement: Native Baseline (JNI or FFM downcall)

## Business requirement

Measure whether JVM-native kernels beat crossing into native C++ per kernel. This is the headline scenario question — answering it well is core to the project's value proposition.

## Scope

Create a minimal native baseline for one or two primitive operations. The native call may be implemented as classic **JNI** or as an **FFM downcall** (Java 22+); the interpretation rule is the same for both — the overhead is part of the design cost and is **not** subtracted.

Start with `AddInt32` and `AddFloat64`.

## Benchmark comparisons

```text
java_raw_vector
java_wrapper
java_compute_dispatch
native_cpp_per_kernel
```

If JNI and FFM are both wired (unlikely in this iteration), report them as `native_jni_per_kernel` and `native_ffm_per_kernel`.

## Interpretation

This benchmark measures JVM-native execution vs native C++ through the JVM/native boundary. It does **not** measure Java raw loop vs pure C++ raw loop.

Native-boundary overhead (JNI marshalling or FFM downcall stub cost) **must not be subtracted**. It is part of the design cost — the whole project pitch is that the JVM-native path avoids that boundary.

Correct claim: "JVM-native execution outperforms per-kernel native calls in this steady-state scenario."

Forbidden claim: "Java is faster than C++."

## Dimensions

```text
rows: 1K, 16K, 64K, 1M
nulls: 0% initially
```

Nullable native baseline may be deferred. The headline answer (does JVM-native beat per-kernel native?) does not require null profiles.

## Native implementation notes

For implementation choice:

- **JNI**: classic, more boilerplate, well-understood overhead.
- **FFM downcall** (`Linker`, `MethodHandle`, `Arena`): less boilerplate, lower per-call overhead, requires `--enable-native-access=ALL-UNNAMED` (already in foundation flag set).

Pick one for MVP; the spdd does not mandate which. The native C++ side can be hand-rolled (`add_int32_array(int32_t* a, int32_t* b, int32_t* out, int n)`) — full Arrow C++ integration is out of scope.

## Non-goals

- Full Arrow C++ integration layer (skip the rest of Arrow C++ Compute — we only need a single `add` kernel implemented natively).
- Claims that Java is faster than C++ in general.
- Blocking MVP on JNI difficulty — if both JNI and FFM are too costly to wire in this iteration, defer with a written reason and a follow-up plan.
- Nullable native variant.

## Acceptance criteria

- At least one native-per-kernel benchmark exists OR is explicitly deferred with a documented reason and a follow-up plan.
- Reports state that native-boundary overhead is included.
- JVM benchmarks remain runnable without the native side if the native build is optional.
- Headline comparison (JVM-native vs native-per-kernel) is reportable at 1K / 16K / 64K / 1M rows.

## Cross-references

- `BENCHMARKS.md §Native-baseline benchmarks`.
- `CORE_DESIGN.md §Risks & assumptions` (FFM dependency).

## Domain Concept Identification

### Domain Concept Identification

#### Existing Concepts (from codebase)
- JVM-native kernel layers: raw (`AddInt32Raw`, `AddFloat64Raw`), wrapper (`AddInt32`, `AddFloat64`), and dispatch facade (`Compute.add` via `AddDispatch`) already exist and define the three Java-side comparison points.
- Benchmark governance model: `BenchmarkMetadataProvider`, `BenchmarkMetadata`, `BenchmarkSuiteValidator`, and `BenchmarkProfiles` already enforce row/null policy, benchmark labeling, and reproducibility contracts.
- Baseline interpretation policy: `BENCHMARKS.md` already defines native-baseline semantics and explicitly states boundary overhead must be included.
- Build/runtime native-access readiness: Gradle shared JVM args already include `--enable-native-access=ALL-UNNAMED`, enabling FFM-based experiments without introducing new runtime flag policy.

#### New Concepts Required
- Native per-kernel invocation layer: a benchmarkable boundary-crossing call path (JNI or FFM downcall) for primitive add operations, treated as a first-class benchmark layer.
- Optional native backend contract: a build/runtime separation model where native baseline can be present or deferred while JVM benchmark suites remain runnable.
- Headline-scenario reporting contract: explicit result labeling that distinguishes `native_cpp_per_kernel` (and optionally JNI/FFM split labels) from Java layer measurements.
- Defer-with-plan governance artifact: if native wiring is postponed, a documented rationale plus follow-up plan becomes part of acceptance handling rather than an ad hoc note.

#### Key Business Rules
- Scenario truthfulness rule: results must compare JVM-native execution against boundary-crossing native invocation, never claim language-level superiority.
- Included-overhead rule: JNI/FFM boundary cost is part of measured design cost and must remain in the reported numbers.
- MVP-scope rule: one or two primitive ops (`AddInt32`, `AddFloat64`) are sufficient; full Arrow C++ integration and nullable native profiles are out of scope.
- Operability rule: JVM benchmark workflows must continue to run when native components are optional or deferred.

## Strategic Approach

### Strategic Approach

#### Solution Direction
- Extend the current benchmark layer architecture with a native-per-kernel layer for `AddInt32` and `AddFloat64`, and compare it against existing `java_raw_vector`, `java_wrapper`, and `java_compute_dispatch` layers under the established row matrix.
- Reuse existing benchmark metadata and policy conventions so the native baseline is integrated into current reporting/governance rather than treated as a standalone experiment.
- Keep implementation strategy minimal and decoupled from Arrow C++ internals: native side remains a narrow primitive-array/buffer add contract, while Java-side orchestration follows current benchmark setup and reproducibility practices.

#### Key Design Decisions
- Native bridge choice (JNI vs FFM downcall): JNI is mature but boilerplate-heavy; FFM is lower-boilerplate and aligned with this codebase's FFM usage but may have runtime/platform nuances -> recommend FFM-first for MVP because native-access flags are already standardized and memory-segment-centric patterns already exist.
- Native layer optionality (hard-required vs optional/deferred): hard-required increases delivery risk; optional/deferred preserves benchmark continuity -> recommend optional integration with explicit defer policy to satisfy ACs without blocking JVM benchmark progress.
- Reporting granularity (single `native_cpp_per_kernel` vs split JNI/FFM labels): single label is simpler for MVP; split labels improve attribution if both paths exist -> recommend single mandatory label with conditional JNI/FFM split only when both are actually wired.
- Operation scope (`AddInt32` only vs `AddInt32` + `AddFloat64`): one op lowers integration effort; two ops improve representativeness across integer and floating-point workloads -> recommend starting with `AddInt32` as required minimum and adding `AddFloat64` if schedule allows in same iteration.

#### Alternatives Considered
- Full Arrow C++ compute bridge baseline: rejected because requirement explicitly narrows scope to minimal per-kernel native baseline and treats full integration as non-goal.
- Native-only benchmark suite separate from existing JMH layering: rejected because current benchmark governance is layer-matrix driven and separation would weaken comparability.
- Forcing nullable native benchmark in MVP: rejected because requirement explicitly allows deferring nullable native profile for the headline question.

## Risk & Gap Analysis

### Risk & Gap Analysis

#### Requirement Ambiguities
- Native path selection criteria are underspecified: requirement allows JNI or FFM but does not define decision thresholds (engineering effort, stability, portability) for choosing one.
- `AddFloat64` scope wording is soft: "Start with `AddInt32` and `AddFloat64`" can be read as mandatory-both vs phased-first.
- Defer acceptance evidence shape is unspecified: requirement demands documented reason and follow-up plan but does not define minimum structure or location.
- Optional native build behavior is not fully specified: expectation is JVM suite remains runnable, but fallback behavior for absent native symbols/libs is not detailed.

#### Edge Cases
- Small-batch regime (1K rows) may be dominated by boundary overhead, potentially reversing relative ranking vs large batches; this is important for honest interpretation.
- Cross-platform native build variability can make benchmark availability inconsistent across developer machines/CI environments.
- Mismatch between benchmark data layout and native function expectations can skew measurements if marshaling/copy paths differ between JNI and FFM approaches.
- Partial coverage scenario (only one op native-wired) may create overgeneralized headline claims if report wording is not scoped per operation.

#### Technical Risks
- Native integration risk: introducing JNI/FFM bindings can add toolchain/platform complexity and delay benchmark delivery -> mitigation direction is minimal native surface and explicit optional/defer path.
- Measurement comparability risk: if native path allocates or copies differently from Java paths, results may reflect setup asymmetry rather than boundary cost -> mitigation direction is consistent preallocation and explicit reporting of allocation policy.
- Runtime fragility risk: missing native artifacts can break benchmark runs -> mitigation direction is clear optional native mode with graceful exclusion and documented reason.
- Interpretation risk: stakeholders may misread outputs as language benchmark -> mitigation direction is strict labeling and report language aligned with `BENCHMARKS.md` interpretation rules.

#### Acceptance Criteria Coverage
| AC# | Description | Addressable? | Gaps/Notes |
|-----|-------------|--------------|------------|
| 1 | At least one native-per-kernel benchmark exists OR is explicitly deferred with a documented reason and a follow-up plan. | Yes | Current codebase has no native JNI/FFM call path yet; AC is still satisfiable via either implementation or explicit defer artifact. |
| 2 | Reports state that native-boundary overhead is included. | Yes | `BENCHMARKS.md` already encodes this rule; reporting must carry this statement into benchmark output/readout artifacts. |
| 3 | JVM benchmarks remain runnable without the native side if the native build is optional. | Partial | Existing JMH suite is JVM-only and runnable; optional-native fallback policy/mechanism is not yet formalized for native benchmark classes. |
| 4 | Headline comparison (JVM-native vs native-per-kernel) is reportable at 1K / 16K / 64K / 1M rows. | Partial | Required row matrix exists in benchmark policy and several current benchmarks, but no native-per-kernel benchmark currently provides the comparison outputs. |
