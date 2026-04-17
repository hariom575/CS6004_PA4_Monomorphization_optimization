# CS6004 PA4 — Monomorphization Optimization

**Student:** Hariom Mewada (24m2137@iitb.ac.in), Pushpendra Uikey 

---

## Overview

This assignment implements a monomorphization pass on Java bytecode using Soot's Jimple IR. The goal is to resolve virtual calls at compile time when the set of possible dispatch targets is small, replacing them with direct calls (and optionally inlining). The pass runs in Soot's `wjtp` (whole-Jimple transformation pack) phase after whole-program analysis.

Three layers of analysis progressively narrow the target set for each virtual call site:

1. **CHA** — class hierarchy analysis, the conservative upper bound
2. **Intra-procedural PTA** — points-to analysis within the containing method, tracking allocation sites
3. **k-Object Sensitivity (k=2)** — cross-method field tracking for container patterns

Sites are classified as MONO (1 target), BIMORPHIC (2), POLY (3–4), or MEGA (5+). The first three get Jimple rewrites; MEGA is left as a virtual call.

---

## Implementation

### Analysis Layers

**CHA** is read directly from Soot's call graph (built with `cg.cha`). For each virtual/interface invoke, I iterate `cg.edgesOutOf(stmt)` and collect concrete targets. This gives the starting target set.

**Intra-procedural PTA** runs a forward dataflow over the CFG of each containing method. The abstract state tracks `Map<Local, Set<AllocSite>>` where `AllocSite` records the concrete type at a `new` statement. If the receiver's points-to set has no UNKNOWN entries, I resolve dispatch for each allocation site type and intersect with CHA targets (soundness: never add targets, only remove). The analysis is cached per method.

**k-Object Sensitivity** handles the container pattern where PTA leaves `pts(this) = UNKNOWN` inside a callee because the receiver came from a field. For a call `box.run()` where the caller's PTA state knows `pts(box)` points to a specific `BoxAllocSite`, I walk into the callee body looking for `this.field` reads and cross-reference the caller's field state for that allocation site. At depth `k`, this can resolve dispatch targets that pure intra-procedural PTA misses.

### Jimple Rewrites

**MONO → Inline:** If the callee has ≤30 Jimple units and no exception traps, I inline the body. The process is: alpha-rename callee locals with a suffix, bind `@this` and `@parameter` identity stmts to the actual arguments, then insert the cloned units before the call site. Return stmts become gotos to a shared `postReturn` nop, which handles callees with multiple return paths. After insertion, `site.stmt` is removed.

**MONO → Devirtualise:** For callees that are too large to inline, I insert a cast to the concrete type before the call site, then rewrite the invoke expression as `virtualinvoke castLocal.<ConcreteClass: ...>()`. Using `virtualinvoke` on the concrete class (rather than `specialinvoke`) keeps the bytecode JVM-verifier safe — `specialinvoke` across class boundaries can fail verification.

**BIMORPHIC → Guarded dispatch:** Two targets. One gets a type test (`instanceof`); the other is the fallthrough else arm. The generated structure is:

```
$iof_T0 = r instanceof T0;
if $iof_T0 != 0 goto arm0;
// else: cast to T1, call T1.m(), goto afterCall
arm0:
// cast to T0, call T0.m(), goto afterCall
afterCall: ...
```

**POLY → Type-test chain:** Three or four targets. Each gets a type test; the original virtual call stays as the fallback if all tests fail.

```
$iof_T0 = r instanceof T0; if $iof_T0 != 0 goto arm0;
$iof_T1 = r instanceof T1; if $iof_T1 != 0 goto arm1;
$iof_T2 = r instanceof T2; if $iof_T2 != 0 goto arm2;
virtualinvoke r.<Decl: m()>();  // fallback
goto afterCall;
arm0: ...; goto afterCall;
...
```

### Key Bug: Soot PatchingChain and Self-loop Gotos

The main difficulty in the Jimple rewrite was Soot's `PatchingChain`. When you call `insertBefore(X, pivot)`, PatchingChain automatically redirects *all* unit-box targets that currently point to `pivot` to instead point to `X`. This is useful — it means external gotos landing on `pivot` get updated to land on `X`. But it has a trap: if `X` itself has a unit-box pointing to `pivot` (e.g., `X = new GotoStmt(pivot)`), that box also gets redirected from `pivot` to `X`, creating a self-loop.

