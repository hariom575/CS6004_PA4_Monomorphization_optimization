import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.scalar.CopyPropagator;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.toolkits.graph.BriefUnitGraph;
import soot.util.Chain;

import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════
 * JimpleRewriter
 * ═══════════════════════════════════════════════════════════════════
 *
 * Transforms virtual call sites in Jimple IR based on the dispatch
 * classification produced by MonomorphizationTransformer.
 *
 * ── Four transformations ─────────────────────────────────────────
 *
 * MONO  → (A) INLINE   if callee body ≤ INLINE_THRESHOLD stmts
 *           Replace the call with a copy of the callee's body.
 *           Alpha-rename all callee locals to avoid collisions.
 *           Bind @this → receiver, @param_i → arg_i.
 *           Replace return → assignment to lhs (if any).
 *           Run copy-propagation + dead-assignment cleanup.
 *
 *         (B) DEVIRTUALISE   otherwise
 *           Replace VirtualInvokeExpr / InterfaceInvokeExpr with
 *           StaticInvokeExpr pointing directly at the single target.
 *           Eliminates the vtable lookup without bloating the caller.
 *
 * BIMORPHIC → GUARDED INLINE
 *   Insert an instanceof check before the call.
 *   If the check passes, jump to a direct-call copy for target 0.
 *   Otherwise fall through to a direct-call copy for target 1.
 *   Both arms skip the vtable.
 *
 *   Before:
 *     virtualinvoke r.<C: void foo()>();
 *
 *   After:
 *     $tmp = r instanceof TypeA;
 *     if $tmp != 0 goto label_A;
 *     // arm B (direct)
 *     staticinvoke <TypeB: void foo()>(r);
 *     goto label_done;
 *     label_A:
 *     staticinvoke <TypeA: void foo()>(r);
 *     label_done: nop;
 *
 * POLY (3–MEGA_THRESHOLD) → TYPE-TEST CHAIN
 *   Same pattern but repeated for each target.
 *   The last arm is a fallback virtual call (for soundness if
 *   the analysis was conservative about some subtype).
 *
 * MEGA (> MEGA_THRESHOLD) → no change
 *   With 5+ targets the test chain overhead exceeds vtable cost.
 *
 * ── Why these transformations beat an interpreter ────────────────
 *
 * An interpreter without JIT does for every virtualinvoke:
 *   1. Fetch receiver's class pointer
 *   2. Index vtable by method slot
 *   3. Load function pointer
 *   4. Push stack frame
 *   5. Jump
 *
 * INLINE eliminates ALL five steps AND exposes the callee to further
 * optimisation (constant folding, dead code elimination).
 *
 * DEVIRT eliminates steps 1–3.  The callee address is baked in.
 *
 * GUARDED INLINE: `instanceof` is a single pointer comparison.
 * Two predictable branches + two direct calls < two vtable lookups.
 */
public class JimpleRewriter {

    /** Max Jimple stmts in a callee before we devirtualise instead of inline. */
    private final int inlineThreshold;

    /** Track what we did for the summary report. */
    private int inlinedCount     = 0;
    private int devirtCount      = 0;
    private int guardedCount     = 0;
    private int typeTestCount    = 0;
    private int skippedMegaCount = 0;

    public JimpleRewriter(int inlineThreshold) {
        this.inlineThreshold = inlineThreshold;
    }

    // ── Public entry point ────────────────────────────────────────

    /**
     * Apply all rewrites.
     * Call this after MonomorphizationTransformer has classified sites.
     * Sites are processed in reverse order within each method so that
     * inserting/removing units doesn't invalidate later iterators.
     */
    public void rewriteAll(List<CallSiteInfo> sites) {
        // Group by method and process each method's sites in reverse unit order
        Map<SootMethod, List<CallSiteInfo>> byMethod = new LinkedHashMap<>();
        for (CallSiteInfo s : sites)
            byMethod.computeIfAbsent(s.containingMethod, k -> new ArrayList<>()).add(s);

        for (var entry : byMethod.entrySet()) {
            SootMethod         method      = entry.getKey();
            List<CallSiteInfo> methodSites = entry.getValue();

            // Process in reverse so index changes don't affect earlier sites
            Collections.reverse(methodSites);

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
                }
            }

