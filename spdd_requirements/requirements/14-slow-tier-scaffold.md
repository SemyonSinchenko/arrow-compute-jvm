# Requirement: Slow-Tier Scaffold — RegexMatchUtf8

## Business requirement

Establish the slow-tier kernel pattern through one canonical operation. `RegexMatchUtf8` is the canonical slow-tier op: not SIMD-amenable, plain Java loop over Arrow vectors, behind a pluggable interface so future JNI / re2j / Hyperscan-Java backends can swap in without changing callers.

This iteration commits the slow-tier pattern (no `raw/` layer, `wrapper/slow/` location, interface + default-impl pluggability, honest PyArrow benchmark) that all subsequent slow-tier spdds reuse.

## Scope

Create the full vertical for `RegexMatchUtf8` over `VarCharVector` input with a `String` pattern and `BitVector` boolean output:

```text
wrapper/slow/RegexMatcher.java       (interface + defaultMatcher factory)
wrapper/slow/JavaUtilRegexMatcher.java (default impl, java.util.regex)
wrapper/slow/RegexMatchUtf8.java
dispatch/RegexDispatch.java
Compute.regexMatch(...)
```

No `raw/` layer for this op.

## Pluggability

`RegexMatcher` is the **interface**:

```java
public interface RegexMatcher {
    void match(VarCharVector input, String pattern, BitVector out);
    static RegexMatcher defaultMatcher() {
        return new JavaUtilRegexMatcher();
    }
}
```

- Default implementation uses `java.util.regex.Pattern` precompiled once per call.
- Callers can pass a custom `RegexMatcher` per call:
  `Compute.regexMatch(input, pattern, out, myMatcher)`.
- A static factory swap allows global override:
  `RegexMatcher.setDefault(myMatcher)` (or equivalent — final shape settled at impl).
- **No `ServiceLoader`, no DI framework, no `FunctionRegistry`.**

This is the pluggability template every other slow-tier op with plausible alternative backends will follow.

## Wrapper behavior

`RegexMatchUtf8.eval(VarCharVector input, String pattern, BitVector out, RegexMatcher matcher)`:

- `Checks.outputCapacity(out, n)`
- `Checks.zeroSliceOffset(input)`
- Validate pattern up front (compile once); on `PatternSyntaxException`, rethrow before any compute happens.
- Propagate input validity to `out` (`Validity.propagateUnary` or `markAllValid` per `getNullCount`).
- Delegate to `matcher.match(input, pattern, out)`. Default impl walks rows via `input.get(i)` (or zero-copy byte access) and tests `pattern.matcher(bytesAsCharSeq).find()` or `.matches()` — concrete choice (`find` vs `matches`) documented at impl.
- `out.setValueCount(n)`.

## Coding rules (slow tier)

- Allowed: Arrow Java accessors (`vector.get(i)`, `vector.getValueLength(i)`, `vector.getDataBuffer()`), `for`/`while` loops, `BitVectorHelper`, `Pattern.compile` once per call, `Matcher` reused across rows when safe.
- Forbidden: per-row allocation of `String` if the data can be tested as bytes, boxing, streams, lambdas inside the inner loop, reflection, per-row `try`/`throw` for normal flow.
- `Pattern` precompiled once before the loop. Per-row `Matcher.reset(...)` is acceptable.

## Public API

`Compute.regexMatch(VarCharVector input, String pattern, BitVector out)` uses the default matcher.

`Compute.regexMatch(VarCharVector input, String pattern, BitVector out, RegexMatcher matcher)` accepts a custom backend.

## Dispatch behavior

`RegexDispatch` is `public` and routes the type combination to `RegexMatchUtf8`. Throw `UnsupportedOperationException` via `Errors.unsupported(...)` for unsupported input types.

## Tests

Wrapper tests with Arrow (no separate raw tests — there is no raw layer):

- Empty input, single row, all-match, all-no-match, mixed.
- Multibyte UTF-8 inputs.
- All-null, sparse nulls (1%), dense nulls (30%); output validity propagates correctly; null lanes are skipped, not regex-matched.
- Pattern syntax errors throw before the compute loop starts (assert via `assertThrows`); no partial writes to `out`.
- Custom `RegexMatcher` parameter wired through (assert delegation).
- Allocator-debug-mode test JVM; no leaks.
- `BitVector.validateFull()` on output.
- Float ULP rule does not apply (boolean output).

## Benchmarks

- Project plain-Java implementation (`JavaUtilRegexMatcher`) vs PyArrow `pa.compute.match_substring_regex` over preloaded Arrow batches.
- Native baseline (JNI/FFM into RE2 or Hyperscan) is **optional** for this iteration — defer if too expensive.

Required dimensions:

- Rows: 1K, 16K, 64K, 1M.
- Null profiles: 0%, 10%.
- Pattern complexity: literal substring (`"foo"`), anchored (`"^foo"`), alternation (`"foo|bar"`), bounded repetition.

Report explicitly labels result as **SLOW tier** and cross-references `CORE_DESIGN.md §Two-tier kernel design`. Do not editorialize gap size; print the numbers.

## Non-goals

- `regex_extract`, `regex_replace`, full `LIKE` deferred to a slow-tier string expansion spdd.
- `ServiceLoader`-based registration is out of scope; per-call override + static factory swap is the entire pluggability story.
- No native baseline implementation required for this iteration; the interface is shaped to accept one later.

## Acceptance criteria

- `Compute.regexMatch(...)` works end-to-end with the default matcher.
- A trivial alternative `RegexMatcher` impl (test-only, e.g., `AlwaysMatchMatcher`) plugs in cleanly and the wrapper delegates to it.
- Wrapper tests pass.
- JMH benchmark runs and produces interpretable SLOW-tier labeled results vs PyArrow.
- The pattern (interface + default impl, no raw layer, honest benchmark) is documented well enough that the next slow-tier spdd reuses it without re-deriving design decisions.

## Cross-references

- `AGENTS.md §Slow-tier kernels` (rules, pluggability, benchmark requirement).
- `CORE_DESIGN.md §Two-tier kernel design`, §Aggregation state model (unrelated, but lives near).
- `ARROW_JAVA_API_USAGE.md §15 Slow tier uses Arrow Java fully`.
- `BENCHMARKS.md §Slow-tier benchmarks`.