The original code used a `doneNop` as a convergence point and inserted arm gotos with `insertBefore(new GotoStmt(doneNop), doneNop)`. This always created self-loops:

```
label5:
    goto label5;   // ← goto doneNop got redirected to itself
```

The fix is to avoid `insertBefore` for goto insertion entirely. Instead:

1. Before any edits, capture `afterCall = units.getSuccOf(site.stmt)` — the convergence point is the *original* successor, which already exists in the chain.
2. Insert an `entryNop` before `site.stmt` (PatchingChain redirects all external jumps to `entryNop`).
3. Use `insertAfter` with a moving cursor for all subsequent insertions.
4. Arm gotos point to `afterCall` (an already-existing unit, never used as an `insertBefore` pivot).

Since `insertAfter` does not redirect jump targets in PatchingChain, and `afterCall` is never a pivot, no self-loop can form.

---

## Test Results

| Test | Description | After CHA | After PTA | Transformation |
|------|-------------|-----------|-----------|----------------|
| Test1 | Single concrete subtype, MONO | MONO=2 | MONO=2 | Inline + Devirt |
| Test2 | Field assignment, MONO | MONO=2 | MONO=2 | Inline + Devirt |
| Test3 | Interface dispatch, MONO | MONO=2 | MONO=2 | Inline + Devirt |
| Test4 | Multiple call sites, all MONO | MONO=3 | MONO=3 | 2×Inline + Devirt |
| Test5 | Runtime branch, Dog/Cat — BIMORPHIC | BI=1 | BI=1 | Guarded dispatch |
| Test6 | Switch on 3 types — POLY | POLY=1 | POLY=1 | Type-test chain |
| Test7 | Container pattern, k-obj needed | BI=1 | BI=1 | Guarded dispatch |
| Test8 | Two-level field chain, k=2 | BI=1 | BI=1 | Guarded dispatch |
| Test9 | Two containers, different types | BI=1 | BI=1 | Guarded dispatch |
| Test10 | Five subtypes — MEGA, no rewrite | MEGA=1 | MEGA=1 | Skipped |
| Test11 | PTA resolves to MONO | MONO=2 | MONO=2 | Inline + Devirt |
| Test12 | Inline threshold boundary | MONO=4 | MONO=4 | 2×Inline + 2×Devirt |

All 12 tests produce valid Jimple with no self-loop gotos and no rewriter errors.

**Test12 note:** BigWorker12.work() is expected to exceed the 30-stmt threshold based on Java source line count, but Soot's constant folding collapses all the integer arithmetic to a single string concatenation (6 Jimple units total). So both workers end up inlined. The threshold check is against Jimple units, not source lines, so this is correct behavior.

---

## CHA vs PTA vs k-Obj Comparison

On these test cases, PTA does not narrow beyond CHA. This is expected — the tests are designed so the declared static types already constrain the dispatch set well. For instance, in Test5, `l2` is declared as `Animal5`, and CHA already knows only `Dog5` and `Cat5` extend `Animal5`. PTA sees two allocation sites (`new Dog5`, `new Cat5`) and gets the same two targets. No narrowing happens.

k-Object Sensitivity also shows no narrowing gain in these tests. The container pattern tests (Test7-9) are structured so the BIMORPHIC classification is correct even with k-obj — the containers hold different types at different call sites, so narrowing to MONO would be unsound. A deeper test (e.g., one container holding exactly one type in a single allocation context) would show k-obj narrowing.

---

## Running the Analysis

```bash
# Compile + run all 12 tests (summary table):
./run_tests.sh

# Run a single test with full output:
./run_tests.sh 5

# Compiled class files + Jimple output:
# analysis/*.class
# analysis/sootOutput/<ClassName>.jimple
```

The run script compiles the analysis code, then for each testcase compiles the Java sources into a temp directory, runs the analysis, and reports the per-testcase dispatch counts and rewrite summary. Transformed Jimple files are written to `analysis/sootOutput/`.
