# SPDD Requirements Pack

This directory holds per-iteration inputs to the SPDD workflow. The
authoritative design lives in the top-level foundation docs:

- `AGENTS.md` — rules for LLM/agent contributors in hot-path code.
- `CORE_DESIGN.md` — architecture, layers, two-tier kernel design.
- `BENCHMARKS.md` — measurement methodology, baselines, claims.
- `ARROW_JAVA_API_USAGE.md` — when to use Arrow Java vs project helpers.
- `DEVELOPMENT_PLAN.md` — iteration dependency tree and execution order.

Foundation docs are ground truth; if a spdd file disagrees with a
foundation doc, the foundation wins and the spdd is wrong.

Iteration dependency order and iteration status tracking are maintained
in `DEVELOPMENT_PLAN.md`.

Each file in `requirements/` is an input for SPDD:

```text
/spdd-analysis @requirements/NN-name.md
/spdd-reasons-canvas @spdd/analysis/NN-name.md
/spdd-generate @spdd/prompt/NN-name.md
/spdd-sync @spdd/prompt/NN-name.md
```

Run one small iteration at a time. Do not ask the agent to implement
the whole project in one prompt.

## Core constraints repeated across iterations

- raw kernels are Arrow-free, single-threaded, allocate nothing;
- slow-tier kernels live in `wrapper/slow/`; they have no raw layer,
  use Arrow Java accessors, and ship behind pluggable interfaces +
  default plain-Java implementations when a JNI/3rd-party swap is
  plausible;
- dispatch and wrappers may use Arrow Java APIs; raw kernels may not;
- use Arrow Java utilities before writing local helpers (see
  `ARROW_JAVA_API_USAGE.md`);
- options pass as primitive flags at wrapper signatures only;
  no options objects cross into wrappers or raw kernels;
- raw kernel naming: `<Op><Type>[<Mode>]Raw` with `computeAll` /
  `noNulls` / `skipNulls` / `validOnly` entry-point method names;
- wrappers use `eval` as the entry-point method name;
- validity polarity is 1 = valid, 0 = null;
- buffers are little-endian (Arrow in-memory invariant);
- raw kernels assume non-aliasing inputs vs output;
- wrappers reject vectors with non-zero slice offset;
- wrappers retain data + validity buffers together via
  `BufferRefs.retain(...)`;
- `MemorySegment` views never escape the wrapper's try-with-resources;
- tests run with `-Darrow.memory.debug.allocator=true`;
- checked kernels precheck active rows before the compute loop and
  throw before any compute happens; no per-row throws in hot loop;
- do not introduce generic registries, `Datum`, `KernelSignature`,
  or UDF infrastructure during MVP;
- public-extension story is "dispatch classes are public; users
  subclass to plug in custom raw kernels", not a registry.

## Project identifiers

- Project name: `arrow-compute`.
- Root package: `io.github.semyonsinchenko.arrowcompute`.
