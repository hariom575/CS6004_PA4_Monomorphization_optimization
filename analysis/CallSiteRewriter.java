package com.mono.rewriter;

import com.mono.util.AnalysisStats;
import com.mono.util.CallSiteInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.scalar.CopyPropagator;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.util.Chain;

import java.util.*;

/**
 * ═══════════════════════════════════════════════════════════════════
 * STEP 4 + 5 — Call-Site Rewriter
 * ═══════════════════════════════════════════════════════════════════
 *
 * Transforms call sites according to their dispatch classification:
 *
 * ── MONO ──────────────────────────────────────────────────────────
 *
 *   Exactly one concrete target.  Two transformations are possible:
 *
 *   A) DEVIRTUALISATION (always safe for mono):
 *      Replace VirtualInvokeExpr / InterfaceInvokeExpr with a
 *      StaticInvokeExpr to the single known target.
 *      The JIT can then inline and optimise without needing a
 *      runtime type guard.
 *
 *   B) INLINING (if the callee body is small enough):
 *      Replace the call with a copy of the callee's Jimple body,
 *      alpha-renaming locals to avoid collisions.
 *      This eliminates the call overhead entirely and exposes
 *      further optimisation opportunities (constant folding, etc.).
 *
 *   We use inlining only when the callee has ≤ INLINE_THRESHOLD stmts.
 *
 * ── POLY ──────────────────────────────────────────────────────────
 *
 *   2–MEGA_THRESHOLD concrete targets.  We apply a GUARDED INLINE
 *   (also called "type-test chain" or "inline cache"):
 *
 *   Original:
 *     r.speak();
 *
 *   Transformed (2 targets: Dog, Cat):
 *     if (r instanceof Dog) {
 *         ((Dog) r).speak();   // direct call → JIT can inline
 *     } else if (r instanceof Cat) {
 *         ((Cat) r).speak();
 *     } else {
 *         r.speak();           // slow-path fallback (shouldn't happen
 *                              // if analysis is sound, but needed for
 *                              // safety / deopt)
 *     }
 *
 *   This lets the JIT inline the fast paths and profile-guide further
 *   optimisation, while the fallback ensures correctness.
 *
 * ── MEGA ──────────────────────────────────────────────────────────
 *
 *   More than MEGA_THRESHOLD targets.  No profitable transformation;
 *   the virtual dispatch is left unchanged.  We log a note for the
 *   developer (megamorphic sites are often architecture problems).
 *
 * ═══════════════════════════════════════════════════════════════════
 */
public class CallSiteRewriter {

    private static final Logger log =
        LoggerFactory.getLogger(CallSiteRewriter.class);

    /**
     * Maximum number of Jimple statements in a callee body before we
     * fall back from inlining to devirtualisation only.
     */
    private static final int INLINE_THRESHOLD = 30;

    private final AnalysisStats stats;

    public CallSiteRewriter(AnalysisStats stats) {
        this.stats = stats;
    }

    // ---------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------

    /**
     * Rewrite all call sites in the given list.
     * Sites are processed in the order they appear in the list;
     * already-rewritten sites are skipped.
     */
    public void rewriteAll(List<CallSiteInfo> sites) {
        for (CallSiteInfo site : sites) {
            if (site.isRewritten()) continue;
            switch (site.getDispatchKind()) {
                case MONO -> rewriteMono(site);
                case POLY -> rewritePoly(site);
                case MEGA -> logMega(site);
                default   -> { /* UNKNOWN – no targets, skip */ }
            }
        }
        log.info("[Rewriter] {}", stats);
    }

    // ---------------------------------------------------------------
    // MONO rewriting
    // ---------------------------------------------------------------

    /**
     * Monomorphic site → devirtualise, then inline if callee is small.
     *
     * Devirtualisation replaces:
     *   virtualinvoke r.<T: void m()>()
     * with:
     *   staticinvoke <ConcreteType: void m()>()
     *
     * After devirtualisation, if the callee body fits within
     * INLINE_THRESHOLD statements, we inline the body directly.
     */
    private void rewriteMono(CallSiteInfo site) {
        SootMethod callee = site.getSingleTarget();

        log.debug("[Rewriter][MONO] {} → {}",
                  site.getInvokeExpr().getMethod().getSubSignature(),
                  callee.getSignature());

        // ── Step A: Devirtualise ──────────────────────────────────
        boolean devirtualised = devirtualise(site, callee);
        if (devirtualised) stats.devirtualised.incrementAndGet();

        // ── Step B: Try to inline ─────────────────────────────────
        if (shouldInline(callee)) {
            boolean inlined = inlineCallee(site, callee);
            if (inlined) stats.inlined.incrementAndGet();
        }

        site.markRewritten();
        stats.recordClassification(CallSiteInfo.DispatchKind.MONO);
    }

