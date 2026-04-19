import soot.*;
import soot.jimple.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
import soot.toolkits.graph.BriefUnitGraph;

import java.util.*;

/*
 * MonomorphizationTransformer - runs in Soot's wjtp phase.
 *
 * Three refinement layers applied in order:
 *   L1  CHA/VTA  -- Soot's call graph (CHA with VTA applied internally)
 *   L2  PTA      -- our intra-procedural points-to analysis
 *   L3  k-Obj    -- k-object sensitivity to handle container patterns
 *
 * Call site classification:
 *   MONO      (1 target)    -> devirtualise or inline if callee is small
 *   BIMORPHIC (2 targets)   -> guarded dispatch using instanceof check
 *   POLY      (3-4 targets) -> type-test chain with fallback
 *   MEGA      (5+)          -> leave as virtual call
 */
public class MonomorphizationTransformer extends SceneTransformer {

    private static final int K = 2;
    private static final int INLINE_THRESHOLD = 30;

    // stored after collection so refineKObj can access call graph edges
    private CallGraph cg;

    @Override
    protected void internalTransform(String phaseName, Map<String, String> options) {

        cg = Scene.v().getCallGraph();

        List<CallSiteInfo> sites = collectCallSites(cg);

        System.out.println("\n════════════════════════════════════════");
        System.out.println(" Monomorphization Analysis  (k=" + K + ")");
        System.out.println("════════════════════════════════════════");
        System.out.println("Total virtual call sites: " + sites.size());
        // Soot builds call graph using CHA and then refines with VTA internally,
        // so this first layer already includes VTA-level precision.
        printDistribution("After CHA/VTA", sites, false, false);

        refinePTA(sites);
        printDistribution("After PTA (intra-proc)", sites, true, false);

        refineKObj(sites, K);
        printDistribution("After k-Obj (k=" + K + ")", sites, true, true);

        printDetailedReport(sites);

        // ── Apply Jimple transformations ─────────────────────────
        System.out.println("\n── Applying Jimple rewrites ────────────────────────────────");
        JimpleRewriter rewriter = new JimpleRewriter(INLINE_THRESHOLD);
        rewriter.rewriteAll(sites);
        rewriter.printRewriteSummary();
    }

    // ─────────────────────────────────────────────────────────────
    // LAYER 1: Collect call sites
    //
    // Strategy: build the set of application methods to scan using
    // THREE sources, so we never miss any method:
    //
    //   (a) Entry points  — main() has no callers, won't appear
    //                       as a call graph edge source
    //   (b) CG edge sources — every method that calls something else
    //   (c) All application class methods — belt-and-suspenders;
    //       bodies are retrieved on demand via retrieveActiveBody()
    //
    // We retrieve bodies on demand because in Soot's whole-program
    // mode many methods have isConcrete()=true but hasActiveBody()=false
    // at wjtp time (lazy loading).
    // ─────────────────────────────────────────────────────────────
    private List<CallSiteInfo> collectCallSites(CallGraph cg) {
        List<CallSiteInfo> sites = new ArrayList<>();
        Set<SootMethod> toScan = new LinkedHashSet<>();

        // Only scan APPLICATION class methods — skip all library/JDK methods.
        // (a) Entry points that are application classes
        for (SootMethod ep : Scene.v().getEntryPoints())
            if (ep.getDeclaringClass().isApplicationClass())
                toScan.add(ep);

        // (b) CG edge sources that are application methods
        Iterator<Edge> allEdges = cg.iterator();
        while (allEdges.hasNext()) {
            Edge e = allEdges.next();
            SootMethod src = e.src();
            if (src != null && src.isConcrete()
                    && src.getDeclaringClass().isApplicationClass())
                toScan.add(src);
        }

        // (c) All application class concrete methods (belt-and-suspenders)
        for (SootClass cls : Scene.v().getApplicationClasses())
            for (SootMethod m : cls.getMethods())
                if (m.isConcrete()) toScan.add(m);

        // Scan each method
        for (SootMethod method : toScan) {
            // Retrieve body if not loaded yet
            if (!method.hasActiveBody()) {
                try { method.retrieveActiveBody(); }
                catch (Exception ex) { continue; }
            }
            if (!method.hasActiveBody()) continue;

            Body body = method.getActiveBody();
            for (Unit u : body.getUnits()) {
                Stmt stmt = (Stmt) u;
                if (!stmt.containsInvokeExpr()) continue;

                InvokeExpr invoke = stmt.getInvokeExpr();
                // Only virtual/interface dispatches; static and special are already mono
                if (!(invoke instanceof VirtualInvokeExpr) &&
                    !(invoke instanceof InterfaceInvokeExpr)) continue;

                CallSiteInfo site = new CallSiteInfo(method, u, invoke);

                // Read CHA edges from Soot's call graph
                Iterator<Edge> edges = cg.edgesOutOf(u);
                while (edges.hasNext()) {
                    SootMethod tgt = edges.next().tgt();
                    if (tgt.isConcrete()) site.chaEdges.add(tgt);
                }

                site.classify();
                sites.add(site);
            }
        }
        return sites;
    }

