import soot.*;
import soot.jimple.*;

import java.util.*;

import static com.mono.pta.PointsToState.UNKNOWN_ALLOC;

/**
 * ═══════════════════════════════════════════════════════════════════
 * STEP 3  —  k-Object-Sensitive PTA Refiner (custom engine)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Uses the IntraProcPTA engine (same worklist + transfer function
 * extracted from the redundant-load assignment) to implement
 * k-object sensitivity WITHOUT Spark.
 *
 * ── Why intra-proc PTA alone misses some monomorphic sites ────────
 *
 *   class Box {
 *     Animal a;
 *     Box(Animal a) { this.a = a; }
 *     void callSpeak() { a.speak(); }   // site S
 *   }
 *   Box b1 = new Box(new Dog());
 *   Box b2 = new Box(new Cat());
 *   b1.callSpeak();
 *   b2.callSpeak();
 *
 * IntraProcPTA of Box.callSpeak:
 *   It analyses the method body in isolation, no context.
 *   pts(this) = UNKNOWN (caller not modelled)
 *   → a.speak() is POLY: {Dog, Cat}
 *
 * With 1-object sensitivity (k=1):
 *   We analyse callSpeak TWICE:
 *     context(b1) = [alloc-site of new Box(...dog...)]
 *       caller's pts(b1.a) = {Dog}  → site S MONO → Dog.speak
 *     context(b2) = [alloc-site of new Box(...cat...)]
 *       caller's pts(b2.a) = {Cat}  → site S MONO → Cat.speak
 *
 * ── How we implement this without a full Andersen solver ──────────
 *
 * The key insight: we don't need to re-run the full PTA for the
 * callee under each context.  We only need to ask:
 *
 *   "Given that the caller's PTA state says the receiver object was
 *    allocated at alloc-site A, what concrete types can the dispatch
 *    receiver inside the callee hold?"
 *
 * We answer this by:
 *   1. Getting the caller's IntraProcPTA state at the call site.
 *   2. Looking at pts(receiver) in that state.
 *   3. For each alloc site A in pts(receiver):
 *        - A's type is the receiver type (always known)
 *        - Walk up to k levels of field reads in the callee body,
 *          each time checking the CALLER's field pts state for A.
 *        - This gives us the context-specific type of the field
 *          without re-running the whole PTA for the callee.
 *
 * This is sound under the assumption that the caller's field state
 * correctly captures what was stored before the call — which our
 * IntraProcPTA guarantees within the method.
 */
public class KObjectSensitiveRefiner {

    private static final Logger log =
        LoggerFactory.getLogger(KObjectSensitiveRefiner.class);

    private final int k;
    private final AnalysisStats stats;
    private final CustomPTARefiner ptaEngine;

    /** Cache: method → its intra-proc PTA result (avoid recomputing). */
    private final Map<SootMethod, IntraProcPTA.Result> ptaCache = new HashMap<>();

    public KObjectSensitiveRefiner(int k,
                                    AnalysisStats stats,
                                    CustomPTARefiner ptaEngine) {
        if (k < 1) throw new IllegalArgumentException("k must be ≥ 1; got " + k);
        this.k         = k;
        this.stats     = stats;
        this.ptaEngine = ptaEngine;
        log.info("[k-OBJ] Initialised (k={}, using custom IntraProcPTA engine).", k);
    }

    // ── Public API ────────────────────────────────────────────────

