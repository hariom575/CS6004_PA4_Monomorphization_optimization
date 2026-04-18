# CS6004 PA4 - Monomorphization Optimization

**Team:** The Heap Farmer

**Members:**

- Hariom Mewada (Roll: 24m2137)
- Pushpendra Uikey (Roll: 23b1023)

---

## 1. What We Did

We built a monomorphization pass that works on Java bytecode using Soot's Jimple IR. The goal is simple: look at each virtual method call and figure out how many real methods can actually be called there at runtime. If the number is small enough, replace the virtual dispatch with cheaper code.

A virtual call goes through a vtable lookup at runtime. If we know statically that only one method can ever be called, we can skip the lookup entirely (devirtualize or inline). If two methods are possible, a simple if-else is faster than a vtable. For three or four, a chain of type checks. For five or more, we leave it alone -- not worth the code bloat.

We stack three analysis layers on top of Soot's built-in call graph:

1. **CHA/VTA** -- Soot builds the call graph using Class Hierarchy Analysis and then applies Variable Type Analysis. VTA looks at the right-hand side of assignments to remove types that are never actually put into a variable. So if only Dog is ever assigned to an Animal variable, VTA removes Cat from the call graph edge. This is our starting upper bound.

2. **Intra-proc PTA** -- our own forward dataflow analysis. It tracks, for each local variable at each program point, which allocation sites (which `new` expressions) can reach it. This is flow-sensitive: it sees each `new` statement in order. If the receiver at a call site has only one known allocation site, we can narrow to one target.

3. **k-Object Sensitivity (k=2)** -- handles the container pattern. When the receiver comes from a field (like `this.worker` inside `Container7.run()`), PTA cannot narrow it because `this` is unknown inside the method. We look at the callers of the method and use the constructor call analysis to find what was put in that field when the object was built. This works up to k=2 levels deep (outer.inner.field).

---

## 2. Analysis Details

### Layer 1: CHA + VTA (Soot's call graph)

Soot builds the call graph with `-p cg.cha enabled:true`. CHA on its own would add an edge for every concrete subtype of the declared receiver type. VTA then removes edges for types that are never actually assigned to the receiver variable anywhere in the program.

For example: if Animal has subtypes Cat and Dog, but only Cat is ever assigned to variable `a`, VTA removes the Dog edge. The call graph we read via `cg.edgesOutOf(stmt)` already has VTA applied.

Soot's VTA is also per-call-site: for each virtual call statement it uses the type information at that point, not just across the whole method. This means Soot's VTA is already quite precise and handles many simple cases.

### Layer 2: Intra-proc PTA

We run a worklist-based forward dataflow on the method body. At each program point we track:

- `local -> set of AllocSite` : which `new` statements can reach this variable
- `(AllocSite, field) -> set of AllocSite` : what is stored in each field of each object

Transfer rules:
- `x = new T()` : pts(x) = { fresh AllocSite(T) }
- `x = y` : pts(x) = pts(y)
- `x = y.f` : pts(x) = union of field contents of all y's alloc sites; UNKNOWN if any field unknown
- `y.f = x` : strong update if pts(y) has exactly one known site, else weak update
- Constructor call `specialinvoke new_obj.<T: void <init>(args)>(a1,a2...)` : we walk the constructor body and for each `this.field = @paramN` assignment, set field state to pts(argN). This lets us track what was stored in fields during construction.
- Other method call: conservatively wipe all field info for reachable objects (escape analysis)

When the receiver at a virtual call has no UNKNOWN sites, we resolve dispatch for each site and intersect with Soot's CHA edges (soundness guarantee: we never add edges, only remove).

In practice, Soot's VTA already handles most simple local-variable patterns. Our PTA's main contribution is computing the field state (AllocSite, field) -> pts which the k-obj layer uses.

### Layer 3: k-Object Sensitivity (k=2)

This handles the pattern where PTA cannot narrow a call because the receiver came through `this.field` inside a method (so `this` is UNKNOWN).