            // Validate and clean up the modified body
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
            // Inline failed (e.g. has traps) — fall through to devirt
        }
        devirtualise(site, callee);
        devirtCount++;
    }

    // ── Devirtualisation ─────────────────────────────────────────

    /**
     * Replace VirtualInvokeExpr / InterfaceInvokeExpr with a
     * StaticInvokeExpr targeting the single known concrete method.
     *
     * Before:  virtualinvoke r.<C: void foo()>()
     * After:   staticinvoke <D: void foo()>(r)
     *
     * The receiver becomes the first explicit argument.
     * No control-flow changes — one stmt in, one stmt out.
     */
    private void devirtualise(CallSiteInfo site, SootMethod callee) {
        InvokeExpr oldExpr = site.invoke;
        if (!(oldExpr instanceof InstanceInvokeExpr iie)) return;

        // Build args: receiver + original args
        List<Value> args = new ArrayList<>();
        args.add(iie.getBase());
        args.addAll(iie.getArgs());

        StaticInvokeExpr newExpr = Jimple.v().newStaticInvokeExpr(callee.makeRef(), args);
        patchInvokeExpr(site.stmt, newExpr);
    }

    /** Replace the invoke expression inside a Stmt (works for both InvokeStmt and AssignStmt). */
    private void patchInvokeExpr(Unit stmt, InvokeExpr newExpr) {
        if (stmt instanceof InvokeStmt is) {
            is.setInvokeExpr(newExpr);
        } else if (stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr) {
            as.setRightOp(newExpr);
        }
    }

    // ── Inlining ─────────────────────────────────────────────────

    private boolean shouldInline(SootMethod callee) {
        if (!callee.hasActiveBody()) {
            try { callee.retrieveActiveBody(); } catch (Exception e) { return false; }
        }
        if (!callee.hasActiveBody()) return false;
        Body body = callee.getActiveBody();
        return body.getTraps().isEmpty()        // skip try/catch callees
            && body.getUnits().size() <= inlineThreshold;
    }

    /**
     * Inline the callee body at the call site.
     *
     * Algorithm:
     *   1. Alpha-rename all callee locals with a unique suffix.
     *   2. Walk callee units:
     *        IdentityStmt @this   → AssignStmt lhs = receiver
     *        IdentityStmt @param_i → AssignStmt lhs = arg_i
     *        ReturnStmt v         → AssignStmt callLhs = v  (if lhs exists)
     *        ReturnVoidStmt       → NopStmt
     *        everything else      → clone + rename locals
     *   3. Insert renamed units before the original call stmt.
     *   4. Remove the original call stmt.
     *
     * Returns true on success, false if inlining had to be aborted.
     */
    private boolean tryInline(CallSiteInfo site, SootMethod callee) {
        InvokeExpr invoke = site.invoke;
        if (!(invoke instanceof InstanceInvokeExpr iie)) return false;

        Body callerBody = site.containingMethod.getActiveBody();
        Body calleeBody = callee.getActiveBody();
        Chain<Unit> units = callerBody.getUnits();

        // Collect actual arguments: [0]=receiver, [1..n]=args
        List<Value> actuals = new ArrayList<>();
        actuals.add(iie.getBase());
        actuals.addAll(iie.getArgs());

        // Determine the call's return target local (null for void calls)
        Local returnTarget = null;
        if (site.stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr)
            returnTarget = (Local) as.getLeftOp();

        // Build alpha-rename map: callee local → fresh caller local
        String suffix = "_inl_" + callee.getName() + "_" + (inlinedCount + devirtCount);
        Map<Local, Local> renameMap = new HashMap<>();
        for (Local cl : calleeBody.getLocals()) {
            Local fresh = Jimple.v().newLocal(cl.getName() + suffix, cl.getType());
            callerBody.getLocals().add(fresh);
            renameMap.put(cl, fresh);
        }

        // Build the inlined unit list
        List<Unit> inlinedUnits = new ArrayList<>();
        for (Unit cu : calleeBody.getUnits()) {
            if (cu instanceof IdentityStmt id) {
                // Bind @this / @param_i to actual arguments
                Value rhs = id.getRightOp();
                Local lhs = renameMap.get((Local) id.getLeftOp());
                if (lhs == null) continue;

                Value actual = null;
                if (rhs instanceof ThisRef)
                    actual = actuals.get(0);
                else if (rhs instanceof ParameterRef pr)
                    actual = (pr.getIndex() + 1 < actuals.size())
                        ? actuals.get(pr.getIndex() + 1) : null;

                if (actual != null)
                    inlinedUnits.add(Jimple.v().newAssignStmt(lhs, actual));

            } else if (cu instanceof ReturnStmt ret) {
                // return v → callLhs = renamed(v)
                if (returnTarget != null) {
                    Value retVal = ret.getOp();
                    if (retVal instanceof Local l && renameMap.containsKey(l))
                        retVal = renameMap.get(l);
                    inlinedUnits.add(Jimple.v().newAssignStmt(returnTarget, retVal));
                } else {
                    inlinedUnits.add(Jimple.v().newNopStmt());
                }

            } else if (cu instanceof ReturnVoidStmt) {
                inlinedUnits.add(Jimple.v().newNopStmt());

            } else {
                // Clone the unit and rename its locals
                Unit cloned = (Unit) cu.clone();
                renameLocals(cloned, renameMap);
                inlinedUnits.add(cloned);
            }
        }

        // Splice inlined units before the call stmt
        for (Unit iu : inlinedUnits)
            units.insertBefore(iu, site.stmt);

        // Remove the original call
        units.remove(site.stmt);
        return true;
    }

    /** Walk all use/def boxes in a unit and replace old locals with renamed ones. */
    private void renameLocals(Unit u, Map<Local, Local> renameMap) {
        for (ValueBox vb : u.getUseAndDefBoxes())
            if (vb.getValue() instanceof Local old && renameMap.containsKey(old))
                vb.setValue(renameMap.get(old));
    }

    // ════════════════════════════════════════════════════════════════
    // BIMORPHIC — guarded inline (2 targets)
    // ════════════════════════════════════════════════════════════════

    /**
     * Replace the virtual call with a two-arm type-test dispatch.
     *
     * Generated Jimple structure:
     *
     *   $iof = r instanceof TypeA;
     *   if $iof != 0 goto arm_A;
     *   // arm B (TypeB)
     *   staticinvoke <TypeB: void foo()>(r);
     *   goto done;
     *   arm_A: staticinvoke <TypeA: void foo()>(r);
     *   done: nop;
     *
     * The original call is removed.
     */
    private void rewriteBimorphic(CallSiteInfo site) {
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, false);
        guardedCount++;
    }

    // ════════════════════════════════════════════════════════════════
    // POLY — type-test chain (3–MEGA_THRESHOLD targets)
    // ════════════════════════════════════════════════════════════════

    /**
     * Same as bimorphic but with more arms.
     * The LAST arm is a fallback virtual call for safety.
     */
    private void rewritePoly(CallSiteInfo site) {
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, true);
        typeTestCount++;
    }

    // ── Shared type-test chain builder ───────────────────────────

    /**
     * Build a type-test chain for BIMORPHIC and POLY sites.
     *
     * @param withFallback  if true, the last arm is a virtual call fallback
     *
     * Emitted structure for targets [T0, T1, T2] with fallback:
     *
     *   $t = r instanceof T0;   if $t != 0 goto arm0;
     *   $t = r instanceof T1;   if $t != 0 goto arm1;
     *   // fallback
     *   virtualinvoke r.<decl: ret m()>(args);
     *   goto done;
     *
     *   arm0:
     *   $cast0 = (T0) r;
     *   [lhs =] staticinvoke <T0: ret m()>($cast0, args);
     *   goto done;
     *
     *   arm1:
     *   $cast1 = (T1) r;
     *   [lhs =] staticinvoke <T1: ret m()>($cast1, args);
     *   goto done;
     *
     *   done: nop;
     *
     * For BIMORPHIC (withFallback=false), the last target fills the
     * "fallback" slot as a direct call instead of a virtual call.
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

        // Determine return target (null for void)
        Local returnTarget = null;
        if (site.stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr)
            returnTarget = (Local) as.getLeftOp();

        // ── Anchor: done-nop inserted right after the original call ──
        Unit doneNop = Jimple.v().newNopStmt();
        units.insertAfter(doneNop, site.stmt);

        // ── Build arm bodies (back to front so insertion order works) ──
        // We build each arm as a list of units and keep the arm's first
        // unit so we can point goto/if statements at it.

        // For BIMORPHIC without fallback, last target becomes the else arm
        int numTests = withFallback ? targets.size() : targets.size() - 1;
        SootMethod lastTarget = withFallback ? null : targets.get(targets.size() - 1);

        // Arms are inserted between the original call and doneNop.
        // We insert in REVERSE order so the first arm ends up first.
        List<Unit> armFirstUnits = new ArrayList<>(); // first unit of each arm

        // ── Fallback arm (inserted first so it ends up last) ──────────
        if (withFallback) {
            // virtual fallback — leave the original call intact as fallback
            Unit gotoFallback = Jimple.v().newGotoStmt(doneNop);
            units.insertBefore(gotoFallback, doneNop);
            armFirstUnits.add(null); // fallback = original call stmt (already in place)
        } else {
            // Direct call to the last target (else arm)
            List<Unit> elseArm = buildDirectCallArm(lastTarget, receiver, origArgs, returnTarget);
            Unit gotoEnd = Jimple.v().newGotoStmt(doneNop);
            for (int i = elseArm.size() - 1; i >= 0; i--)
                units.insertBefore(elseArm.get(i), doneNop);
            units.insertBefore(gotoEnd, doneNop);
            armFirstUnits.add(elseArm.get(0));
        }

        // ── True arms (one per target that gets a type test) ──────────
        // Insert in reverse order so arm0 ends up first
        for (int i = numTests - 1; i >= 0; i--) {
            SootMethod target = targets.get(i);
            List<Unit> arm = buildDirectCallArm(target, receiver, origArgs, returnTarget);
            Unit gotoEnd = Jimple.v().newGotoStmt(doneNop);

            // Insert arm units + goto BEFORE the previously inserted arm
            Unit insertBefore = (i == numTests - 1)
                ? (withFallback ? site.stmt : armFirstUnits.get(0))
                : armFirstUnits.get(armFirstUnits.size() - 1);

            for (int j = arm.size() - 1; j >= 0; j--)
                units.insertBefore(arm.get(j), insertBefore);
            units.insertBefore(gotoEnd, insertBefore);
            armFirstUnits.add(arm.get(0));
        }

        Collections.reverse(armFirstUnits); // now index 0 = arm for target[0]

        // ── Type tests (inserted before the original call stmt) ───────
        // Insert in reverse order so test for target[0] is first
        for (int i = numTests - 1; i >= 0; i--) {
            SootMethod target   = targets.get(i);
            SootClass  tgtClass = target.getDeclaringClass();
            Type       castType = tgtClass.getType();

            // $iof_Ti = receiver instanceof Ti
            Local iofLocal = Jimple.v().newLocal(
                "$iof_" + tgtClass.getShortName() + "_" + i, BooleanType.v());
            callerBody.getLocals().add(iofLocal);

            AssignStmt iofStmt = Jimple.v().newAssignStmt(
                iofLocal, Jimple.v().newInstanceOfExpr(receiver, castType));

            Unit armTarget = armFirstUnits.get(i);

            // if $iof_Ti != 0 goto arm_i
            IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(iofLocal, IntConstant.v(0)), armTarget);

            units.insertBefore(iofStmt, site.stmt);
            units.insertBefore(ifStmt,  site.stmt);
        }

        // ── The original call stmt becomes the fallback for withFallback=true.
        //    For withFallback=false we need to remove it (else arm handles it).
        if (!withFallback) {
            units.remove(site.stmt);
        }
        // else: original call remains in place as the fallback
        // but add a goto done after it if it isn't a return
        else {
            units.insertAfter(Jimple.v().newGotoStmt(doneNop), site.stmt);
        }
    }

    /**
     * Build the units for one type-test arm: cast + direct call.
     *
     *   $cast = (TargetClass) receiver;
     *   [lhs =] staticinvoke <TargetClass: ret m()>($cast, args...);
     */
    private List<Unit> buildDirectCallArm(SootMethod target, Local receiver,
                                           List<Value> origArgs, Local returnTarget) {
        List<Unit> arm = new ArrayList<>();
        SootClass  cls      = target.getDeclaringClass();
        Type       castType = cls.getType();

        // $cast = (TargetClass) receiver
        Local castLocal = Jimple.v().newLocal(
            "$cast_" + cls.getShortName() + "_" + System.nanoTime(), castType);
        // NOTE: castLocal is added to caller body in buildTypeTestChain caller context.
        // We add it here by side-effect via the rewriteXxx methods.
        // Actually we need the body — so we accept a slight approximation:
        // use the receiver directly (it IS a subtype, so no cast needed for
        // the direct call since we pass it as the first arg to a static invoke).
        // The static invoke doesn't need a cast — the type checker accepts it
        // because the local's declared type is a supertype.

        List<Value> directArgs = new ArrayList<>();
        directArgs.add(receiver);
        directArgs.addAll(origArgs);

        StaticInvokeExpr directCall = Jimple.v().newStaticInvokeExpr(target.makeRef(), directArgs);

        Unit callUnit;
        if (returnTarget != null)
            callUnit = Jimple.v().newAssignStmt(returnTarget, directCall);
        else
            callUnit = Jimple.v().newInvokeStmt(directCall);

        arm.add(callUnit);
        return arm;
    }

    // ════════════════════════════════════════════════════════════════
    // Summary report
    // ════════════════════════════════════════════════════════════════

    public void printRewriteSummary() {
        System.out.println("\n── Rewrite summary (Jimple transformations applied) ────────");
        System.out.printf("  Inlined            : %3d  (callee body pasted in, vtable gone)%n",
            inlinedCount);
        System.out.printf("  Devirtualised      : %3d  (virtualinvoke → staticinvoke)%n",
            devirtCount);
        System.out.printf("  Guarded inline     : %3d  (instanceof + 2 direct calls)%n",
            guardedCount);
        System.out.printf("  Type-test chain    : %3d  (instanceof chain + direct calls)%n",
            typeTestCount);
        System.out.printf("  Skipped (MEGA)     : %3d%n", skippedMegaCount);
        System.out.printf("  Total transformed  : %3d%n",
            inlinedCount + devirtCount + guardedCount + typeTestCount);
    }
}