    public void refine(List<CallSiteInfo> sites) {
        int narrowed = 0;

        for (CallSiteInfo site : sites) {

            // Already mono from a prior pass — just carry over
            if (site.isMonomorphic()) {
                site.getKObjTargets().addAll(site.getPtaTargets().isEmpty()
                    ? site.getBestTargets() : site.getPtaTargets());
                continue;
            }

            stats.kObjSites.incrementAndGet();

            Set<SootMethod> refined = resolveWithKObj(site);

            if (refined == null || refined.isEmpty()) {
                // k-obj couldn't help — fall back to PTA/VTA/CHA
                site.getKObjTargets().addAll(site.getPtaTargets().isEmpty()
                    ? site.getBestTargets() : site.getPtaTargets());
            } else {
                site.getKObjTargets().addAll(refined);
            }

            int prevSize = site.getPtaTargets().isEmpty()
                ? (site.getVtaTargets().isEmpty()
                    ? site.getChaTargets().size()
                    : site.getVtaTargets().size())
                : site.getPtaTargets().size();

            if (site.getKObjTargets().size() < prevSize) {
                stats.kObjReduced.incrementAndGet();
                narrowed++;
            }
            if (!site.getKObjTargets().isEmpty())
                stats.kObjResolved.incrementAndGet();

            site.classify();

            if (site.isMonomorphic())
                log.debug("[k-OBJ] k={} → MONO: {} in {} → {}",
                          k,
                          site.getInvokeExpr().getMethod().getSubSignature(),
                          site.getContainingMethod().getName(),
                          site.getSingleTarget().getSignature());
        }

        log.info("[k-OBJ] Done. {} / {} sites narrowed by k-obj (k={}).",
                 narrowed, stats.kObjSites.get(), k);
    }

    // ── Core: k-object-sensitive resolution ──────────────────────

    /**
     * Narrow the target set for a single call site using k-obj.
     *
     * Returns null   → k-obj cannot determine anything, keep prior.
     * Returns empty  → same.
     * Returns set    → these are the narrowed targets.
     */
    private Set<SootMethod> resolveWithKObj(CallSiteInfo site) {
        InvokeExpr invoke = site.getInvokeExpr();
        if (!(invoke instanceof InstanceInvokeExpr iie)) return null;
        if (!(iie.getBase() instanceof Local receiver))  return null;

        SootMethod          caller    = site.getContainingMethod();
        IntraProcPTA.Result callerPTA = getPTA(caller);
        if (callerPTA == null) return null;

        Unit          callUnit     = site.getStatement();
        PointsToState stateAtCall  = callerPTA.ptsIn.get(callUnit);
        if (stateAtCall == null) return null;

        Set<PointsToState.AllocSite> receiverPts = stateAtCall.getVar(receiver);
        if (receiverPts.contains(UNKNOWN_ALLOC)) return null;
        if (receiverPts.isEmpty())               return null;

        String subSig = invoke.getMethod().getSubSignature();

        Set<SootMethod> targets = new LinkedHashSet<>();

        for (PointsToState.AllocSite receiverAlloc : receiverPts) {
            // ── Level 0: the receiver type itself ────────────────
            Type allocType = receiverAlloc.getAllocType();
            if (allocType == null) return null;   // UNKNOWN allocation

            if (!(allocType instanceof RefType rt)) continue;
            SootClass recvClass = rt.getSootClass();

            SootMethod concrete = resolveDispatch(recvClass, subSig);
            if (concrete == null || !concrete.isConcrete()) return null;

            // ── Levels 1..k: field-chain from caller's state ─────
            //
            // If the callee reads a field of `this`, we can look up
            // what the caller's PTA state says that field holds.
            // This is the core of k-object sensitivity.
            Set<Type> dispatchTypes =
                walkFieldChain(concrete, receiverAlloc, stateAtCall, 0);

            if (dispatchTypes == null) return null;  // hit UNKNOWN

            for (Type t : dispatchTypes) {
                if (!(t instanceof RefType drt)) continue;
                SootClass dcls = drt.getSootClass();
                if (dcls.isAbstract() || dcls.isInterface()) continue;
                SootMethod target = resolveDispatch(dcls, subSig);
                if (target != null && target.isConcrete())
                    targets.add(target);
            }

            // Also include the receiver's own concrete type
            if (!recvClass.isAbstract() && !recvClass.isInterface())
                targets.add(concrete);
        }

        if (targets.isEmpty()) return null;

        // ── Monotone intersection with best prior set ─────────────
        Set<SootMethod> prior = site.getPtaTargets().isEmpty()
            ? (site.getVtaTargets().isEmpty()
                ? site.getChaTargets()
                : site.getVtaTargets())
            : site.getPtaTargets();

        if (!prior.isEmpty()) targets.retainAll(prior);
        return targets.isEmpty() ? null : targets;
    }