    // ─────────────────────────────────────────────────────────────
    // LAYER 2: Intra-procedural PTA
    //
    // For each virtual call site, run IntraProcPTA on its containing
    // method (once per method, cached).  At the call statement, query
    // pts(receiver):
    //
    //   All AllocSites concrete (no UNKNOWN)?
    //     → map each site's type to its dispatch target
    //     → ptaTargets = that set  (always ⊆ chaEdges after intersection)
    //   Any UNKNOWN?
    //     → can't narrow; ptaTargets = chaEdges (soundness)
    //
    // WHY this works for Test11:
    //   main() body:
    //     $r0 = new A;      → pts($r0) = {AS#0<A>}
    //     $r1 = new D;      → pts($r1) = {AS#1<D>}
    //     virtualinvoke $r1.<C: void foo(A)>($r0)  ← call site
    //   At the call:
    //     pts($r1) = {AS#1<D>}  — no UNKNOWN
    //     → type D → resolveDispatch(D, "void foo(A)") = D.foo
    //     → targets = {D.foo}
    //     → intersect with chaEdges {C.foo, D.foo} = {D.foo}
    //     → MONO ✓
    // ─────────────────────────────────────────────────────────────
    private final Map<SootMethod, IntraProcPTA.Result> ptaCache = new HashMap<>();

    private void refinePTA(List<CallSiteInfo> sites) {
        Map<SootMethod, List<CallSiteInfo>> byMethod = new LinkedHashMap<>();
        for (CallSiteInfo s : sites)
            byMethod.computeIfAbsent(s.containingMethod, m -> new ArrayList<>()).add(s);

        for (var entry : byMethod.entrySet()) {
            SootMethod         method      = entry.getKey();
            List<CallSiteInfo> methodSites = entry.getValue();

            IntraProcPTA.Result pta = getOrRunPTA(method);
            if (pta == null) {
                for (CallSiteInfo s : methodSites) s.ptaTargets.addAll(s.chaEdges);
                continue;
            }

            for (CallSiteInfo site : methodSites) {
                Set<SootMethod> narrow = narrowWithPTA(site, pta);
                if (narrow != null)
                    site.ptaTargets.addAll(narrow);
                else
                    site.ptaTargets.addAll(site.chaEdges);
                site.classify();
            }
        }
    }