    // ---------------------------------------------------------------
    // Devirtualisation implementation
    // ---------------------------------------------------------------

    /**
     * Replaces a virtual/interface invoke with a static invoke to the
     * single concrete target.
     *
     * Implementation notes:
     *   • We must replace the InvokeExpr inside the Stmt, not the
     *     stmt itself, because the stmt may be an AssignStmt or
     *     InvokeStmt.
     *   • A StaticInvokeExpr does not carry a receiver — the receiver
     *     becomes an explicit argument at position 0.
     *   • We must call method.setModifiers() to ensure the concrete
     *     method is accessible (or add a trampoline).
     */
    private boolean devirtualise(CallSiteInfo site, SootMethod callee) {
        try {
            InvokeExpr oldInvoke = site.getInvokeExpr();
            if (!(oldInvoke instanceof InstanceInvokeExpr iie)) return false;

            // Build new arg list: receiver + original args
            List<Value> newArgs = new ArrayList<>();
            newArgs.add(iie.getBase());
            newArgs.addAll(iie.getArgs());

            // Create the static invoke expression
            StaticInvokeExpr staticInvoke =
                Jimple.v().newStaticInvokeExpr(callee.makeRef(), newArgs);

            // Patch the statement
            patchInvokeExpr(site, staticInvoke);

            log.debug("[Devirt] {} → static {}",
                      oldInvoke.getMethod().getSubSignature(),
                      callee.getSubSignature());
            return true;

        } catch (Exception e) {
            log.warn("[Devirt] Failed for {}: {}", site, e.getMessage());
            return false;
        }
    }

    /**
     * Replaces the InvokeExpr inside the statement with {@code newExpr}.
     *
     * Soot statements are:
     *   InvokeStmt   – the invoke is the entire stmt
     *   AssignStmt   – the invoke is the right-hand side
     */
    private void patchInvokeExpr(CallSiteInfo site, InvokeExpr newExpr) {
        Unit stmt = site.getStatement();
        if (stmt instanceof InvokeStmt is) {
            is.setInvokeExpr(newExpr);
        } else if (stmt instanceof AssignStmt as && as.getRightOp() instanceof InvokeExpr) {
            as.setRightOp(newExpr);
        }
    }

    // ---------------------------------------------------------------
    // Inlining implementation
    // ---------------------------------------------------------------

    private boolean shouldInline(SootMethod callee) {
        if (!callee.hasActiveBody()) {
            try { callee.retrieveActiveBody(); }
            catch (Exception e) { return false; }
        }
        int stmtCount = callee.getActiveBody().getUnits().size();
        return stmtCount <= INLINE_THRESHOLD;
    }

