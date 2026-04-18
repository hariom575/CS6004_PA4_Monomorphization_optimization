import soot.*;

import java.util.*;

/**
 * Points-to lattice element for one program point inside a method.
 *
 * Taken verbatim from the redundant-load assignment.
 * The only additions are:
 *   - AllocSite.getConcreteType()  so callers can ask "what type
 *     was allocated here?" without re-inspecting the Unit.
 *   - Everything is package-accessible (no package, same directory).
 */
final class PointsToState {

    // ── Sentinel: "we don't know what this points to" ─────────────
    static final AllocSite UNKNOWN = new AllocSite(-1, null, true);

    // ── Core maps ─────────────────────────────────────────────────
    final Map<Local, Set<AllocSite>>        varPts;      // var  → {alloc sites}
    final Map<FieldKey, Set<AllocSite>>     fieldPts;    // obj.field → {alloc sites}
    final Map<AllocSite, Set<Local>>        revVarPts;   // alloc → {locals pointing to it}
    final Map<Local, Integer>               lastWriteLine;

    private PointsToState() {
        varPts        = new HashMap<>();
        fieldPts      = new HashMap<>();
        revVarPts     = new HashMap<>();
        lastWriteLine = new HashMap<>();
    }

    // ── Factory ───────────────────────────────────────────────────

    static PointsToState empty() { return new PointsToState(); }

    // ── Deep copy ─────────────────────────────────────────────────

    PointsToState copy() {
        PointsToState c = new PointsToState();
        for (var e : varPts.entrySet())
            c.varPts.put(e.getKey(), new HashSet<>(e.getValue()));
        for (var e : fieldPts.entrySet())
            c.fieldPts.put(e.getKey(), new HashSet<>(e.getValue()));
        for (var e : revVarPts.entrySet())
            c.revVarPts.put(e.getKey(), new HashSet<>(e.getValue()));
        c.lastWriteLine.putAll(lastWriteLine);
        return c;
    }

    // ── Lattice join (used at control-flow merge points) ──────────

    PointsToState union(PointsToState other) {
        PointsToState r = new PointsToState();

        // Merge variable pts — a local absent from one branch → UNKNOWN
        Set<Local> allLocals = new HashSet<>(varPts.keySet());
        allLocals.addAll(other.varPts.keySet());
        for (Local l : allLocals) {
            Set<AllocSite> merged = new HashSet<>();
            merged.addAll(varPts.getOrDefault(l, setOf(UNKNOWN)));
            merged.addAll(other.varPts.getOrDefault(l, setOf(UNKNOWN)));
            r.setVar(l, merged);
        }

        // Merge field pts
        Set<FieldKey> allFields = new HashSet<>(fieldPts.keySet());
        allFields.addAll(other.fieldPts.keySet());
        for (FieldKey k : allFields) {
            Set<AllocSite> merged = new HashSet<>();
            merged.addAll(fieldPts.getOrDefault(k, setOf(UNKNOWN)));
            merged.addAll(other.fieldPts.getOrDefault(k, setOf(UNKNOWN)));
            r.setField(k.site, k.field, merged);
        }

        // Merge write lines — take the later one (more conservative)
        Set<Local> allWritten = new HashSet<>(lastWriteLine.keySet());
        allWritten.addAll(other.lastWriteLine.keySet());
        for (Local l : allWritten) {
            int a = lastWriteLine.getOrDefault(l, Integer.MIN_VALUE);
            int b = other.lastWriteLine.getOrDefault(l, Integer.MIN_VALUE);
            r.lastWriteLine.put(l, Math.max(a, b));
        }
        return r;
    }

    // ── Variable points-to ────────────────────────────────────────

    Set<AllocSite> getVar(Local l) {
        return varPts.getOrDefault(l, setOf(UNKNOWN));
    }

    void setVar(Local l, Set<AllocSite> sites) {
        // Remove stale reverse-map entries for the old pts set
        Set<AllocSite> old = varPts.get(l);
        if (old != null) {
            for (AllocSite s : old) {
                Set<Local> ls = revVarPts.get(s);
                if (ls != null) { ls.remove(l); if (ls.isEmpty()) revVarPts.remove(s); }
            }
        }
        if (sites == null || sites.isEmpty()) { varPts.remove(l); return; }
        Set<AllocSite> fresh = new HashSet<>(sites);
        varPts.put(l, fresh);
        for (AllocSite s : fresh)
            revVarPts.computeIfAbsent(s, k -> new HashSet<>()).add(l);
    }