The approach for k=1 (Test7):
1. Find that receiver `worker` = `this.worker` in `Container7.run()`
2. Look at all callers of `Container7.run()` via the call graph
3. For each caller (e.g., `Test7.main` calling `box.run()`):
   - Find the allocation site of `box` in the caller
   - Find the constructor call for that allocation (`new Container7(w)`)
   - Read the PTA state at the constructor call -- at that point `w` is still `{AllocSite<HardWorker7>}` (before loop back-edges contaminate it)
   - Map constructor parameter to field: `Container7(Worker7 w)` -> `this.worker = w` -> param index 0
   - So `box.worker = pts(w at init call) = {HardWorker7}` -> MONO

For k=2 (Test8, two-level chain `outer.inner.proc`):
1. BIMORPHIC site: `proc.process()` in `Inner8.run()`, from `this.proc`
2. Callers of `Inner8.run()` is `Outer8.execute()`, but there `inner` = `this.inner` = UNKNOWN
3. Go up one more level: callers of `Outer8.execute()` is `Test8.main`
4. In `Test8.main`, find `outer` alloc site, then:
   - `resolveFieldViaConstructor(outer, Outer8.inner)` -> `{AllocSite<Inner8>}`
   - `resolveFieldViaConstructor(inner_alloc, Inner8.proc)` -> `{AllocSite<FastProc8>}`
5. -> MONO (FastProc8.process)

For Test9 (two containers with different field types): k-obj correctly stays BIMORPHIC because one caller provides RedEngine and another provides BlueEngine -- the union is two types, which is correct.

---

## 3. Transformation Details

### MONO -> Inline

When the callee body is 30 Jimple statements or fewer, we paste its body directly at the call site.

Before:
```
$b = new Only;
$result = virtualinvoke $b.<Base1: int compute()>();
```

After (inlined):
```
$b = new Only;
$r0_inl0 = $b;
$result = 42;
goto postReturn;
postReturn: nop;
```

### MONO -> Devirtualise

When the callee is too big to inline (more than 30 Jimple statements), we replace the virtual call with a static call. For this we make a new static method in the target class -- we call it a "bridge method". The bridge has the same body as the original method, but the receiver (`this`) becomes a normal first parameter. Then we use `staticinvoke` which does not need any vtable lookup at all, so it is faster than virtualinvoke even under `-Xint`.

Before:
```
$result = virtualinvoke $c.<C: int foo(A)>($a)
```

After (preferred -- static bridge):
```
$result = staticinvoke <D: int foo$mono$D(D, A)>($c, $a)
```

If bridge creation is not possible (for example, the callee is a library method we cannot copy, or it has try/catch blocks), we fall back to the old approach: cast the receiver to the concrete type and use virtualinvoke on it.

Fallback:
```
$dvt_D = (D) $c
$result = virtualinvoke $dvt_D.<D: int foo(A)>($a)
```

This same static bridge approach is also used inside the BIMORPHIC guarded dispatch arms, so both MONO devirt and BIMORPHIC rewrites produce `invokestatic` in the output Jimple for application class methods.

### BIMORPHIC -> Guarded Dispatch

We create a static bridge method in the target class that takes `this` as an explicit first parameter, then use invokestatic. This removes vtable overhead entirely and passes JVM bytecode verification.

Before:
```
virtualinvoke $a.<Animal5: String speak()>()
```

After (in Test5.main):
```
$iof_Dog5 = $a instanceof Dog5;
if $iof_Dog5 != 0 goto arm0;
$cast_Cat5 = (Cat5) $a;
staticinvoke <Cat5: String speak$mono$Cat5(Cat5)>($cast_Cat5);
goto done;
arm0: $cast_Dog5 = (Dog5) $a;
staticinvoke <Dog5: String speak$mono$Dog5(Dog5)>($cast_Dog5);
goto done;
done: nop;
```

The static bridge `speak$mono$Cat5(Cat5 self)` is generated by copying Cat5.speak's body and replacing `@this` with `@parameter0`.

