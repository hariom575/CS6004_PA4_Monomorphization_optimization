import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.scalar.CopyPropagator;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.util.Chain;

import java.util.*;

/**
 * JimpleRewriter — applies monomorphization rewrites to Jimple IR.
 *
 * Four transformations:
 *
 * MONO → INLINE  (callee ≤ INLINE_THRESHOLD stmts, no traps)
 *   Clone callee body, alpha-rename locals, bind @this/@param, handle
 *   multi-return via a shared post-return nop. Incoming gotos are
 *   redirected to the first inlined unit before site.stmt is removed.
 *
 * MONO → DEVIRTUALISE  (callee > threshold or inline failed)
 *   Cast receiver to the concrete type, use virtualinvoke on the
 *   concrete class (JVM-verifier safe). Incoming gotos are redirected
 *   to the cast statement so it is never skipped.
 *
 * BIMORPHIC → GUARDED DISPATCH (2 targets, withFallback=false)
 *   Generated layout:
 *     [entryNop]
 *     $iof_T0 = r instanceof T0; if $iof_T0 != 0 goto arm0;
 *     // fallthrough else arm: direct call to T1 (last target)
 *     $cast_T1 = (T1) r; specialinvoke $cast_T1.<T1: ret m()>(); goto done;
 *     arm0: $cast_T0 = (T0) r; specialinvoke $cast_T0.<T0: ret m()>(); goto done;
 *     done: nop;
 *
 * POLY (3–4 targets, withFallback=true)
 *   Generated layout:
 *     [entryNop]
 *     $iof_T0 = r instanceof T0; if $iof_T0 != 0 goto arm0;
 *     ...
 *     virtualinvoke r.<DeclClass: ret m()>();   // virtual fallback
 *     goto done;
 *     arm0: ...; goto done;
 *     ...
 *     done: nop;
 *
 * KEY CORRECTNESS RULE — the entryNop technique:
 *   When transforming a call site at `site.stmt`, other branches in the
 *   method may hold goto targets pointing DIRECTLY to `site.stmt` (e.g.,
 *   the merge of an if-then-else).  If we insert new code BEFORE
 *   `site.stmt` without updating those targets, those incoming jumps
 *   bypass the new code.  We fix this by:
 *     1. Inserting an `entryNop` before site.stmt.
 *     2. Redirecting ALL incoming jump targets from site.stmt to entryNop.
 *   The entryNop then falls through into the type-test chain or devirt
 *   cast, which correctly precedes the transformed call.
 *
 * MEGA (5+) → no change.
 */
public class JimpleRewriter {

    private final int inlineThreshold;

    private int inlinedCount     = 0;
    private int devirtCount      = 0;
    private int guardedCount     = 0;
    private int typeTestCount    = 0;
    private int skippedMegaCount = 0;

    public JimpleRewriter(int inlineThreshold) {
        this.inlineThreshold = inlineThreshold;
    }

    // ── Public entry point ───────────────────────────────────────────

