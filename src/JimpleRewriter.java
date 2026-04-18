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
 *     // fallthrough else arm: static call to T1 (last target)
 *     $cast_T1 = (T1) r; staticinvoke <T1: ret m$mono(T1)>($cast_T1); goto done;
 *     arm0: $cast_T0 = (T0) r; staticinvoke <T0: ret m$mono(T0)>($cast_T0); goto done;
 *     done: nop;
 *   Each arm calls a static bridge method created in the target class.
 *   invokestatic passes JVM verification regardless of caller/callee relationship.
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

    private final Map<SootMethod, SootMethod> staticBridgeCache = new HashMap<>();
    private final Set<Unit> processedUnits = new HashSet<>();

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
        if (processedUnits.contains(site.stmt)) return;  // already transformed in a prior pass
        SootMethod callee = site.singleTarget();
        if (shouldInline(callee)) {
            if (tryInline(site, callee)) {
                inlinedCount++;
                return;
            }
        }
        devirtualise(site, callee);
        processedUnits.add(site.stmt);
        devirtCount++;
    }

    // ── Devirtualisation ────────────────────────────────────────────

    /**
     * Devirtualise a MONO call site using a static bridge method.
     *
     * Preferred path (static bridge available):
     *   Before: virtualinvoke r.<Abstract: R foo()>()
     *   After:  staticinvoke <Concrete: R foo$mono$Concrete(Concrete, ...)>(r, ...)
     *
     * This eliminates the vtable lookup entirely — no cast, no dispatch.
     * Running under -Xint (no JIT) the staticinvoke is directly resolved,
     * so we get the full benefit without relying on JIT inlining.
     *
     * Fallback (bridge creation fails, e.g. callee has traps):
     *   Before: virtualinvoke r.<Abstract: R foo()>()
     *   After:  $dvt = (Concrete) r
     *           virtualinvoke $dvt.<Concrete: R foo()>()
     *   (eliminates the abstract-class vtable lookup; cast redirects incoming jumps)
     */
    private void devirtualise(CallSiteInfo site, SootMethod callee) {
        InvokeExpr oldExpr = site.invoke;
        if (!(oldExpr instanceof InstanceInvokeExpr iie)) return;
        Local receiver = (Local) iie.getBase();

        // Preferred: static bridge → pure staticinvoke, no cast, no vtable
        SootMethod bridge = createStaticBridge(callee);
        if (bridge != null) {
            List<Value> bridgeArgs = new ArrayList<>();
            bridgeArgs.add(receiver);
            bridgeArgs.addAll(iie.getArgs());
            StaticInvokeExpr staticExpr =
                Jimple.v().newStaticInvokeExpr(bridge.makeRef(), bridgeArgs);
            patchInvokeExpr(site.stmt, staticExpr);
            return;
        }

        // Fallback: cast + virtualinvoke on concrete type
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
        if (processedUnits.contains(site.stmt)) return;
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, false);
        processedUnits.add(site.stmt);
        guardedCount++;
    }

    // ════════════════════════════════════════════════════════════════
    // POLY — type-test chain (3–MEGA_THRESHOLD targets)
    // ════════════════════════════════════════════════════════════════

    private void rewritePoly(CallSiteInfo site) {
        if (processedUnits.contains(site.stmt)) return;
        List<SootMethod> targets = new ArrayList<>(site.bestTargets());
        buildTypeTestChain(site, targets, true);
        processedUnits.add(site.stmt);
        typeTestCount++;
    }

    // ── Type-test chain builder ──────────────────────────────────────

    /**
     * Build a type-test dispatch chain in the Jimple unit chain.
     *
     * For !withFallback (BIMORPHIC, targets=[T0, T1]):
     *
     *   entryNop:                         ← all incoming jumps redirected here
     *   $iof_T0 = r instanceof T0;
     *   if $iof_T0 != 0 goto arm0;
     *   $cast_T1 = (T1) r;               ← else arm (T1, last target)
     *   staticinvoke <T1: ret m$mono(T1)>($cast_T1, args);
     *   goto afterCall;
     *   arm0: $cast_T0 = (T0) r;
     *   staticinvoke <T0: ret m$mono(T0)>($cast_T0, args);
     *   goto afterCall;
     *   [afterCall: whatever was after the original virtual call]
     *
     * For withFallback (POLY, targets=[T0, T1, T2]):
     *
     *   entryNop:
     *   $iof_T0 = r instanceof T0;  if $iof_T0 != 0 goto arm0;
     *   $iof_T1 = r instanceof T1;  if $iof_T1 != 0 goto arm1;
     *   $iof_T2 = r instanceof T2;  if $iof_T2 != 0 goto arm2;
     *   virtualinvoke r.<Decl: ret m()>(args);  ← original call as fallback
     *   goto afterCall;
     *   arm0: ...; goto afterCall;
     *   arm1: ...; goto afterCall;
     *   arm2: ...; goto afterCall;
     *   [afterCall: ...]
     *
     * WHY insertAfter+cursor instead of insertBefore(goto, doneNop):
     *   Soot's PatchingChain.insertBefore(X, pivot) redirects ALL existing
     *   unit-box targets from pivot to X — including X's own unit-boxes.
     *   So insertBefore(new GotoStmt(doneNop), doneNop) makes the goto
     *   point to itself (self-loop).  Using insertAfter with a moving cursor
     *   avoids the pivot entirely; gotos point to afterCall which is fetched
     *   before any edits and never used as a pivot.
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

        // Capture convergence point BEFORE any modifications.
        // Every arm goto jumps here. Never used as an insertBefore pivot.
        Unit afterCall = units.getSuccOf(site.stmt);

        // insertBefore(entryNop, site.stmt) causes PatchingChain to redirect
        // all existing jump targets from site.stmt → entryNop automatically.
        // The explicit redirectJumpsTo below is a belt-and-suspenders guard.
        Unit entryNop = Jimple.v().newNopStmt();
        units.insertBefore(entryNop, site.stmt);
        redirectJumpsTo(site.stmt, entryNop, callerBody);

        // Number of type-tested targets:
        //   BIMORPHIC: all but the last (last is the else-arm fallthrough)
        //   POLY: all (site.stmt remains as the virtual fallback)
        int numTests = withFallback ? targets.size() : targets.size() - 1;

        // Pre-create cast + call units for each type-tested target.
        // Doing this before insertion lets ifStmts reference them as branch
        // targets before they appear in the unit chain.
        long baseId = System.nanoTime();
        List<Unit> armCastUnits = new ArrayList<>();
        List<Unit> armCallUnits = new ArrayList<>();
        for (int i = 0; i < numTests; i++) {
            SootMethod target   = targets.get(i);
            SootClass  cls      = target.getDeclaringClass();
            RefType    castType = RefType.v(cls);

            Local castLocal = Jimple.v().newLocal(
                "$cast_" + cls.getShortName() + "_" + (baseId + i), castType);
            callerBody.getLocals().add(castLocal);

            armCastUnits.add(Jimple.v().newAssignStmt(
                castLocal, Jimple.v().newCastExpr(receiver, castType)));

            SootMethod bridge2 = createStaticBridge(target);
            InvokeExpr call;
            if (bridge2 != null) {
                List<Value> bArgs = new ArrayList<>();
                bArgs.add(castLocal);
                bArgs.addAll(origArgs);
                call = Jimple.v().newStaticInvokeExpr(bridge2.makeRef(), bArgs);
            } else {
                call = Jimple.v().newVirtualInvokeExpr(castLocal, target.makeRef(), origArgs);
            }
            armCallUnits.add(returnTarget != null
                ? Jimple.v().newAssignStmt(returnTarget, call)
                : Jimple.v().newInvokeStmt(call));
        }

        // Step 1: Insert type tests via insertAfter+cursor.
        // insertAfter does NOT redirect any jump targets, so no self-loop risk.
        // Chain after each pair: entryNop → ... → iofI → ifI → site.stmt → afterCall
        Unit cursor = entryNop;
        for (int i = 0; i < numTests; i++) {
            SootMethod target   = targets.get(i);
            SootClass  tgtClass = target.getDeclaringClass();
            RefType    castType = RefType.v(tgtClass);

            Local iofLocal = Jimple.v().newLocal(
                "$iof_" + tgtClass.getShortName() + "_" + (baseId + i), BooleanType.v());
            callerBody.getLocals().add(iofLocal);

            AssignStmt iofStmt = Jimple.v().newAssignStmt(
                iofLocal, Jimple.v().newInstanceOfExpr(receiver, castType));
            IfStmt ifStmt = Jimple.v().newIfStmt(
                Jimple.v().newNeExpr(iofLocal, IntConstant.v(0)), armCastUnits.get(i));

            units.insertAfter(iofStmt, cursor);  cursor = iofStmt;
            units.insertAfter(ifStmt,  cursor);  cursor = ifStmt;
        }

        // Step 2: Position cursor at end of the "fallthrough" section.
        if (!withFallback) {
            // BIMORPHIC: else arm is last target — insert it after last test.
            SootMethod lastTarget = targets.get(targets.size() - 1);
            List<Unit> elseArm = buildDirectCallArm(
                lastTarget, receiver, origArgs, returnTarget, callerBody);
            for (Unit u : elseArm) {
                units.insertAfter(u, cursor);
                cursor = u;
            }
            GotoStmt elseGoto = Jimple.v().newGotoStmt(afterCall);
            units.insertAfter(elseGoto, cursor);
            cursor = elseGoto;
        } else {
            // POLY: site.stmt is the virtual fallback; add goto afterCall after it.
            GotoStmt fallbackGoto = Jimple.v().newGotoStmt(afterCall);
            units.insertAfter(fallbackGoto, site.stmt);
            cursor = fallbackGoto;
        }

        // Step 3: Insert jumped-to arms after cursor.
        // Arm gotos point to afterCall (captured before edits) — never a pivot.
        for (int i = 0; i < numTests; i++) {
            units.insertAfter(armCastUnits.get(i), cursor); cursor = armCastUnits.get(i);
            units.insertAfter(armCallUnits.get(i), cursor); cursor = armCallUnits.get(i);
            GotoStmt armGoto = Jimple.v().newGotoStmt(afterCall);
            units.insertAfter(armGoto, cursor);             cursor = armGoto;
        }

        // Step 4: Remove original call (BIMORPHIC) or leave as fallback (POLY).
        if (!withFallback) {
            units.remove(site.stmt);
        }
    }

    /**
     * Build one direct-call arm using a static bridge method.
     *
     *   $cast_T = (T) receiver;
     *   [lhs =] staticinvoke <T: ret m$mono(T, args)>($cast_T, args);
     *
     * staticinvoke always passes JVM bytecode verification.
     * Falls back to virtualinvoke on cast receiver if static bridge unavailable.
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

        arm.add(Jimple.v().newAssignStmt(
            castLocal, Jimple.v().newCastExpr(receiver, castType)));

        SootMethod bridge = createStaticBridge(target);
        InvokeExpr callExpr;
        if (bridge != null) {
            List<Value> bridgeArgs = new ArrayList<>();
            bridgeArgs.add(castLocal);
            bridgeArgs.addAll(origArgs);
            callExpr = Jimple.v().newStaticInvokeExpr(bridge.makeRef(), bridgeArgs);
        } else {
            callExpr = Jimple.v().newVirtualInvokeExpr(castLocal, target.makeRef(), origArgs);
        }
        arm.add(returnTarget != null
            ? Jimple.v().newAssignStmt(returnTarget, callExpr)
            : Jimple.v().newInvokeStmt(callExpr));

        return arm;
    }

    // ── Static bridge factory ────────────────────────────────────────

    /**
     * Create (or return cached) a static wrapper for {@code target}.
     *
     * The wrapper has signature: static <retType> <name>$mono(<DeclClass> r, <params...>)
     * The body is a copy of the original with @this replaced by @parameter0.
     * Callers use staticinvoke which always passes JVM bytecode verification.
     *
     * Returns null if the body has traps (we cannot copy them safely).
     */
    private SootMethod createStaticBridge(SootMethod target) {
        if (staticBridgeCache.containsKey(target)) return staticBridgeCache.get(target);

        if (!target.hasActiveBody()) {
            try { target.retrieveActiveBody(); } catch (Exception e) {
                staticBridgeCache.put(target, null); return null;
            }
        }
        Body origBody = target.getActiveBody();
        if (!origBody.getTraps().isEmpty()) {
            staticBridgeCache.put(target, null); return null;
        }

        SootClass cls = target.getDeclaringClass();
        String bridgeName = target.getName() + "$mono$" + cls.getShortName();

        // Reuse if already added (e.g. if this method is called twice for same target)
        for (SootMethod m : cls.getMethods()) {
            if (m.getName().equals(bridgeName) && m.isStatic()) {
                staticBridgeCache.put(target, m);
                return m;
            }
        }

        List<Type> paramTypes = new ArrayList<>();
        paramTypes.add(cls.getType());                      // explicit receiver
        paramTypes.addAll(target.getParameterTypes());

        SootMethod bridge = new SootMethod(bridgeName, paramTypes,
            target.getReturnType(), soot.Modifier.PUBLIC | soot.Modifier.STATIC);
        cls.addMethod(bridge);

        JimpleBody newBody = Jimple.v().newBody(bridge);
        bridge.setActiveBody(newBody);

        Map<Local, Local> renameMap = new HashMap<>();
        for (Local l : origBody.getLocals()) {
            Local fresh = Jimple.v().newLocal(l.getName() + "_br", l.getType());
            newBody.getLocals().add(fresh);
            renameMap.put(l, fresh);
        }

        Chain<Unit> newUnits = newBody.getUnits();
        for (Unit u : origBody.getUnits()) {
            if (u instanceof IdentityStmt id) {
                Value rhs = id.getRightOp();
                Local lhs = renameMap.get((Local) id.getLeftOp());
                if (lhs == null) continue;
                if (rhs instanceof ThisRef) {
                    newUnits.add(Jimple.v().newIdentityStmt(lhs,
                        Jimple.v().newParameterRef(cls.getType(), 0)));
                } else if (rhs instanceof ParameterRef pr) {
                    newUnits.add(Jimple.v().newIdentityStmt(lhs,
                        Jimple.v().newParameterRef(pr.getType(), pr.getIndex() + 1)));
                }
            } else {
                Unit cloned = (Unit) u.clone();
                renameLocals(cloned, renameMap);
                newUnits.add(cloned);
            }
        }

        staticBridgeCache.put(target, bridge);
        return bridge;
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
        System.out.printf("  Devirtualised      : %3d  (virtualinvoke → staticinvoke via bridge; fallback: cast + virtualinvoke)%n",
            devirtCount);
        System.out.printf("  Guarded dispatch   : %3d  (instanceof + 2 staticinvoke calls)%n",
            guardedCount);
        System.out.printf("  Type-test chain    : %3d  (instanceof chain + staticinvoke calls)%n",
            typeTestCount);
        System.out.printf("  Skipped (MEGA)     : %3d%n", skippedMegaCount);
        System.out.printf("  Total transformed  : %3d%n",
            inlinedCount + devirtCount + guardedCount + typeTestCount);
    }
}