    private Set<SootMethod> narrowWithPTA(CallSiteInfo site, IntraProcPTA.Result pta) {
        InvokeExpr invoke = site.invoke;
        if (!(invoke instanceof InstanceInvokeExpr iie)) return null;
        if (!(iie.getBase() instanceof Local receiver))  return null;

        PointsToState stateAtCall = pta.ptsIn.get(site.stmt);
        if (stateAtCall == null) return null;

        Set<PointsToState.AllocSite> receiverPts = stateAtCall.getVar(receiver);
        if (receiverPts.contains(PointsToState.UNKNOWN)) return null;
        if (receiverPts.isEmpty())                        return null;

        String subSig = invoke.getMethod().getSubSignature();
        Set<SootMethod> targets = new LinkedHashSet<>();

        for (PointsToState.AllocSite alloc : receiverPts) {
            Type t = alloc.getConcreteType();
            if (t == null) return null;
            if (!(t instanceof RefType rt)) continue;
            SootClass cls = rt.getSootClass();
            if (cls.isAbstract() || cls.isInterface()) continue;
            SootMethod m = resolveDispatch(cls, subSig);
            if (m == null || !m.isConcrete()) return null;
            targets.add(m);
        }

        // Monotone invariant: never add targets, only remove
        if (!site.chaEdges.isEmpty()) targets.retainAll(site.chaEdges);
        return targets.isEmpty() ? null : targets;
    }

    // ─────────────────────────────────────────────────────────────
    // LAYER 3: k-Object Sensitivity
    //
    // Handles the pattern PTA misses when the receiver came from a
    // method call or a field of a parameter:
    //
    //   class Box { Animal a; Box(Animal a){this.a=a;} }
    //   Box b = new Box(new Dog());
    //   b.run();  ← PTA of Box.run sees pts(this)=UNKNOWN
    //              k-obj uses caller's field state:
    //              callerState[BoxAllocSite.a] = {AllocSite<Dog>}
    //              → run() dispatches on Dog → MONO
    //
    // For Test11 this layer doesn't fire (PTA already finds MONO),
    // but it handles deeper patterns like the Box example.
    // ─────────────────────────────────────────────────────────────
    private void refineKObj(List<CallSiteInfo> sites, int k) {
        for (CallSiteInfo site : sites) {
            if (site.isMono()) {
                site.kobjTargets.addAll(site.ptaTargets);
                continue;
            }
            Set<SootMethod> refined = tryKObj(site, k);
            if (refined != null && !refined.isEmpty())
                site.kobjTargets.addAll(refined);
            else
                site.kobjTargets.addAll(site.ptaTargets);
            site.classify();
        }
    }

