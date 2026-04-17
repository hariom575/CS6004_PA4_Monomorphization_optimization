# CS6004 PA4 — Monomorphization Optimization

**Team:** The Heap Farmer

**Members:**
- Hariom Mewada (Roll: 24m2137)
- Pushpendra Uikey (Roll: 23b1023)

---

## 1. What We Implemented

We implemented a monomorphization pass on Java bytecode using Soot's Jimple IR. The main idea is to look at virtual call sites and check how many concrete methods can actually be called at runtime. If the number is small (1, 2, or 3-4), we replace the virtual dispatch with cheaper code.

We use three layers of analysis to figure out the target set at each call site:

1. **CHA/VTA** — Soot's built-in call graph. Soot uses Class Hierarchy Analysis (CHA) and then applies VTA (Variable Type Analysis) internally, so the call graph we get already has VTA-level precision. This is the starting upper bound.

2. **Intra-procedural Points-to Analysis (PTA)** — we wrote our own forward dataflow analysis that tracks, for each local variable, which `new` expressions can reach it (allocation sites). If the receiver has no UNKNOWN allocation sites, we can narrow the target set.

3. **k-Object Sensitivity (k=2)** — handles the "container pattern" where the receiver came from a field of the caller. PTA alone can't resolve these because inside the callee, `this` is UNKNOWN. We look at the caller's field state to find out what concrete types the field holds.

**Transformations applied:**
- MONO (1 target): inline the callee if body ≤ 30 Jimple stmts, else devirtualise
- BIMORPHIC (2 targets): guarded dispatch — one `instanceof` check + two direct calls
- POLY (3-4 targets): type-test chain — one `instanceof` per target + fallback virtual call
- MEGA (5+ targets): leave as is, not worth touching

---

## 2. Analysis Details

### CHA/VTA (Layer 1)

We use Soot's call graph built with `cg.cha`. Soot runs VTA on top of CHA internally, so the edges we get already reflect which types are actually instantiated in the program. For a virtual call `r.m()` where `r` has declared type `T`, the CHA/VTA layer gives all concrete methods that override `m` in any subtype of `T` that is actually instantiated.

**Assumptions:** whole-program analysis, no reflection, no dynamic class loading.

**Precision dimensions:** flow-insensitive, context-insensitive at this layer.

### Intra-proc PTA (Layer 2)

We run a forward worklist-based dataflow analysis on the caller's body. The abstract state at each point maps:
- local → set of AllocSites (which `new` statements can reach it)
- (AllocSite, field) → set of AllocSites (field contents)

Key rules:
- `x = new T()` → pts(x) = {fresh AllocSite with type T}
- `x = y` → pts(x) = pts(y)
- `x = y.f` → pts(x) = union of pts(o.f) for each o in pts(y), UNKNOWN if any field is empty
- `y.f = x` → strong update if |pts(y)| == 1 and not UNKNOWN, else weak update
- Method call → conservatively invalidate all reachable fields (escape analysis)

When the receiver has no UNKNOWN sites, we map each allocation site type to its dispatch target and intersect with the CHA targets (soundness: we never add targets, only remove).

**Assumptions:** no aliasing across method boundaries (intra-proc only), return values from calls are UNKNOWN.

**Precision:** flow-sensitive within the method, allocation-site based.

### k-Object Sensitivity (Layer 3, k=2)

For a call `box.run()` where the caller's PTA says `box` points to some known AllocSite, we walk into the callee body and track:
- `dest = this.f` → look up callerState[BoxAllocSite.f] to get concrete types
- `dest = src` → copy tracked set
- If a virtual call is made on a tracked local with known types → resolve target

At depth k, this can resolve patterns like `outer.inner.doWork()` that PTA misses because the receiver came through a field chain.

---

## 3. Transformation Details

### MONO → Inline

Before:
```
$b = new Only;
$result = virtualinvoke $b.<Base1: int compute()>();
```
After (inlined):
```
$b = new Only;
$r0_inl0 = $b;       // bind @this
$result = 42;         // callee body expanded here
goto postReturn;
postReturn: nop;
```

### MONO → Devirtualise

Before:
```
virtualinvoke $c.<C: void foo(A)>($a)
```
After:
```
$dvt_D = (D) $c;
virtualinvoke $dvt_D.<D: void foo(A)>($a)
```
We use `virtualinvoke` (not `specialinvoke`) on the concrete class to stay JVM-verifier safe.

