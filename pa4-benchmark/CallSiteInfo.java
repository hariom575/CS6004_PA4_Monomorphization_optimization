import soot.*;
import soot.jimple.InvokeExpr;

import java.util.*;

/*
 * Stores everything we know about one virtual call site.
 * Target set is refined in 3 steps:
 *
 *   chaEdges   -- targets from Soot's call graph (built using CHA + VTA internally)
 *   ptaTargets -- narrowed by our intra-proc PTA on the caller body
 *   kobjTargets-- narrowed further by k-object sensitivity (k=2)
 *
 * Note: Soot's call graph uses VTA (Variable Type Analysis) on top of CHA.
 * VTA and CHA produce the same edge set in Soot's representation, so we
 * use the single chaEdges set for both.
 *
 * We always use the finest non-empty set as the "best" target set.
 *
 * Classification:
 *   MONO      (1 target)    -- devirtualise or inline the callee
 *   BIMORPHIC (2 targets)   -- guarded dispatch with instanceof check
 *   POLY      (3-4 targets) -- type-test chain with fallback
 *   MEGA      (5+)          -- leave as virtual call, not worth touching
 */
final class CallSiteInfo {

    static final int MEGA_THRESHOLD = 4;

    enum Kind { UNKNOWN, MONO, BIMORPHIC, POLY, MEGA }

    final SootMethod containingMethod;
    final Unit       stmt;
    final InvokeExpr invoke;

    // Target sets - filled layer by layer
    final Set<SootMethod> chaEdges    = new LinkedHashSet<>();
    final Set<SootMethod> ptaTargets  = new LinkedHashSet<>();
    final Set<SootMethod> kobjTargets = new LinkedHashSet<>();

    Kind kind = Kind.UNKNOWN;

    CallSiteInfo(SootMethod containingMethod, Unit stmt, InvokeExpr invoke) {
        this.containingMethod = containingMethod;
        this.stmt             = stmt;
        this.invoke           = invoke;
    }

    // Returns finest non-empty target set we have so far
    Set<SootMethod> bestTargets() {
        if (!kobjTargets.isEmpty()) return Collections.unmodifiableSet(kobjTargets);
        if (!ptaTargets.isEmpty())  return Collections.unmodifiableSet(ptaTargets);
        return Collections.unmodifiableSet(chaEdges);
    }

    // Call after each layer finishes to update classification
    void classify() {
        int n = bestTargets().size();
        if      (n == 0)              kind = Kind.UNKNOWN;
        else if (n == 1)              kind = Kind.MONO;
        else if (n == 2)              kind = Kind.BIMORPHIC;
        else if (n <= MEGA_THRESHOLD) kind = Kind.POLY;
        else                          kind = Kind.MEGA;
    }

    boolean isMono()      { return kind == Kind.MONO; }
    boolean isBimorphic() { return kind == Kind.BIMORPHIC; }
    boolean isPoly()      { return kind == Kind.POLY; }
    boolean isMega()      { return kind == Kind.MEGA; }

    SootMethod singleTarget() {
        if (!isMono()) throw new IllegalStateException("Not mono: " + this);
        return bestTargets().iterator().next();
    }

    @Override public String toString() {
        return String.format("[%s] %s in %s  cha=%d pta=%d kobj=%d",
            kind,
            invoke.getMethod().getSubSignature(),
            containingMethod.getName(),
            chaEdges.size(), ptaTargets.size(), kobjTargets.size());
    }
}