    private Set<SootMethod> tryKObj(CallSiteInfo site, int k) {
        InvokeExpr invoke = site.invoke;
        if (!(invoke instanceof InstanceInvokeExpr iie)) return null;
        if (!(iie.getBase() instanceof Local receiver))  return null;

        String subSig = invoke.getMethod().getSubSignature();

        // First: try standard PTA-based k-obj (receiver known locally)
        IntraProcPTA.Result localPTA = getOrRunPTA(site.containingMethod);
        if (localPTA != null) {
            PointsToState stateAtCall = localPTA.ptsIn.get(site.stmt);
            if (stateAtCall != null) {
                Set<PointsToState.AllocSite> receiverPts = stateAtCall.getVar(receiver);
                if (!receiverPts.contains(PointsToState.UNKNOWN) && !receiverPts.isEmpty()) {
                    Set<SootMethod> allTargets = new LinkedHashSet<>();
                    for (PointsToState.AllocSite receiverAlloc : receiverPts) {
                        Type recvType = receiverAlloc.getConcreteType();
                        if (recvType == null) return null;
                        if (!(recvType instanceof RefType rt)) continue;
                        SootMethod concrete = resolveDispatch(rt.getSootClass(), subSig);
                        if (concrete == null || !concrete.isConcrete()) return null;
                        allTargets.add(concrete);
                    }
                    if (!allTargets.isEmpty()) {
                        if (!site.ptaTargets.isEmpty()) allTargets.retainAll(site.ptaTargets);
                        if (!allTargets.isEmpty()) return allTargets;
                    }
                }
            }
        }

        // Second: caller-side k-obj — receiver comes from this.field inside the containing method.
        // Find the field, then look at all callers of this method via call graph to determine
        // what concrete types that field holds at each call site.
        SootField receiverField = findSourceField(site.containingMethod, receiver);
        if (receiverField == null) return null;

        Set<SootMethod> allTargets = new LinkedHashSet<>();
        boolean hadCallers = false;

        Iterator<Edge> callerEdges = cg.edgesInto(site.containingMethod);
        while (callerEdges.hasNext()) {
            Edge e = callerEdges.next();
            SootMethod callerMethod = e.src();
            Unit callUnit = e.srcUnit();
            if (callUnit == null || !callerMethod.isConcrete()) continue;
            if (!callerMethod.getDeclaringClass().isApplicationClass()) continue;

            IntraProcPTA.Result callerPTA = getOrRunPTA(callerMethod);
            if (callerPTA == null) continue;

            PointsToState callerState = callerPTA.ptsIn.get(callUnit);
            if (callerState == null) continue;

            // Get the 'this' object passed at this call site
            InvokeExpr callerInvoke = ((Stmt) callUnit).getInvokeExpr();
            if (!(callerInvoke instanceof InstanceInvokeExpr callerIie)) continue;
            if (!(callerIie.getBase() instanceof Local callerObj)) continue;

            Set<PointsToState.AllocSite> objPts = callerState.getVar(callerObj);

            if (objPts.isEmpty()) return null;

            if (objPts.contains(PointsToState.UNKNOWN)) {
                // callerObj is also from a field — k=2: go up one more level.
                // Find which field of callerMethod's 'this' callerObj comes from.
                SootField outerField = findSourceField(callerMethod, callerObj);
                if (outerField == null) return null;

                Iterator<Edge> outerEdges = cg.edgesInto(callerMethod);
                while (outerEdges.hasNext()) {
                    Edge e2 = outerEdges.next();
                    SootMethod outerCaller = e2.src();
                    Unit outerCallUnit = e2.srcUnit();
                    if (outerCallUnit == null || !outerCaller.isConcrete()) continue;
                    if (!outerCaller.getDeclaringClass().isApplicationClass()) continue;
                    InvokeExpr outerInvoke = ((Stmt) outerCallUnit).getInvokeExpr();
                    if (!(outerInvoke instanceof InstanceInvokeExpr outerIie)) continue;
                    if (!(outerIie.getBase() instanceof Local outerObj)) continue;

                    IntraProcPTA.Result outerPTA = getOrRunPTA(outerCaller);
                    if (outerPTA == null) continue;
                    PointsToState outerState = outerPTA.ptsIn.get(outerCallUnit);
                    if (outerState == null) continue;

                    Set<PointsToState.AllocSite> outerPts = outerState.getVar(outerObj);
                    if (outerPts.isEmpty() || outerPts.contains(PointsToState.UNKNOWN)) return null;

                    for (PointsToState.AllocSite outerAlloc : outerPts) {
                        // Resolve the intermediate object (callerObj) via constructor in outerCaller
                        Set<PointsToState.AllocSite> midPts =
                            resolveFieldViaConstructor(outerCaller, outerPTA, outerAlloc, outerField);
                        if (midPts == null || midPts.isEmpty() || midPts.contains(PointsToState.UNKNOWN))
                            return null;
                        // Now resolve receiverField from each intermediate alloc
                        for (PointsToState.AllocSite midAlloc : midPts) {
                            Set<PointsToState.AllocSite> fieldPts =
                                resolveFieldViaConstructor(outerCaller, outerPTA, midAlloc, receiverField);
                            if (fieldPts == null || fieldPts.isEmpty() || fieldPts.contains(PointsToState.UNKNOWN))
                                return null;
                            for (PointsToState.AllocSite fieldAlloc : fieldPts) {
                                Type t = fieldAlloc.getConcreteType();
                                if (!(t instanceof RefType rt)) return null;
                                SootMethod target = resolveDispatch(rt.getSootClass(), subSig);
                                if (target == null || !target.isConcrete()) return null;
                                allTargets.add(target);
                            }
                        }
                    }
                    hadCallers = true;
                }
                continue;
            }

            for (PointsToState.AllocSite objAlloc : objPts) {
                // First try direct field state; if contaminated by loop, use constructor analysis.
                Set<PointsToState.AllocSite> fieldPts = callerState.getField(objAlloc, receiverField);
                if (fieldPts.isEmpty() || fieldPts.contains(PointsToState.UNKNOWN)) {
                    fieldPts = resolveFieldViaConstructor(callerMethod, callerPTA, objAlloc, receiverField);
                }
                if (fieldPts == null || fieldPts.isEmpty() || fieldPts.contains(PointsToState.UNKNOWN))
                    return null;

                for (PointsToState.AllocSite fieldAlloc : fieldPts) {
                    Type t = fieldAlloc.getConcreteType();
                    if (!(t instanceof RefType rt)) return null;
                    SootMethod target = resolveDispatch(rt.getSootClass(), subSig);
                    if (target == null || !target.isConcrete()) return null;
                    allTargets.add(target);
                }
            }
            hadCallers = true;
        }

        if (!hadCallers || allTargets.isEmpty()) return null;
        if (!site.ptaTargets.isEmpty()) allTargets.retainAll(site.ptaTargets);
        return allTargets.isEmpty() ? null : allTargets;
    }

