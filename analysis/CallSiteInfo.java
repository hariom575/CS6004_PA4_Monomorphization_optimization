import soot.*;
import soot.jimple.InvokeExpr;

import java.util.*;

/**
 * Everything we learn about one virtual call site, accumulated
 * across three refinement layers:
 *
 *   chaEdges   — direct output of Soot's CHA call graph (upper bound)
 *   ptaTargets — narrowed by intra-procedural PTA on the caller
 *   kobjTargets— narrowed further by k-object sensitivity
 *
 * The "best" target set is always the finest non-empty one.
 *
 * ── Dispatch classification ───────────────────────────────────────
 *
 *   MONO  (1 target)  → safe to replace virtual dispatch with:
 *                        (a) direct static call  — eliminates vtable lookup
 *                        (b) inline              — eliminates call overhead
 *                            (only when callee body is small)
 *
 *   BIMORPHIC (2)     → guarded inline: one instanceof check + two direct calls
 *                        This is the most profitable poly optimisation because
 *                        two-target sites are very common and the branch is
 *                        very predictable once the JIT sees it.
 *
 *   POLY (3–MEGA_T)   → type-test chain for each target + fallback virtual call
 *                        Profitable if the common case is in the first 1-2 tests.
 *
 *   MEGA (>MEGA_T)    → no transformation; virtual dispatch is as good as anything
 *
 * ── Goal ──────────────────────────────────────────────────────────
 *
 * We are targeting an interpreter with NO JIT and NO inline caches.
 * Every virtual dispatch is a full vtable lookup.  Every direct call
 * saves that lookup.  Every inlined call saves the call overhead too.
 * So MONO → inline is a significant win.  BIMORPHIC → guarded inline
 * is also a win because a predictable branch + direct call is cheaper
 * than two vtable lookups.
 */
final class CallSiteInfo {

    // ── Threshold ──────────────────────────────────────────────────
    static final int MEGA_THRESHOLD = 4;   // > 4 targets → MEGA

    // ── Dispatch kind ─────────────────────────────────────────────
    enum Kind { UNKNOWN, MONO, BIMORPHIC, POLY, MEGA }

    // ── Identity ──────────────────────────────────────────────────
    final SootMethod containingMethod;
    final Unit       stmt;
    final InvokeExpr invoke;

    // ── Target sets (populated incrementally) ─────────────────────
    final Set<SootMethod> chaEdges    = new LinkedHashSet<>();
    final Set<SootMethod> ptaTargets  = new LinkedHashSet<>();
    final Set<SootMethod> kobjTargets = new LinkedHashSet<>();

    Kind kind = Kind.UNKNOWN;


    CallSiteInfo(SootMethod containingMethod, Unit stmt, InvokeExpr invoke) {
        this.containingMethod = containingMethod;
        this.stmt             = stmt;
        this.invoke           = invoke;
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** The finest non-empty target set we currently have. */
    Set<SootMethod> bestTargets() {
        if (!kobjTargets.isEmpty()) return Collections.unmodifiableSet(kobjTargets);
        if (!ptaTargets.isEmpty())  return Collections.unmodifiableSet(ptaTargets);
        return Collections.unmodifiableSet(chaEdges);
    }

    /**
     * Recompute the dispatch kind from the best target set.
     * Call this after each layer finishes populating its set.
     */
    void classify() {
        int n = bestTargets().size();
        if      (n == 0)              kind = Kind.UNKNOWN;
        else if (n == 1)              kind = Kind.MONO;
        else if (n == 2)              kind = Kind.BIMORPHIC;
        else if (n <= MEGA_THRESHOLD) kind = Kind.POLY;
        else                          kind = Kind.MEGA;
    }

    boolean isMono()       { return kind == Kind.MONO; }
    boolean isBimorphic()  { return kind == Kind.BIMORPHIC; }
    boolean isPoly()       { return kind == Kind.POLY; }
    boolean isMega()       { return kind == Kind.MEGA; }

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
