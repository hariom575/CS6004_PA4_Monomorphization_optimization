# CS6004 PA4 — Monomorphization Optimization with Soot

**Team:** The Heap Farmer

| Member | Roll |
|---|---|
| Hariom Mewada | 24m2137 |
| Pushpendra Uikey | 23b1023 |

---

## Overview

A monomorphization pass that operates on Java bytecode via Soot's Jimple IR. For each virtual call site, we determine statically how many concrete targets are reachable at runtime and replace expensive vtable dispatch with cheaper alternatives:

| Targets | Classification | Transformation |
|---------|---------------|----------------|
| 1 | MONO | Inline (≤30 stmts) or devirtualize |
| 2 | BIMORPHIC | Guarded if-else with static bridge calls |
| 3–4 | POLY | Type-test chain with fallback virtual call |
| ≥5 | MEGA | Left unchanged |

---

## Analysis Stack

Three layers narrow the set of possible targets at each call site:

### Layer 1: CHA + VTA (Soot built-in)

Soot builds the call graph with CHA (every concrete subtype) then applies Variable Type Analysis to remove types that are never actually assigned to the receiver variable. This is the starting upper bound and handles most simple cases (Tests 1–6, 10–12).

### Layer 2: Intra-proc Points-To Analysis

A flow-sensitive forward dataflow over the method body. Tracks:

- `local → set<AllocSite>` — which `new` expressions can reach a variable
- `(AllocSite, field) → set<AllocSite>` — field contents per object

Constructor calls are walked to populate initial field state. The main contribution of this layer is computing the field points-to map consumed by Layer 3.

### Layer 3: k-Object Sensitivity (k = 2)

Handles the container pattern where `this.field` receivers cannot be narrowed inside the method body. The analysis climbs the call graph up to two levels deep, finds the allocation site of the container in each caller, and reads the field type from the constructor call at that site.

- **k = 1 (Test7):** `box.worker` resolved from `Test7.main` → MONO
- **k = 2 (Test8):** `outer.inner.proc` resolved two levels up → MONO
- **Test9:** two callers provide different types → correctly stays BIMORPHIC

---

## Transformations

### MONO → Inline

Callee body (≤ 30 Jimple statements) is pasted directly at the call site with fresh local names.

### MONO → Devirtualize

Callee is too large to inline; receiver is cast to the concrete type and called via `virtualinvoke` on the narrow type so the JVM can devirtualize it.

### BIMORPHIC → Guarded Dispatch

An `instanceof` check selects between two static bridge methods (`speak$mono$Cat5(Cat5)`), eliminating the vtable lookup entirely.

### POLY → Type-Test Chain

Same pattern as BIMORPHIC extended to 3–4 checks with a final fallback `virtualinvoke` for the last arm.

---

## Project Structure

```
.
├── src/
│   ├── Main.java                      # Entry point, Soot setup
│   ├── MonomorphizationTransformer.java  # Top-level scene transformer
│   ├── CallSiteInfo.java              # Call site metadata (Kind enum)
│   ├── IntraProcPTA.java              # Layer 2: flow-sensitive PTA
│   ├── PointsToState.java             # AllocSite / FieldKey types
│   ├── CallSiteRewriter.java          # Guarded dispatch / type-test chain
│   ├── JimpleRewriter.java            # Inline / devirtualize
│   └── PA4.java                       # Soot pack registration
├── tests/
│   ├── Test1.java … Test15.java       # Functional test cases
│   └── (supporting classes)
├── soot-4.6.0-jar-with-dependencies.jar
├── script.sh                          # Build + benchmark script
└── report.md                          # Detailed technical report
```

---

## Running

```bash
bash script.sh
```

The script:
1. Cleans old class files and compiles the analysis
2. For each test (1–15):
   - Compiles the test class
   - Measures baseline runtime (5 runs, minimum)
   - Runs the optimization pass
   - Verifies optimized output matches original
   - Measures optimized runtime (5 runs, minimum)
3. Prints a summary table

Transformed class files are written to `sootOutput/`.

**Requirements:** Java 8+, `bash`

---

## Results Summary

38 of 40 virtual call sites optimized (95%). The 2 skipped sites are the MEGA calls in Test10 and Test13 — correctly left untouched.

| Test | Scenario | Classification | Transformation |
|------|----------|---------------|----------------|
| Test1 | Single subtype | MONO ×2 | Inline + Devirt |
| Test2 | Dead subtype (VTA) | MONO ×2 | Inline + Devirt |
| Test3 | Single interface impl | MONO ×2 | Inline + Devirt |
| Test4 | Two allocation sites | MONO ×3 | 2× Inline + Devirt |
| Test5 | Two types, runtime branch | MONO + BIMORPHIC | Guarded + Devirt |
| Test6 | Three types, switch | MONO + POLY | Type-test chain + Devirt |
| Test7 | Container field, k=1 | MONO ×2 + BI→MONO | 3× Inline + Devirt |
| Test8 | Two-level chain, k=2 | MONO ×5 + BI→MONO | 6× Inline + Devirt |
| Test9 | Two containers, diff types | MONO ×3 + BIMORPHIC | 2× Inline + Guarded + Devirt |
| Test10 | Five subtypes | MONO + MEGA | Devirt + skipped |
| Test11 | Reassignment in loop | MONO ×3 | 2× Inline + Devirt |
| Test12 | Threshold boundary | MONO ×3 | 2× Inline + Devirt |
| Test13 | Six subtypes (explicit MEGA) | MONO + MEGA | Devirt + skipped |
| Test14 | Receiver from method return | MONO + BIMORPHIC | Guarded + Devirt |
| Test15 | Receiver from array load | MONO + BIMORPHIC | Guarded + Devirt |

All 15 tests produce identical output before and after optimization.

> **Note on `-Xint` benchmarks:** Under interpreter mode some tests show slight overhead from inlining (more bytecodes per iteration). The real gains appear with JIT enabled, where monomorphic sites allow cross-call inlining (typically 10–50% speedup in production).

---

## Key Bug Fixed

**Soot `PatchingChain` self-loop:** `insertBefore(X, pivot)` redirects all jump targets—including X's own target if X is a `GotoStmt` pointing to pivot—creating an infinite loop. Fix: capture `afterCall = units.getSuccOf(site.stmt)` before edits, insert an `entryNop` as the jump landing, and use `insertAfter` for subsequent units with arm gotos pointing to `afterCall`.