    /**
     * Inlines the callee body at the call site.
     *
     * Steps:
     *   1. Alpha-rename all locals in the callee copy to avoid
     *      name clashes with the caller's locals.
     *   2. Replace @this and @parameter refs with actual arguments.
     *   3. Replace @return refs with the assignment target (if any).
     *   4. Insert the renamed body before the call stmt.
     *   5. Remove the original call stmt.
     *   6. Run copy-propagation + dead-assignment elimination to
     *      clean up the inlined code.
     *
     * Limitation: we skip inlining if the callee contains exception
     * handlers (try/catch) – this is a common conservative simplification.
     */
    private boolean inlineCallee(CallSiteInfo site, SootMethod callee) {
        try {
            Body calleeBody = callee.getActiveBody();

            // Skip if callee has exception traps
            if (!calleeBody.getTraps().isEmpty()) {
                log.debug("[Inline] Skipping {} – has exception traps.",
                          callee.getSubSignature());
                return false;
            }

            SootMethod caller = site.getContainingMethod();
            Body callerBody   = caller.getActiveBody();
            Chain<Unit> units = callerBody.getUnits();
            Unit callStmt     = site.getStatement();

            // ── Build rename map ──────────────────────────────────
            // All callee locals get a fresh unique suffix
            String suffix = "__inlined_" + callee.getName() + "_"
                          + System.nanoTime();
            Map<Local, Local> localMap = new LinkedHashMap<>();

            for (Local calleeLocal : calleeBody.getLocals()) {
                Local renamed = Jimple.v().newLocal(
                    calleeLocal.getName() + suffix,
                    calleeLocal.getType());
                callerBody.getLocals().add(renamed);
                localMap.put(calleeLocal, renamed);
            }

            // ── Identify actual arguments ─────────────────────────
            InvokeExpr invoke = site.getInvokeExpr();
            List<Value> actuals = new ArrayList<>();
            if (invoke instanceof InstanceInvokeExpr iie)
                actuals.add(iie.getBase()); // receiver at position 0
            actuals.addAll(invoke.getArgs());

            // ── Identify return target ────────────────────────────
            Local returnTarget = null;
            if (callStmt instanceof AssignStmt as &&
                as.getRightOp() instanceof InvokeExpr) {
                returnTarget = (Local) as.getLeftOp();
            }

            // ── Copy and rewrite callee statements ────────────────
            List<Unit> inlinedUnits = new ArrayList<>();
            for (Unit u : calleeBody.getUnits()) {
                if (u instanceof IdentityStmt id) {
                    // @this := ; @param := – bind to actuals
                    handleIdentityStmt(id, actuals, localMap, callerBody, inlinedUnits);
                } else if (u instanceof ReturnStmt ret) {
                    // return x; → returnTarget = x (if there is one)
                    handleReturnStmt(ret, returnTarget, localMap, inlinedUnits, suffix);
                } else if (u instanceof ReturnVoidStmt) {
                    // void return – insert a NopStmt as placeholder
                    inlinedUnits.add(Jimple.v().newNopStmt());
                } else {
                    // Regular statement – clone and rename locals
                    Unit cloned = (Unit) u.clone();
                    renameLocals(cloned, localMap);
                    inlinedUnits.add(cloned);
                }
            }

            // ── Splice inlined units before the call ─────────────
            for (Unit iu : inlinedUnits)
                units.insertBefore(iu, callStmt);

            // ── Remove original call ──────────────────────────────
            units.remove(callStmt);

            // ── Cleanup passes ────────────────────────────────────
            CopyPropagator.v().transform(callerBody);
            DeadAssignmentEliminator.v().transform(callerBody);
            callerBody.validate();

            log.debug("[Inline] Inlined {} into {}.",
                      callee.getSubSignature(), caller.getName());
            return true;

        } catch (Exception e) {
            log.warn("[Inline] Failed for {}: {}", callee.getSubSignature(), e.getMessage());
            return false;
        }
    }

    private void handleIdentityStmt(IdentityStmt id, List<Value> actuals,
                                     Map<Local, Local> localMap, Body callerBody,
                                     List<Unit> out) {
        Value rhs = id.getRightOp();
        Local lhs = localMap.get((Local) id.getLeftOp());
        if (lhs == null) return;

        Value actual = null;
        if (rhs instanceof ThisRef && !actuals.isEmpty()) {
            actual = actuals.get(0);
        } else if (rhs instanceof ParameterRef pr) {
            int idx = pr.getIndex() + 1; // +1 because 0 is 'this'
            if (idx < actuals.size()) actual = actuals.get(idx);
        }
        if (actual != null)
            out.add(Jimple.v().newAssignStmt(lhs, actual));
    }

    private void handleReturnStmt(ReturnStmt ret, Local returnTarget,
                                   Map<Local, Local> localMap,
                                   List<Unit> out, String suffix) {
        if (returnTarget == null) return;
        Value retVal = ret.getOp();
        if (retVal instanceof Local l && localMap.containsKey(l)) retVal = localMap.get(l);
        out.add(Jimple.v().newAssignStmt(returnTarget, retVal));
    }

    /** Walk all use-boxes in a unit and replace old locals with renamed ones. */
    private void renameLocals(Unit u, Map<Local, Local> localMap) {
        for (ValueBox vb : u.getUseAndDefBoxes()) {
            if (vb.getValue() instanceof Local old && localMap.containsKey(old))
                vb.setValue(localMap.get(old));
        }
    }

    // ---------------------------------------------------------------
    // POLY rewriting — guarded inline
    // ---------------------------------------------------------------

    /**
     * Polymorphic site → insert a type-test chain.
     *
     * For each target T_i (sorted by estimated frequency, most common first):
     *
     *   if (receiver instanceof T_i) goto label_i
     *   ...
     *   goto label_fallback
     *
     *   label_i:
     *     directcall T_i.m(receiver, args...)
     *     goto label_after
     *
     *   label_fallback:
     *     virtualinvoke receiver.m(args...)   ← original call
     *
     *   label_after:
     *     ...
     *
     * This transforms a single vtable dispatch into a sequence of
     * type checks followed by direct calls.  The JIT can inline each
     * direct call and may eliminate the test if a branch dominates.
     *
     * NOTE: In Jimple, "goto" is modelled with GotoStmt; instanceof
     * is modelled with InstanceofExpr inside an IfStmt.
     */
    private void rewritePoly(CallSiteInfo site) {
        Set<SootMethod> targets = site.getBestTargets();
        log.debug("[Rewriter][POLY] {} targets at {}",
                  targets.size(),
                  site.getInvokeExpr().getMethod().getSubSignature());

        try {
            buildTypeTestChain(site, new ArrayList<>(targets));
            stats.guardedInline.incrementAndGet();
            site.markRewritten();
        } catch (Exception e) {
            log.warn("[Poly] Guarded inline failed for {}: {}", site, e.getMessage());
        }

        stats.recordClassification(CallSiteInfo.DispatchKind.POLY);
    }