    Set<Local> getLocalsForAlloc(AllocSite site) {
        return revVarPts.getOrDefault(site, Collections.emptySet());
    }

    // ── Field points-to ───────────────────────────────────────────

    Set<AllocSite> getField(AllocSite site, SootField field) {
        return fieldPts.getOrDefault(new FieldKey(site, field), Collections.emptySet());
    }

    void setField(AllocSite site, SootField field, Set<AllocSite> pts) {
        FieldKey k = new FieldKey(site, field);
        if (pts == null || pts.isEmpty()) fieldPts.remove(k);
        else fieldPts.put(k, new HashSet<>(pts));
    }

    void addField(AllocSite site, SootField field, Set<AllocSite> pts) {
        fieldPts.computeIfAbsent(new FieldKey(site, field), k -> new HashSet<>())
                .addAll(pts);
    }

    Set<SootField> getAllFields(AllocSite site) {
        Set<SootField> out = new HashSet<>();
        for (FieldKey k : fieldPts.keySet()) if (k.site.equals(site)) out.add(k.field);
        return out;
    }

    void removeAllFields(AllocSite site) {
        fieldPts.keySet().removeIf(k -> k.site.equals(site));
    }

    // ── Write-line tracking ───────────────────────────────────────

    void recordWrite(Local l, int line) { lastWriteLine.put(l, line); }

    int getLastWriteLine(Local l) {
        return lastWriteLine.getOrDefault(l, Integer.MAX_VALUE);
    }

    // ── Equality (needed for fixed-point termination check) ───────

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PointsToState p)) return false;
        return varPts.equals(p.varPts)
            && fieldPts.equals(p.fieldPts)
            && revVarPts.equals(p.revVarPts)
            && lastWriteLine.equals(p.lastWriteLine);
    }

    @Override public int hashCode() {
        return Objects.hash(varPts, fieldPts, revVarPts, lastWriteLine);
    }

    // ── Utility ───────────────────────────────────────────────────

    static Set<AllocSite> setOf(AllocSite s) {
        Set<AllocSite> set = new HashSet<>(); set.add(s); return set;
    }

    // ═══════════════════════════════════════════════════════════════
    // AllocSite  — one heap allocation point (new T() statement)
    // ═══════════════════════════════════════════════════════════════

    static final class AllocSite {
        final int     id;
        final Unit    unit;      // the `new` statement, null for UNKNOWN
        final boolean unknown;

        AllocSite(int id, Unit unit, boolean unknown) {
            this.id = id; this.unit = unit; this.unknown = unknown;
        }

        /**
         * Returns the concrete type being allocated, or null for UNKNOWN.
         * Used by the monomorphization consumer to map alloc-site → type.
         *
         * AnyNewExpr (the supertype of NewExpr, NewArrayExpr, NewMultiArrayExpr)
         * does not have getBaseType().  We use getType() instead, which is
         * defined on all Value subclasses and returns the allocated type directly.
         *   new Dog()          → RefType("Dog")
         *   new int[5]         → ArrayType(IntType, 1)   — we ignore array allocs
         */
        Type getConcreteType() {
            if (unknown || !(unit instanceof soot.jimple.AssignStmt as)) return null;
            soot.Value rhs = as.getRightOp();
            if (!(rhs instanceof soot.jimple.AnyNewExpr ne)) return null;
            // getType() is defined on Value; it returns the type of the expression
            Type t = ne.getType();
            // We only care about reference types (object allocations)
            return (t instanceof RefType) ? t : null;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AllocSite a)) return false;
            return id == a.id && unknown == a.unknown;
        }
        @Override public int hashCode() { return Objects.hash(id, unknown); }
        @Override public String toString() {
            if (unknown) return "UNKNOWN";
            Type t = getConcreteType();
            return "Alloc#" + id + (t != null ? "<" + t + ">" : "");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FieldKey  — composite key (AllocSite, SootField)
    // ═══════════════════════════════════════════════════════════════

    static final class FieldKey {
        final AllocSite site;
        final SootField field;
        FieldKey(AllocSite site, SootField field) { this.site = site; this.field = field; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldKey fk)) return false;
            return Objects.equals(site, fk.site) && Objects.equals(field, fk.field);
        }
        @Override public int hashCode() { return Objects.hash(site, field); }
    }
}