    /**
     * Walks the callee body looking for field reads on `this`.
     * For each such field, looks up its points-to set in the
     * CALLER's state for `receiverAlloc`.
     *
     * Returns the set of concrete types reachable at depth ≤ k.
     * Returns null if UNKNOWN is encountered at any level.
     *
     * @param callee        the concrete callee method
     * @param receiverAlloc the specific alloc site of the receiver
     * @param callerState   caller's PTA state at the call site
     * @param depth         current recursion depth (0-indexed)
     */
    private Set<Type> walkFieldChain(SootMethod callee,
                                      PointsToState.AllocSite receiverAlloc,
                                      PointsToState callerState,
                                      int depth) {
        // Base case: depth exceeded k, return receiver's own type
        if (depth >= k) {
            Type t = receiverAlloc.getAllocType();
            return (t != null) ? new LinkedHashSet<>(Collections.singleton(t)) : null;
        }

        if (!callee.hasActiveBody()) {
            Type t = receiverAlloc.getAllocType();
            return (t != null) ? new LinkedHashSet<>(Collections.singleton(t)) : null;
        }

        Body calleeBody;
        try { calleeBody = callee.getActiveBody(); }
        catch (Exception e) { return null; }

        // Find the `this` local in the callee
        Local thisLocal = findThisLocal(calleeBody);

        Set<Type> result = new LinkedHashSet<>();
        // Always include the receiver's own type
        Type ownType = receiverAlloc.getAllocType();
        if (ownType != null) result.add(ownType);

        // Scan callee body for: x = this.f
        for (Unit u : calleeBody.getUnits()) {
            if (!(u instanceof AssignStmt as)) continue;
            if (!(as.getRightOp() instanceof InstanceFieldRef ifr)) continue;
            if (thisLocal == null) continue;
            if (!ifr.getBase().equals(thisLocal)) continue;

            SootField field = ifr.getField();

            // What does the CALLER's state say about receiverAlloc.field?
            Set<PointsToState.AllocSite> fieldPts =
                callerState.getField(receiverAlloc, field);

            if (fieldPts.isEmpty()) {
                // Field not tracked by caller PTA — conservative unknown
                return null;
            }
            if (fieldPts.contains(UNKNOWN_ALLOC)) return null;

            // ── Recurse for each object in the field pts ──────────
            //
            // This is the k-depth chain traversal.
            // At depth+1 we look at what *their* fields hold, etc.
            for (PointsToState.AllocSite fieldAlloc : fieldPts) {
                Type fieldType = fieldAlloc.getAllocType();
                if (fieldType == null) return null;

                result.add(fieldType);

                if (depth + 1 < k) {
                    // Find the callee that the field's type would dispatch to
                    if (fieldType instanceof RefType frt) {
                        SootClass fcls = frt.getSootClass();
                        SootMethod fieldCallee =
                            resolveDispatch(fcls, callee.getSubSignature());
                        if (fieldCallee != null) {
                            Set<Type> deeper = walkFieldChain(
                                fieldCallee, fieldAlloc, callerState, depth + 1);
                            if (deeper == null) return null;
                            result.addAll(deeper);
                        }
                    }
                }
            }
        }

        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Find the Local that corresponds to `this` in a method body.
     * In Jimple the first IdentityStmt assigns a ThisRef to a local.
     */
    private Local findThisLocal(Body body) {
        for (Unit u : body.getUnits()) {
            if (u instanceof IdentityStmt id &&
                id.getRightOp() instanceof ThisRef)
                return (Local) id.getLeftOp();
        }
        return null;
    }

    /** Walk the class hierarchy to find the most-specific concrete method. */
    private SootMethod resolveDispatch(SootClass cls, String subSig) {
        SootClass cur = cls;
        while (cur != null) {
            try {
                SootMethod m = cur.getMethod(subSig);
                if (m.isConcrete()) return m;
            } catch (RuntimeException ignored) {}
            cur = cur.hasSuperclass() ? cur.getSuperclass() : null;
        }
        return null;
    }

    /** Get (or compute) the PTA result for a method, using the cache. */
    private IntraProcPTA.Result getPTA(SootMethod method) {
        return ptaCache.computeIfAbsent(method, ptaEngine::getPTAResult);
    }
}