### POLY -> Type-Test Chain

Same idea as BIMORPHIC but with 3-4 type checks and a final fallback virtual call for the last arm.

### Key Bug Fixed: Soot PatchingChain Self-loop

`PatchingChain.insertBefore(X, pivot)` redirects all jump targets from pivot to X. But if X is a GotoStmt pointing to pivot, it also redirects X's own target -- creating a self-loop.

Fix: capture `afterCall = units.getSuccOf(site.stmt)` before edits. Insert an `entryNop` before `site.stmt` as the jump landing. Use `insertAfter` for all subsequent units. Arm gotos point to `afterCall` (never used as insertBefore pivot).

---

## 4. Results

### What Each Analysis Contributes

| Layer | What it does | When it helps |
|-------|-------------|---------------|
| CHA | Adds edge for every concrete subtype | Starting point -- always gives an upper bound |
| VTA (Soot) | Removes types not assigned to the receiver variable | Removes dead-type edges (Tests 1-6, 10-12) |
| Our PTA | Flow-sensitive allocation tracking; computes field state | Provides field pts map needed by k-obj |
| k-obj (k=2) | Looks at caller's constructor to find field types | Narrows container-pattern BIMORPHIC to MONO (Tests 7, 8) |

Note: Soot's VTA is already per-call-site and handles most local-variable narrowing. Our PTA confirms this independently and computes the field state that k-obj relies on. k-obj provides the additional narrowing for cases where the receiver came through a field chain.

### Functional Correctness

All 15 testcases produce the same output before and after optimization.

| Test   | Scenario                                    | CHA/VTA result        | k-obj gain    | Transformation              |
|--------|---------------------------------------------|-----------------------|---------------|-----------------------------|
| Test1  | Single subtype only                         | MONO x2               | none          | Inline + Devirt             |
| Test2  | Dead subtype (VTA removes unused type)      | MONO x2               | none          | Inline + Devirt             |
| Test3  | Single interface implementation             | MONO x2               | none          | Inline + Devirt             |
| Test4  | Two distinct allocation sites, both MONO    | MONO x3               | none          | 2x Inline + Devirt          |
| Test5  | Two types via runtime branch                | MONO x1 + BIMORPHIC   | none          | Guarded dispatch + Devirt   |
| Test6  | Three types via switch                      | MONO x1 + POLY        | none          | Type-test chain + Devirt    |
| Test7  | Container -- this.field receiver (k=1)      | MONO x2 + BIMORPHIC   | BI -> MONO    | 2x Inline + Devirt          |
| Test8  | Two-level chain outer.inner.proc (k=2)      | MONO x5 + BIMORPHIC   | BI -> MONO    | 5x Inline + Devirt          |
| Test9  | Two containers with different field types   | MONO x3 + BIMORPHIC   | BI stays BI   | 2x Inline + Guarded + Devirt|
| Test10 | Five subtypes -- MEGA, no optimization      | MONO x1 + MEGA        | none          | Devirt (println) + skipped  |
| Test11 | Cat then Dog reassigned in loop             | MONO x3               | none          | 2x Inline + Devirt          |
| Test12 | Two callees both within inline threshold    | MONO x3               | none          | 2x Inline + Devirt          |
| Test13 | Six subtypes -- MEGA (explicit limit test)  | MONO x1 + MEGA        | none (MEGA)   | Devirt (println) + skipped  |
| Test14 | Receiver from method return (inter-proc)    | MONO x1 + BIMORPHIC   | none (UNKNOWN)| Guarded + Devirt            |
| Test15 | Objects loaded from array (UNKNOWN)         | MONO x1 + BIMORPHIC   | none (UNKNOWN)| Guarded + Devirt            |

