import soot.*;
import soot.jimple.*;
import soot.toolkits.graph.UnitGraph;

import java.util.*;

/**
 * Intra-procedural points-to analysis engine.
 *
 * This is the exact worklist solver + transfer function from the
 * redundant-load assignment, extracted into its own class so both
 * the PTA consumer and the k-obj consumer can call it without
 * duplicating code.
 *
 * ── What it computes ─────────────────────────────────────────────
 *
 * Given a method's CFG, it computes for every unit U:
 *   ptsIn[U]  — points-to state on entry  to U
 *   ptsOut[U] — points-to state on exit from U
 *
 * The state at any point maps:
 *   local variable → set of AllocSite (which new-exprs can reach it)
 *   (AllocSite, field) → set of AllocSite (field contents)
 *
 * ── Transfer function rules (unchanged from assignment) ──────────
 *
 *   new T()         → pts(lhs) = { fresh AllocSite }
 *   x = y           → pts(x)   = pts(y)
 *   x = y.f         → pts(x)   = ⋃ { pts(o.f) | o ∈ pts(y) }
 *                                  (UNKNOWN if any pts(o.f) is empty)
 *   y.f = x         → strong update if |pts(y)|=1 ∧ y≠UNKNOWN
 *                     weak   update otherwise
 *   call(recv,args) → invalidate fields of all reachable objects
 *                     (conservative escape)
 *
 * ── How we use the result for monomorphization ───────────────────
 *
 * At a virtual call site   x = r.m(a1...an):
 *   pts(r) at that unit tells us which concrete types r can hold.
 *   If pts(r) = { AllocSite<Dog> }  → dispatch must go to Dog.m
 *   If pts(r) = { AllocSite<Dog>, AllocSite<Cat> } → two targets
 *   If pts(r) contains UNKNOWN → we cannot narrow, keep CHA edges
 */
final class IntraProcPTA {

    /** Per-instance cache: maps `new` statements to stable AllocSite objects. */
    private final Map<Unit, PointsToState.AllocSite> allocByUnit = new HashMap<>();

    // ── Public result type ────────────────────────────────────────

    static final class Result {
        /** Points-to state ON ENTRY to each unit — what we query for call sites. */
        final Map<Unit, PointsToState> ptsIn;
        /** Points-to state ON EXIT from each unit. */
        final Map<Unit, PointsToState> ptsOut;
        /** Stable unit→index map (used to give each new-expr a unique id). */
        final Map<Unit, Integer>       unitToIndex;

        Result(Map<Unit, PointsToState> ptsIn,
               Map<Unit, PointsToState> ptsOut,
               Map<Unit, Integer>       unitToIndex) {
            this.ptsIn       = ptsIn;
            this.ptsOut      = ptsOut;
            this.unitToIndex = unitToIndex;
        }
    }

    /**
     * Analyse one method body.
     *
     * @param cfg  BriefUnitGraph or ExceptionalUnitGraph of the method
     * @return     Result containing ptsIn / ptsOut for every unit
     */
    Result analyze(UnitGraph cfg) {
        Map<Unit, Integer>       unitToIndex = buildIndex(cfg);
        Map<Unit, PointsToState> ptsIn       = new HashMap<>();
        Map<Unit, PointsToState> ptsOut      = new HashMap<>();
        runWorklist(cfg, unitToIndex, ptsIn, ptsOut);
        return new Result(ptsIn, ptsOut, unitToIndex);
    }

    // ── Worklist solver ──────────────────────────────────────────
    // Copied verbatim from AnalysisTransformer.runPointsToAnalysis

    private void runWorklist(UnitGraph cfg,
                              Map<Unit, Integer>       unitToIndex,
                              Map<Unit, PointsToState> inMap,
                              Map<Unit, PointsToState> outMap) {
        for (Unit u : cfg) {
            inMap.put(u,  PointsToState.empty());
            outMap.put(u, PointsToState.empty());
        }

        Deque<Unit> worklist = new ArrayDeque<>();
        Set<Unit>   visited  = new HashSet<>();
        for (Unit u : cfg) worklist.add(u);

        while (!worklist.isEmpty()) {
            Unit          unit = worklist.removeFirst();
            PointsToState in   = join(cfg.getPredsOf(unit), outMap, visited);
            PointsToState out  = transfer(unit, in, unitToIndex);
            visited.add(unit);

            if (!out.equals(outMap.get(unit))) {
                inMap.put(unit,  in);
                outMap.put(unit, out);
                worklist.addAll(cfg.getSuccsOf(unit));
            } else {
                inMap.put(unit, in);
            }
        }
    }

    // ── Join at control-flow merge points ────────────────────────
    // Copied verbatim from AnalysisTransformer.mergePointsTo

    private PointsToState join(List<Unit>               preds,
                                Map<Unit, PointsToState> outMap,
                                Set<Unit>                visited) {
        if (preds == null || preds.isEmpty()) return PointsToState.empty();
        PointsToState merged = null;
        for (Unit pred : preds) {
            if (!visited.contains(pred)) continue;
            PointsToState s = outMap.get(pred);
            merged = (merged == null) ? s.copy() : merged.union(s);
        }
        return merged == null ? PointsToState.empty() : merged;
    }