    /**
     * Emits the type-test chain into the caller body.
     *
     * Jimple structure:
     *
     *   // for each target T_i:
     *   $tmp_i = instanceof receiver T_i
     *   if $tmp_i != 0 goto branch_i
     *
     *   // fallback:
     *   originalVirtualCall
     *   goto afterAll
     *
     *   // branch_i:
     *   $cast_i = (T_i) receiver
     *   directcall T_i.m($cast_i, args...)
     *   goto afterAll
     *
     *   // afterAll:
     *   NopStmt
     */
    private void buildTypeTestChain(CallSiteInfo site, List<SootMethod> targets) {
        SootMethod caller = site.getContainingMethod();
        Body callerBody   = caller.getActiveBody();
        Chain<Unit> units = callerBody.getUnits();
        Unit callStmt     = site.getStatement();

        InvokeExpr invoke   = site.getInvokeExpr();
        if (!(invoke instanceof InstanceInvokeExpr iie)) return;
        Local receiver = (Local) iie.getBase();

        // Anchor: NopStmt placed after the original call
        Unit afterAll = Jimple.v().newNopStmt();
        units.insertAfter(afterAll, callStmt);

        // Each branch is a list: [castAssign, directCall, goto afterAll]
        List<Unit> branches = new ArrayList<>();
        List<Unit> typeTests  = new ArrayList<>();

        for (SootMethod target : targets) {
            SootClass targetClass = target.getDeclaringClass();
            Type      castType    = targetClass.getType();

            // $cast = (TargetClass) receiver
            Local castLocal = Jimple.v().newLocal(
                "__cast_" + targetClass.getShortName() + "_" + System.nanoTime(),
                castType);
            callerBody.getLocals().add(castLocal);
            Unit castStmt = Jimple.v().newAssignStmt(
                castLocal,
                Jimple.v().newCastExpr(receiver, castType));

            // direct call: targetClass.m(castLocal, args…)
            List<Value> directArgs = new ArrayList<>();
            directArgs.add(castLocal);
            directArgs.addAll(iie.getArgs());
            InvokeExpr directInvoke =
                Jimple.v().newStaticInvokeExpr(target.makeRef(), directArgs);
            Unit directCall;
            if (callStmt instanceof AssignStmt as) {
                directCall = Jimple.v().newAssignStmt(as.getLeftOp(), directInvoke);
            } else {
                directCall = Jimple.v().newInvokeStmt(directInvoke);
            }

            Unit gotoAfter = Jimple.v().newGotoStmt(afterAll);

            // Collect branch body (inserted in reverse order later)
            branches.add(0, gotoAfter);
            branches.add(0, directCall);
            branches.add(0, castStmt);

            // instanceof test
            Local testLocal = Jimple.v().newLocal(
                "__iof_" + targetClass.getShortName() + "_" + System.nanoTime(),
                BooleanType.v());
            callerBody.getLocals().add(testLocal);
            Unit testAssign = Jimple.v().newAssignStmt(
                testLocal,
                Jimple.v().newInstanceOfExpr(receiver, castType));
            Unit ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(testLocal, IntConstant.v(0)),
                castStmt);

            typeTests.add(testAssign);
            typeTests.add(ifStmt);
        }

        // Insert type tests before the original call
        for (Unit t : typeTests) units.insertBefore(t, callStmt);
        // Insert branches before afterAll
        for (Unit b : branches) units.insertBefore(b, afterAll);
        // Original call stays as the fallback (already in place)
        // Move goto(afterAll) after the original call
        units.insertAfter(Jimple.v().newGotoStmt(afterAll), callStmt);
    }

    // ---------------------------------------------------------------
    // MEGA — no transformation
    // ---------------------------------------------------------------

    private void logMega(CallSiteInfo site) {
        stats.recordClassification(CallSiteInfo.DispatchKind.MEGA);
        log.info("[MEGA] {} targets at {} in {} – no optimisation applied.",
                 site.getBestTargets().size(),
                 site.getInvokeExpr().getMethod().getSubSignature(),
                 site.getContainingMethod().getName());
    }
}