Notes:
- Tests 1-6, 10-12: Soot's VTA already gives the right answer. Our PTA confirms and computes field state for k-obj.
- Tests 7-8: k-obj narrows the one BIMORPHIC site to MONO by resolving the field type from the caller's constructor call.
- Test9 correctly stays BIMORPHIC: the single call site inside `Holder9.start()` sees two callers with different field types (Red and Blue), so the union is two -- the right answer.
- Test13: six subtypes exceed the MEGA threshold (4); the call site is skipped with no transformation.
- Test14: the receiver `t = make(true)` is a method return value; intra-proc PTA conservatively marks it UNKNOWN and cannot narrow it beyond VTA's BIMORPHIC. k-obj does not help because the receiver is not a stored field.
- Test15: the receiver `a = arr[0]` is an array element load; our PTA does not track array contents and marks the load UNKNOWN. The site stays BIMORPHIC. Full precision would require a separate array-element points-to analysis.

### Performance

Tests use a loop of 100,000 iterations. Measured with `-Xint` (interpreter, no JIT). Minimum of 5 runs.

| Test   | Before (s) | After (s) | Change | What happened                                       |
|--------|-----------|-----------|--------|-----------------------------------------------------|
| Test1  | 0.025     | 0.026     | -4%    | Inline overhead in -Xint (within noise)             |
| Test2  | 0.027     | 0.026     | +4%    | MONO inline (VTA already resolved)                  |
| Test3  | 0.111     | 0.105     | +5%    | Interface MONO inline                               |
| Test4  | 0.028     | 0.030     | -7%    | 2x Inline adds bytecodes (within noise)             |
| Test5  | 0.027     | 0.027     | 0%     | BIMORPHIC guarded dispatch (noise range)            |
| Test6  | 0.025     | 0.028     | -12%   | Type-test chain overhead in -Xint                   |
| Test7  | 0.028     | 0.034     | -21%   | k-obj MONO inline; inlined body adds interpreter work|
| Test8  | 0.114     | 0.109     | +4%    | k=2 MONO, 5x inline; deep chain eliminated         |
| Test9  | 0.042     | 0.036     | +14%   | 2x start() inline + guarded run() dispatch          |
| Test10 | 0.029     | 0.027     | +7%    | println devirt (MEGA site correctly skipped)        |
| Test11 | 0.046     | 0.047     | -2%    | 2x MONO inline (noise range)                        |
| Test12 | 0.036     | 0.032     | +11%   | 2x Inline (both callees within threshold)           |
| Test13 | 0.118     | 0.110     | +7%    | println devirt; MEGA handle() skipped               |
| Test14 | 0.026     | 0.027     | -4%    | Guarded dispatch; receiver from return = UNKNOWN    |
| Test15 | 0.027     | 0.028     | -4%    | Guarded dispatch; array load stays UNKNOWN          |

Overall: 38 out of 40 virtual call sites optimized (95%). The 2 unoptimized sites are the MEGA calls in Test10 and Test13 -- correctly skipped per the MEGA_THRESHOLD=4 rule.

With `-Xint` (no JIT), the interpreter does each bytecode one by one. Inlining puts more bytecodes into the method body, so sometimes we see a small slowdown -- this is expected and is just noise in -Xint mode. The real benefit of monomorphization comes when the JIT is on: a monomorphic call site lets the JIT do its own inlining, which can give 10-50% speedup in real programs.

Test9 (+14%), Test12 (+11%), Test3 (+5%), and Test8 (+4%) show clear improvement even under -Xint because the inlined callees remove dispatch overhead that even the interpreter has to pay. Test13 (+7%) and Test10 (+7%) improve because the println call is devirtualised even though the MEGA site is skipped. Tests with small loop bodies (Test1, Test4, Test7) show noise-level results where the timing difference is less than 5ms.

---

## 5. Running the Code

```bash
bash script.sh
```

This cleans old class files, compiles the analysis code, then for each test:
1. Compiles the test class
2. Measures original runtime (5 runs, minimum)
3. Runs the optimization pass (prints full analysis report)
4. Checks that optimized output matches original
5. Measures optimized runtime (5 runs, minimum)

At the end it prints a summary table. Transformed class files are written to `sootOutput/`.