    /**
     * Resolve a field's allocation type by tracing back to the constructor call.
     * Used when the field state in the PTA fixed-point is UNKNOWN (loop contamination).
     * Finds the `new T` unit from objAlloc, then the `specialinvoke <init>` that follows,
     * then reads the PTA state AT that init call (before the loop) to get the arg's type.
     */
    private Set<PointsToState.AllocSite> resolveFieldViaConstructor(
            SootMethod callerMethod, IntraProcPTA.Result callerPTA,
            PointsToState.AllocSite objAlloc, SootField field) {
        if (objAlloc.unit == null || !(objAlloc.unit instanceof AssignStmt newStmt)) return null;
        if (!(newStmt.getLeftOp() instanceof Local allocLocal)) return null;
        if (!callerMethod.hasActiveBody()) return null;
        Body callerBody = callerMethod.getActiveBody();

        // Walk forward from the alloc unit to find the <init> call on allocLocal
        boolean seenAlloc = false;
        for (Unit u : callerBody.getUnits()) {
            if (u == objAlloc.unit) { seenAlloc = true; continue; }
            if (!seenAlloc) continue;
            if (!(u instanceof InvokeStmt is)) continue;
            if (!(is.getInvokeExpr() instanceof SpecialInvokeExpr sie)) continue;
            if (!sie.getMethod().getName().equals("<init>")) continue;
            if (!sie.getBase().equals(allocLocal)) continue;

            // Found the init call — find which param maps to our field
            SootMethod initMethod = sie.getMethod();
            if (!initMethod.isConcrete() || !initMethod.hasActiveBody()) return null;
            int paramIdx = findFieldParamIndex(initMethod, field);
            if (paramIdx < 0 || paramIdx >= sie.getArgCount()) return null;

            // Read PTA state at the init call (before the loop — clean state)
            PointsToState stateAtInit = callerPTA.ptsIn.get(u);
            if (stateAtInit == null) return null;
            Value arg = sie.getArg(paramIdx);
            if (!(arg instanceof Local argLocal)) return null;
            return stateAtInit.getVar(argLocal);
        }
        return null;
    }