### BIMORPHIC → Guarded Dispatch

Before:
```
virtualinvoke $a.<Animal5: void speak()>()
```
After:
```
$iof_Dog5 = $a instanceof Dog5;
if $iof_Dog5 != 0 goto arm0;
$cast_Cat5 = (Cat5) $a;  specialinvoke $cast_Cat5.<Cat5: void speak()>();  goto done;
arm0: $cast_Dog5 = (Dog5) $a;  specialinvoke $cast_Dog5.<Dog5: void speak()>();  goto done;
done: nop;
```

### Key Challenge: Soot PatchingChain Self-loop Bug

When inserting code before a unit, Soot's `PatchingChain.insertBefore(X, pivot)` automatically redirects all existing jump targets from `pivot` to `X`. This is good for keeping incoming jumps correct. But it also redirects any unit-box inside `X` that points to `pivot`, which causes a self-loop if `X` is `new GotoStmt(pivot)`.

The fix: capture `afterCall = units.getSuccOf(site.stmt)` before any edits, insert an `entryNop` before `site.stmt` as the new jump landing, then use `insertAfter` with a moving cursor for all subsequent insertions. Arm gotos point to `afterCall` which is never used as an `insertBefore` pivot.

---

## 4. Results

### Functional Correctness

All 12 testcases produce the same output before and after optimization.

| Test | Description | Classification | Transformation |
|------|-------------|----------------|----------------|
| Test1  | Single subtype, MONO           | MONO     | Inline |
| Test2  | PTA narrows CHA, MONO          | MONO     | Devirt |
| Test3  | Interface dispatch, MONO       | MONO     | Inline |
| Test4  | Multiple MONO call sites       | MONO×3   | 2×Inline + Devirt |
| Test5  | Two allocation sites, BIMORPHIC| BIMORPHIC| Guarded dispatch |
| Test6  | Three types, POLY              | POLY     | Type-test chain |
| Test7  | Container pattern, k-obj       | BIMORPHIC| Guarded dispatch |
| Test8  | Two-level field chain, k=2     | BIMORPHIC| Guarded dispatch |
| Test9  | Two containers, different types| BIMORPHIC| Guarded dispatch |
| Test10 | Five subtypes, MEGA (no opt)   | MEGA     | Skipped |
| Test11 | Dead allocation, PTA→MONO      | MONO     | Devirt |
| Test12 | Inline threshold boundary      | MONO×4   | 2×Inline + 2×Devirt |

### Performance

Tests use a loop of 100,000 iterations so interpreter overhead is measurable. All timings with `-Xint` (no JIT). Average of 3 runs.

| Test | Before (s) | After (s) | Improvement |
|------|-----------|-----------|-------------|
| Test1  | ~0.18 | ~0.12 | ~33% |
| Test2  | ~0.17 | ~0.12 | ~29% |
| Test3  | ~0.17 | ~0.11 | ~35% |
| Test4  | ~0.21 | ~0.14 | ~33% |
| Test5  | ~0.16 | ~0.14 | ~13% |
| Test6  | ~0.17 | ~0.15 | ~12% |
| Test7  | ~0.17 | ~0.14 | ~18% |
| Test8  | ~0.17 | ~0.15 | ~12% |
| Test9  | ~0.17 | ~0.14 | ~18% |
| Test10 | ~0.16 | ~0.16 | 0% (MEGA, no change) |
| Test11 | ~0.18 | ~0.12 | ~33% |
| Test12 | ~0.20 | ~0.13 | ~35% |

MONO sites with inlining give ~30-35% improvement because both the vtable lookup and call overhead are gone. BIMORPHIC and POLY sites give ~12-18% because a predictable branch + direct call is cheaper than a vtable lookup, but not as much as full inlining. MEGA sites show no change as expected.

---

## 5. Running the Code

```bash
bash script.sh
```

This compiles `src/Main.java` and all supporting files, then runs each testcase one by one. For each test it shows the full analysis report (MONO/BI/POLY/MEGA counts, per-site decisions), verifies output correctness, and prints timing. At the end it prints a summary table.

Transformed class files are written to `sootOutput/`.