    // ── Transfer function ─────────────────────────────────────────
    // Copied verbatim from AnalysisTransformer.transferPointsTo

    private PointsToState transfer(Unit unit,
                                    PointsToState in,
                                    Map<Unit, Integer> idx) {
        PointsToState out     = in.copy();
        int           lineIdx = idx.getOrDefault(unit, Integer.MAX_VALUE);

        if (unit instanceof IdentityStmt) return out;          // @this, @param

        if (unit instanceof InvokeStmt is) {
            processInvoke(out, is.getInvokeExpr());            // escape/invalidate
            return out;
        }

        if (!(unit instanceof AssignStmt stmt)) return out;

        Value lhs = stmt.getLeftOp();
        Value rhs = stmt.getRightOp();

        // ── rhs is an invoke ─────────────────────────────────────
        if (rhs instanceof InvokeExpr ie) {
            processInvoke(out, ie);
            if (lhs instanceof Local l) {
                // Return value: conservatively UNKNOWN
                // (no inter-procedural summaries in intra-proc PTA)
                out.setVar(l, PointsToState.setOf(PointsToState.UNKNOWN));
                out.recordWrite(l, lineIdx);
            }
            return out;
        }

        // ── lhs is a local ────────────────────────────────────────
        if (lhs instanceof Local l) {
            if      (rhs instanceof AnyNewExpr)        out.setVar(l, PointsToState.setOf(allocFor(unit, idx)));
            else if (rhs instanceof Local r)            out.setVar(l, out.getVar(r));
            else if (rhs instanceof StaticFieldRef)     out.setVar(l, PointsToState.setOf(PointsToState.UNKNOWN));
            else if (rhs instanceof InstanceFieldRef ifr) {
                Local base       = (Local) ifr.getBase();
                SootField field  = ifr.getField();
                Set<PointsToState.AllocSite> basePts = out.getVar(base);
                Set<PointsToState.AllocSite> result  = new HashSet<>();
                boolean addUnknown = basePts.contains(PointsToState.UNKNOWN);
                for (PointsToState.AllocSite site : basePts) {
                    if (site.equals(PointsToState.UNKNOWN)) continue;
                    Set<PointsToState.AllocSite> fp = out.getField(site, field);
                    if (fp.isEmpty()) addUnknown = true;
                    else              result.addAll(fp);
                }
                if (addUnknown || result.isEmpty()) result.add(PointsToState.UNKNOWN);
                out.setVar(l, result);
            }
            else out.setVar(l, PointsToState.setOf(PointsToState.UNKNOWN));                // cast, binop, array, const…

            out.recordWrite(l, lineIdx);
            return out;
        }

        // ── lhs is a field write: base.f = rhs ────────────────────
        if (lhs instanceof InstanceFieldRef ifr &&
            (rhs instanceof Local || rhs instanceof Constant)) {

            Local     base  = (Local) ifr.getBase();
            SootField field = ifr.getField();

            Set<PointsToState.AllocSite> rhsPts = (rhs instanceof Local r)
                ? out.getVar(r)
                : PointsToState.setOf(allocFor(unit, idx));                  // constant → fresh site

            Set<PointsToState.AllocSite> basePts = out.getVar(base);
            boolean strong = basePts.size() == 1 && !basePts.contains(PointsToState.UNKNOWN);
            if (strong) out.setField(basePts.iterator().next(), field, rhsPts);
            else        for (var site : basePts) if (!site.equals(PointsToState.UNKNOWN)) out.addField(site, field, rhsPts);
        }

        return out;
    }

    // ── Escape / field invalidation on method calls ──────────────
    // Copied verbatim from AnalysisTransformer.processInvokeExpr
    //
    // We conservatively assume the callee can modify any field of any
    // object reachable from the receiver or arguments.  So we clear
    // all tracked field pts for those objects (and transitively for
    // all objects reachable through their fields).

    private void processInvoke(PointsToState state, InvokeExpr ie) {
        Queue<PointsToState.AllocSite> queue = new ArrayDeque<>();

        if (ie instanceof InstanceInvokeExpr iie && iie.getBase() instanceof Local base)
            for (var s : state.getVar(base)) if (!s.equals(PointsToState.UNKNOWN)) queue.add(s);

        for (Value arg : ie.getArgs())
            if (arg instanceof Local l)
                for (var s : state.getVar(l)) if (!s.equals(PointsToState.UNKNOWN)) queue.add(s);

        Set<PointsToState.AllocSite> done = new HashSet<>();
        while (!queue.isEmpty()) {
            PointsToState.AllocSite site = queue.poll();
            if (!done.add(site)) continue;
            for (SootField f : state.getAllFields(site))
                for (var t : state.getField(site, f))
                    if (!t.equals(PointsToState.UNKNOWN) && !done.contains(t)) queue.add(t);
            state.removeAllFields(site);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Map<Unit, Integer> buildIndex(UnitGraph cfg) {
        Map<Unit, Integer> m = new HashMap<>();
        int i = 0;
        for (Unit u : cfg) m.put(u, i++);
        return m;
    }

    private PointsToState.AllocSite allocFor(Unit unit, Map<Unit, Integer> idx) {
        return allocByUnit.computeIfAbsent(unit,
            u -> new PointsToState.AllocSite(idx.get(u), u, false));
    }
}