    /** Walk a constructor body to find: this.field = @paramN → return param index N. */
    private int findFieldParamIndex(SootMethod initMethod, SootField field) {
        Body body = initMethod.getActiveBody();
        Local thisLocal = findThisLocal(body);
        if (thisLocal == null) return -1;
        Map<Local, Integer> localParamIdx = new HashMap<>();
        for (Unit u : body.getUnits()) {
            if (u instanceof IdentityStmt ids && ids.getRightOp() instanceof ParameterRef pr
                    && ids.getLeftOp() instanceof Local l)
                localParamIdx.put(l, pr.getIndex());
        }
        for (Unit u : body.getUnits()) {
            if (!(u instanceof AssignStmt as)) continue;
            Value lhs = as.getLeftOp();
            Value rhs = as.getRightOp();
            if (lhs instanceof InstanceFieldRef ifr && ifr.getBase().equals(thisLocal)
                    && ifr.getField().equals(field) && rhs instanceof Local src) {
                Integer idx = localParamIdx.get(src);
                if (idx != null) return idx;
            } else if (lhs instanceof Local dst && rhs instanceof Local src
                    && localParamIdx.containsKey(src)) {
                localParamIdx.put(dst, localParamIdx.get(src));
            }
        }
        return -1;
    }

    /**
     * Find the SootField that 'local' was read from in the given method body.
     * Looks for: local = this.field (direct InstanceFieldRef on @this).
     * Returns null if the local does not come from a this-field read.
     */
    private SootField findSourceField(SootMethod method, Local local) {
        if (!method.hasActiveBody()) return null;
        Body body = method.getActiveBody();
        Local thisLocal = findThisLocal(body);
        if (thisLocal == null) return null;

        // Build copy chains: if $x = $y and $y = this.f, then $x also comes from this.f
        Map<Local, SootField> sourceField = new HashMap<>();
        for (Unit u : body.getUnits()) {
            if (u instanceof AssignStmt as) {
                Value lhs = as.getLeftOp();
                Value rhs = as.getRightOp();
                if (!(lhs instanceof Local dest)) continue;
                if (rhs instanceof InstanceFieldRef ifr && ifr.getBase().equals(thisLocal)) {
                    sourceField.put(dest, ifr.getField());
                } else if (rhs instanceof Local src && sourceField.containsKey(src)) {
                    sourceField.put(dest, sourceField.get(src));
                }
            }
        }
        return sourceField.get(local);
    }

    /**
     * Walk the callee body for `this.field` reads and look up the
     * caller's PTA field state to find concrete types.
     *
     * Tracks two things:
     *   1. Assignments: dest = this.f   → localPts[dest] = callerState[receiverAlloc.f]
     *   2. Copies:      dest = src      → localPts[dest] = localPts[src]
     *   3. VirtualInvoke on tracked local → resolve target
     *
     * Returns null  if UNKNOWN encountered (can't narrow).
     * Returns empty if no field-based dispatch found in the body.
     */
    private Set<SootMethod> walkCalleeForFieldDispatch(
            SootMethod callee,
            PointsToState.AllocSite receiverAlloc,
            PointsToState callerState,
            String subSig, int k, int depth) {

        if (!callee.hasActiveBody()) return Collections.emptySet();
        Body body;
        try { body = callee.getActiveBody(); }
        catch (Exception e) { return Collections.emptySet(); }

        Local thisLocal = findThisLocal(body);
        if (thisLocal == null) return Collections.emptySet();

        Map<Local, Set<PointsToState.AllocSite>> localPts = new HashMap<>();
        localPts.put(thisLocal, PointsToState.setOf(receiverAlloc));

        Set<SootMethod> found = new LinkedHashSet<>();

        for (Unit u : body.getUnits()) {
            if (!(u instanceof Stmt stmt)) continue;

            // dest = this.f
            if (u instanceof AssignStmt as &&
                as.getRightOp() instanceof InstanceFieldRef ifr &&
                ifr.getBase() instanceof Local base && base.equals(thisLocal) &&
                as.getLeftOp() instanceof Local dest) {

                Set<PointsToState.AllocSite> fp = callerState.getField(receiverAlloc, ifr.getField());
                localPts.put(dest, (fp.isEmpty() || fp.contains(PointsToState.UNKNOWN))
                    ? PointsToState.setOf(PointsToState.UNKNOWN)
                    : new HashSet<>(fp));
                continue;
            }

            // dest = src  (simple copy)
            if (u instanceof AssignStmt as &&
                as.getRightOp() instanceof Local srcL &&
                as.getLeftOp() instanceof Local dstL) {
                Set<PointsToState.AllocSite> src = localPts.get(srcL);
                if (src != null) localPts.put(dstL, new HashSet<>(src));
                continue;
            }

            // Virtual dispatch on tracked local
            if (!stmt.containsInvokeExpr()) continue;
            InvokeExpr ie = stmt.getInvokeExpr();
            if (!(ie instanceof InstanceInvokeExpr iie2)) continue;
            if (!(iie2.getBase() instanceof Local dispBase)) continue;

            Set<PointsToState.AllocSite> basePts = localPts.get(dispBase);
            if (basePts == null) continue;
            if (basePts.contains(PointsToState.UNKNOWN)) return null;

            for (PointsToState.AllocSite alloc : basePts) {
                Type t = alloc.getConcreteType();
                if (t == null) return null;
                if (!(t instanceof RefType rt)) continue;
                SootClass cls = rt.getSootClass();
                if (cls.isAbstract() || cls.isInterface()) continue;
                SootMethod tgt = resolveDispatch(cls, ie.getMethod().getSubSignature());
                if (tgt == null || !tgt.isConcrete()) return null;

                if (depth + 1 < k) {
                    Set<SootMethod> deeper =
                        walkCalleeForFieldDispatch(tgt, alloc, callerState, subSig, k, depth + 1);
                    if (deeper == null) return null;
                    if (!deeper.isEmpty()) { found.addAll(deeper); continue; }
                }
                found.add(tgt);
            }
        }
        return found;
    }