    public void rewriteAll(List<CallSiteInfo> sites) {
        Map<SootMethod, List<CallSiteInfo>> byMethod = new LinkedHashMap<>();
        for (CallSiteInfo s : sites)
            byMethod.computeIfAbsent(s.containingMethod, k -> new ArrayList<>()).add(s);

        for (var entry : byMethod.entrySet()) {
            SootMethod         method      = entry.getKey();
            List<CallSiteInfo> methodSites = entry.getValue();

            Collections.reverse(methodSites); // reverse so index shifts don't affect earlier sites

            for (CallSiteInfo site : methodSites) {
                try {
                    switch (site.kind) {
                        case MONO      -> rewriteMono(site);
                        case BIMORPHIC -> rewriteBimorphic(site);
                        case POLY      -> rewritePoly(site);
                        case MEGA      -> skippedMegaCount++;
                        default        -> {}
                    }
                } catch (Exception e) {
                    System.err.println("[rewriter] FAILED at " + site + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                }
            }

            if (method.hasActiveBody()) {
                try {
                    Body body = method.getActiveBody();
                    CopyPropagator.v().transform(body);
                    DeadAssignmentEliminator.v().transform(body);
                    body.validate();
                } catch (Exception e) {
                    System.err.println("[rewriter] Cleanup failed for "
                        + method.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // MONO — inline or devirtualise
    // ════════════════════════════════════════════════════════════════

    private void rewriteMono(CallSiteInfo site) {
        SootMethod callee = site.singleTarget();
        if (shouldInline(callee)) {
            if (tryInline(site, callee)) {
                inlinedCount++;
                return;
            }
        }
        devirtualise(site, callee);
        devirtCount++;
    }

    // ── Devirtualisation ────────────────────────────────────────────

    /**
     * Cast receiver to the concrete type, then use virtualinvoke on the
     * concrete class (JVM-verifier safe; no invokespecial on foreign class).
     *
     * Incoming jumps that target site.stmt are redirected to castStmt so
     * the cast is never skipped.
     *
     * Before: virtualinvoke r.<Abstract: void foo()>()
     * After:  $dvt = (Concrete) r
     *         virtualinvoke $dvt.<Concrete: void foo()>()
     */
    private void devirtualise(CallSiteInfo site, SootMethod callee) {
        InvokeExpr oldExpr = site.invoke;
        if (!(oldExpr instanceof InstanceInvokeExpr iie)) return;

        Local receiver = (Local) iie.getBase();
        SootClass concreteClass = callee.getDeclaringClass();
        RefType   concreteType  = RefType.v(concreteClass);

        Body callerBody = site.containingMethod.getActiveBody();
        Chain<Unit> units = callerBody.getUnits();

        Local castLocal = Jimple.v().newLocal(
            "$dvt_" + concreteClass.getShortName() + "_" + devirtCount, concreteType);
        callerBody.getLocals().add(castLocal);

        AssignStmt castStmt = Jimple.v().newAssignStmt(
            castLocal, Jimple.v().newCastExpr(receiver, concreteType));
        units.insertBefore(castStmt, site.stmt);

        // Redirect incoming jumps: they must go to castStmt, not past it to site.stmt
        redirectJumpsTo(site.stmt, castStmt, callerBody);

        VirtualInvokeExpr newExpr = Jimple.v().newVirtualInvokeExpr(
            castLocal, callee.makeRef(), iie.getArgs());
        patchInvokeExpr(site.stmt, newExpr);
    }

    private void patchInvokeExpr(Unit stmt, InvokeExpr newExpr) {
        if (stmt instanceof InvokeStmt is) {
            is.setInvokeExpr(newExpr);
        } else if (stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr) {
            as.setRightOp(newExpr);
        }
    }

    // ── Inlining ─────────────────────────────────────────────────────

    private boolean shouldInline(SootMethod callee) {
        if (!callee.hasActiveBody()) {
            try { callee.retrieveActiveBody(); } catch (Exception e) { return false; }
        }
        if (!callee.hasActiveBody()) return false;
        Body body = callee.getActiveBody();
        return body.getTraps().isEmpty()
            && body.getUnits().size() <= inlineThreshold;
    }

    /**
     * Inline the callee body at the call site.
     *
     * Multi-return support: every ReturnStmt/ReturnVoidStmt in the callee
     * becomes a goto to a shared postReturn NopStmt at the end of the
     * inlined block. This handles callees with multiple exit points.
     *
     * Incoming jump redirect: after inserting inlined units before
     * site.stmt, we redirect all unit-box targets from site.stmt to the
     * first inlined unit, so incoming gotos reach the inlined code rather
     * than being redirected past it by PatchingChain.
     */
    private boolean tryInline(CallSiteInfo site, SootMethod callee) {
        InvokeExpr invoke = site.invoke;
        if (!(invoke instanceof InstanceInvokeExpr iie)) return false;

        Body callerBody = site.containingMethod.getActiveBody();
        Body calleeBody = callee.getActiveBody();
        Chain<Unit> units = callerBody.getUnits();

        // actuals: [0]=receiver, [1..n]=args
        List<Value> actuals = new ArrayList<>();
        actuals.add(iie.getBase());
        actuals.addAll(iie.getArgs());

        Local returnTarget = null;
        if (site.stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr)
            returnTarget = (Local) as.getLeftOp();

        // Alpha-rename: callee local → fresh caller local
        String suffix = "_inl" + inlinedCount;
        Map<Local, Local> renameMap = new HashMap<>();
        for (Local cl : calleeBody.getLocals()) {
            Local fresh = Jimple.v().newLocal(cl.getName() + suffix, cl.getType());
            callerBody.getLocals().add(fresh);
            renameMap.put(cl, fresh);
        }

        // Shared post-return label for multi-return callees
        Unit postReturn = Jimple.v().newNopStmt();

        List<Unit> inlinedUnits = new ArrayList<>();

        for (Unit cu : calleeBody.getUnits()) {
            if (cu instanceof IdentityStmt id) {
                Value rhs = id.getRightOp();
                Local lhs = renameMap.get((Local) id.getLeftOp());
                if (lhs == null) continue;

                Value actual = null;
                if (rhs instanceof ThisRef) {
                    actual = actuals.get(0);
                } else if (rhs instanceof ParameterRef pr) {
                    int idx = pr.getIndex() + 1;
                    if (idx < actuals.size()) actual = actuals.get(idx);
                }
                if (actual != null)
                    inlinedUnits.add(Jimple.v().newAssignStmt(lhs, actual));

            } else if (cu instanceof ReturnStmt ret) {
                if (returnTarget != null) {
                    Value retVal = ret.getOp();
                    if (retVal instanceof Local l && renameMap.containsKey(l))
                        retVal = renameMap.get(l);
                    inlinedUnits.add(Jimple.v().newAssignStmt(returnTarget, retVal));
                }
                inlinedUnits.add(Jimple.v().newGotoStmt(postReturn));

            } else if (cu instanceof ReturnVoidStmt) {
                inlinedUnits.add(Jimple.v().newGotoStmt(postReturn));

            } else {
                Unit cloned = (Unit) cu.clone();
                renameLocals(cloned, renameMap);
                inlinedUnits.add(cloned);
            }
        }
        inlinedUnits.add(postReturn);

        if (inlinedUnits.isEmpty()) return false;

        // Insert inlined units before site.stmt (maintains forward order)
        for (Unit iu : inlinedUnits)
            units.insertBefore(iu, site.stmt);

        // Redirect incoming jumps to the first inlined unit so they are not
        // skipped when site.stmt is removed (PatchingChain would redirect to
        // site.stmt's successor, which is past the inlined code)
        redirectJumpsTo(site.stmt, inlinedUnits.get(0), callerBody);

        units.remove(site.stmt);
        return true;
    }

    private void renameLocals(Unit u, Map<Local, Local> renameMap) {
        for (ValueBox vb : u.getUseAndDefBoxes())
            if (vb.getValue() instanceof Local old && renameMap.containsKey(old))
                vb.setValue(renameMap.get(old));
    }

    // ════════════════════════════════════════════════════════════════
    // BIMORPHIC — guarded dispatch (2 targets)
    // ════════════════════════════════════════════════════════════════

    private void rewriteBimorphic(CallSiteInfo site) {
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, false);
        guardedCount++;
    }

    // ════════════════════════════════════════════════════════════════
    // POLY — type-test chain (3–MEGA_THRESHOLD targets)
    // ════════════════════════════════════════════════════════════════

    private void rewritePoly(CallSiteInfo site) {
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, true);
        typeTestCount++;
    }

    // ── Type-test chain builder ──────────────────────────────────────

    /**
     * Build a type-test dispatch chain in the Jimple unit chain.
     *
     * For !withFallback (BIMORPHIC, targets=[T0, T1]):
     *
     *   entryNop:                         ← all incoming jumps redirected here
     *   $iof_T0_0 = r instanceof T0;
     *   if $iof_T0_0 != 0 goto arm0;
     *   $cast_T1 = (T1) r;               ← else arm (T1, last target)
     *   specialinvoke $cast_T1.<T1: ret m()>(args);
     *   goto done;
     *   arm0: $cast_T0 = (T0) r;         ← jumped-to arm (T0)
     *   specialinvoke $cast_T0.<T0: ret m()>(args);
     *   goto done;
     *   done: nop;
     *
     * For withFallback (POLY, targets=[T0, T1, T2]):
     *
     *   entryNop:
     *   $iof_T0_0 = r instanceof T0;  if $iof_T0_0 != 0 goto arm0;
     *   $iof_T1_1 = r instanceof T1;  if $iof_T1_1 != 0 goto arm1;
     *   $iof_T2_2 = r instanceof T2;  if $iof_T2_2 != 0 goto arm2;
     *   virtualinvoke r.<Decl: ret m()>(args);  ← virtual fallback
     *   goto done;
     *   arm0: ...; goto done;
     *   arm1: ...; goto done;
     *   arm2: ...; goto done;
     *   done: nop;
     *
     * ARM UNIT ORDER (the critical fix):
     *   - The else arm / fallback comes first (fallthrough from all failed tests).
     *   - The jumped-to arms come AFTER the fallthrough, reached via if-goto.
     *   - Within each arm, units are inserted in FORWARD order: cast BEFORE call.
     *
     * JUMP REDIRECT (the other critical fix):
     *   An entryNop is inserted before site.stmt and all incoming jump
     *   targets on site.stmt are redirected to entryNop.  This ensures
     *   every incoming jump reaches the type tests, not the fallthrough arm.
     */
    private void buildTypeTestChain(CallSiteInfo site,
                                     List<SootMethod> targets,
                                     boolean withFallback) {
        InvokeExpr invoke = site.invoke;
        if (!(invoke instanceof InstanceInvokeExpr iie)) return;
        Local receiver = (Local) iie.getBase();
        List<Value> origArgs = iie.getArgs();

        Body callerBody = site.containingMethod.getActiveBody();
        Chain<Unit> units = callerBody.getUnits();

        Local returnTarget = null;
        if (site.stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr)
            returnTarget = (Local) as.getLeftOp();

        // ── Entry nop: takes over site.stmt's role as jump target ────────
        // Inserted before site.stmt; all incoming jumps redirected here.
        // The type tests follow entryNop, so they are always executed.
        Unit entryNop = Jimple.v().newNopStmt();
        units.insertBefore(entryNop, site.stmt);
        redirectJumpsTo(site.stmt, entryNop, callerBody);

        // ── Done nop: convergence point for all arms ──────────────────────
        Unit doneNop = Jimple.v().newNopStmt();
        units.insertAfter(doneNop, site.stmt);

        // For BIMORPHIC: last target is the fallthrough "else" arm.
        // For POLY: all targets get type tests; site.stmt is the fallback.
        int numTests = withFallback ? targets.size() : targets.size() - 1;

        // ── Step 1: Insert the fallthrough / else section ────────────────
        // This code is reached when all type tests fail (fallthrough path).
        // It is placed BEFORE the jumped-to arms in the unit chain.
        if (!withFallback) {
            SootMethod lastTarget = targets.get(targets.size() - 1);
            List<Unit> elseArm = buildDirectCallArm(
                lastTarget, receiver, origArgs, returnTarget, callerBody);
            // Forward order: cast then call
            for (Unit u : elseArm)
                units.insertBefore(u, doneNop);
            // goto done so fallthrough does not enter the jumped-to arms
            units.insertBefore(Jimple.v().newGotoStmt(doneNop), doneNop);
        }
        // For withFallback: site.stmt is the fallback and stays where it is
        // (between the type tests and the jumped-to arms).

        // ── Step 2: Insert jumped-to arms (one per type test) ────────────
        // Placed AFTER the fallthrough/else section, before doneNop.
        // Forward order so arm0 appears first.
        List<Unit> armFirstUnits = new ArrayList<>();
        for (int i = 0; i < numTests; i++) {
            SootMethod target = targets.get(i);
            List<Unit> arm = buildDirectCallArm(
                target, receiver, origArgs, returnTarget, callerBody);
            armFirstUnits.add(arm.get(0));   // record first unit BEFORE insertion
            for (Unit u : arm)
                units.insertBefore(u, doneNop);
            units.insertBefore(Jimple.v().newGotoStmt(doneNop), doneNop);
        }

        // ── Step 3: Insert type tests between entryNop and site.stmt ─────
        // Insert in REVERSE order so targets[0]'s test is first in code.
        for (int i = numTests - 1; i >= 0; i--) {
            SootMethod target   = targets.get(i);
            SootClass  tgtClass = target.getDeclaringClass();
            RefType    castType = RefType.v(tgtClass);

            Local iofLocal = Jimple.v().newLocal(
                "$iof_" + tgtClass.getShortName() + "_" + i, BooleanType.v());
            callerBody.getLocals().add(iofLocal);

            AssignStmt iofStmt = Jimple.v().newAssignStmt(
                iofLocal, Jimple.v().newInstanceOfExpr(receiver, castType));
            IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(iofLocal, IntConstant.v(0)), armFirstUnits.get(i));

            // Insert between entryNop and site.stmt (using site.stmt as anchor)
            units.insertBefore(iofStmt, site.stmt);
            units.insertBefore(ifStmt,  site.stmt);
        }

        // ── Step 4: Clean up original call ───────────────────────────────
        if (!withFallback) {
            // All incoming jumps were redirected to entryNop; safe to remove
            units.remove(site.stmt);
        } else {
            // site.stmt serves as the virtual fallback.
            // Insert goto done AFTER site.stmt so control doesn't fall through
            // into the jumped-to arms.
            units.insertAfter(Jimple.v().newGotoStmt(doneNop), site.stmt);
        }
    }

    /**
     * Build one direct-call arm:
     *   $cast_T = (T) receiver;
     *   [lhs =] specialinvoke $cast_T.<T: ret m()>(args);
     *
     * specialinvoke bypasses the vtable. Safe because the preceding instanceof
     * check guarantees the runtime type is exactly T (or a subtype), and our
     * analysis proved T is the only possible dispatch target.
     *
     * Units are returned in FORWARD order (cast first, then call).
     */
    private List<Unit> buildDirectCallArm(SootMethod target, Local receiver,
                                           List<Value> origArgs, Local returnTarget,
                                           Body callerBody) {
        List<Unit> arm = new ArrayList<>();
        SootClass  cls      = target.getDeclaringClass();
        RefType    castType = RefType.v(cls);

        Local castLocal = Jimple.v().newLocal(
            "$cast_" + cls.getShortName() + "_" + System.nanoTime(), castType);
        callerBody.getLocals().add(castLocal);

        // cast FIRST (index 0)
        arm.add(Jimple.v().newAssignStmt(
            castLocal, Jimple.v().newCastExpr(receiver, castType)));

        // call SECOND (index 1)
        SpecialInvokeExpr directCall = Jimple.v().newSpecialInvokeExpr(
            castLocal, target.makeRef(), origArgs);
        arm.add(returnTarget != null
            ? Jimple.v().newAssignStmt(returnTarget, directCall)
            : Jimple.v().newInvokeStmt(directCall));

        return arm;
    }

    // ── Shared helper ────────────────────────────────────────────────

    /**
     * Redirect every jump target that currently points to {@code oldTarget}
     * to point to {@code newTarget} instead.
     *
     * This must be done BEFORE inserting new code before oldTarget, because
     * Soot's PatchingChain only redirects targets when a unit is REMOVED
     * (to its successor), not when new units are inserted before it.
     * Without this redirect, incoming jumps bypass newly inserted code.
     */
    private void redirectJumpsTo(Unit oldTarget, Unit newTarget, Body body) {
        for (Unit u : body.getUnits()) {
            for (UnitBox ub : u.getUnitBoxes()) {
                if (ub.getUnit() == oldTarget)
                    ub.setUnit(newTarget);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Summary report
    // ════════════════════════════════════════════════════════════════

    public void printRewriteSummary() {
        System.out.println("\n── Rewrite summary (Jimple transformations applied) ────────");
        System.out.printf("  Inlined            : %3d  (callee body pasted in, vtable gone)%n",
            inlinedCount);
        System.out.printf("  Devirtualised      : %3d  (virtualinvoke → concrete-class virtualinvoke)%n",
            devirtCount);
        System.out.printf("  Guarded dispatch   : %3d  (instanceof + 2 direct calls)%n",
            guardedCount);
        System.out.printf("  Type-test chain    : %3d  (instanceof chain + direct calls)%n",
            typeTestCount);
        System.out.printf("  Skipped (MEGA)     : %3d%n", skippedMegaCount);
        System.out.printf("  Total transformed  : %3d%n",
            inlinedCount + devirtCount + guardedCount + typeTestCount);
    }
}