    // ─────────────────────────────────────────────────────────────
    // Shared helpers
    // ─────────────────────────────────────────────────────────────

    private IntraProcPTA.Result getOrRunPTA(SootMethod method) {
        return ptaCache.computeIfAbsent(method, m -> {
            if (!m.hasActiveBody()) {
                try { m.retrieveActiveBody(); } catch (Exception e) { return null; }
            }
            if (!m.hasActiveBody()) return null;
            try { return new IntraProcPTA().analyze(new BriefUnitGraph(m.getActiveBody())); }
            catch (Exception e) { return null; }
        });
    }

    /**
     * JVM virtual dispatch: walk up from cls to find the first
     * concrete implementation of subSig.
     */
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

    private Local findThisLocal(Body body) {
        for (Unit u : body.getUnits())
            if (u instanceof IdentityStmt id && id.getRightOp() instanceof ThisRef)
                return (Local) id.getLeftOp();
        return null;
    }

    // ─────────────────────────────────────────────────────────────
    // Reporting
    // ─────────────────────────────────────────────────────────────

    private void printDistribution(String label, List<CallSiteInfo> sites,
                                    boolean compareToCHA, boolean compareToKObj) {
        long mono    = sites.stream().filter(CallSiteInfo::isMono).count();
        long bi      = sites.stream().filter(CallSiteInfo::isBimorphic).count();
        long poly    = sites.stream().filter(CallSiteInfo::isPoly).count();
        long mega    = sites.stream().filter(CallSiteInfo::isMega).count();
        long unknown = sites.stream().filter(s -> s.kind == CallSiteInfo.Kind.UNKNOWN).count();

        System.out.printf("%-30s MONO=%-4d BI=%-4d POLY=%-4d MEGA=%-4d UNKNOWN=%-4d%n",
            label + ":", mono, bi, poly, mega, unknown);

        if (compareToCHA) {
            long improved = sites.stream()
                .filter(s -> !s.ptaTargets.isEmpty() && s.ptaTargets.size() < s.chaEdges.size())
                .count();
            System.out.printf("  → Narrowed vs CHA: %d sites%n", improved);
        }
        if (compareToKObj) {
            long improved = sites.stream()
                .filter(s -> !s.kobjTargets.isEmpty() && s.kobjTargets.size() < s.ptaTargets.size())
                .count();
            System.out.printf("  → Narrowed vs PTA: %d sites%n", improved);
        }
    }

    private void printDetailedReport(List<CallSiteInfo> sites) {
        System.out.println("\n── Detailed site report ────────────────────────────────────");

        Map<SootMethod, List<CallSiteInfo>> byMethod = new LinkedHashMap<>();
        for (CallSiteInfo s : sites)
            byMethod.computeIfAbsent(s.containingMethod, m -> new ArrayList<>()).add(s);

        int inlined = 0, devirt = 0, guarded = 0, poly = 0, mega = 0;

        for (var entry : byMethod.entrySet()) {
            SootMethod m = entry.getKey();
            System.out.println("\n  " + m.getDeclaringClass().getName() + "." + m.getName() + "()");

            for (CallSiteInfo site : entry.getValue()) {
                int    line   = site.stmt.getJavaSourceStartLineNumber();
                String subSig = site.invoke.getMethod().getSubSignature();

                System.out.printf("    L%-4d  %-48s  [CHA=%d→%d]  %s%n",
                    line, subSig,
                    site.chaEdges.size(), site.bestTargets().size(), site.kind);

                switch (site.kind) {
                    case MONO -> {
                        SootMethod tgt = site.singleTarget();
                        if (canInline(tgt)) {
                            System.out.printf("           ✓ INLINE  → %s.%s%n",
                                tgt.getDeclaringClass().getShortName(), tgt.getName());
                            inlined++;
                        } else {
                            System.out.printf("           ✓ DEVIRT  → %s.%s%n",
                                tgt.getDeclaringClass().getShortName(), tgt.getName());
                            devirt++;
                        }
                    }
                    case BIMORPHIC -> {
                        List<SootMethod> tgts = new ArrayList<>(site.bestTargets());
                        System.out.printf("           ✓ GUARDED → %s.%s | %s.%s%n",
                            tgts.get(0).getDeclaringClass().getShortName(), tgts.get(0).getName(),
                            tgts.get(1).getDeclaringClass().getShortName(), tgts.get(1).getName());
                        guarded++;
                    }
                    case POLY -> {
                        System.out.printf("           ~ POLY     (%d targets)%n", site.bestTargets().size());
                        poly++;
                    }
                    case MEGA -> {
                        System.out.printf("           ✗ MEGA     (%d targets)%n", site.bestTargets().size());
                        mega++;
                    }
                    default -> {}
                }
            }
        }

        System.out.println("\n── Summary ─────────────────────────────────────────────────");
        System.out.printf("  INLINE   (MONO, callee ≤ %d stmts)  : %3d%n", INLINE_THRESHOLD, inlined);
        System.out.printf("  DEVIRT   (MONO, callee > %d stmts)  : %3d%n", INLINE_THRESHOLD, devirt);
        System.out.printf("  GUARDED  (BIMORPHIC)                : %3d%n", guarded);
        System.out.printf("  POLY     (3–4 targets)              : %3d%n", poly);
        System.out.printf("  MEGA     (5+ targets, no opt)       : %3d%n", mega);
        System.out.printf("  Total optimisable / total sites     : %d / %d%n",
            inlined + devirt + guarded + poly, sites.size());

        System.out.println("\n── k-Obj gain (cross-method field narrowing, k=" + K + ") ───");
        long kgain = sites.stream()
            .filter(s -> !s.kobjTargets.isEmpty() && s.kobjTargets.size() < s.ptaTargets.size())
            .count();
        System.out.printf("  Sites narrowed by k-obj beyond PTA  : %d%n", kgain);
    }

    private boolean canInline(SootMethod m) {
        if (!m.hasActiveBody()) {
            try { m.retrieveActiveBody(); } catch (Exception e) { return false; }
        }
        if (!m.hasActiveBody()) return false;
        Body b = m.getActiveBody();
        return b.getTraps().isEmpty() && b.getUnits().size() <= INLINE_THRESHOLD;
    }
}