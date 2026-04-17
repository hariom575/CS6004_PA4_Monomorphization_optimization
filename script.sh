#!/usr/bin/env bash
# CS6004 PA4 — Monomorphization Optimization
# Usage: bash script.sh
#
# For each testcase the script reports:
#   - total virtual call sites found (before optimization)
#   - how many were resolved to MONO / BIMORPHIC / POLY / MEGA
#   - how many were inlined / devirtualised / guarded / type-tested
#   - virtual sites eliminated (MONO+BI+POLY vs original total)
#   - wall-clock runtime before and after optimization (with -Xint, no JIT)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/analysis"
SOOT="$ROOT/soot-4.6.0-jar-with-dependencies.jar"
TESTS="$ROOT/testcases"
TMP=$(mktemp -d /tmp/pa4_XXXXXX)

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

if [ ! -f "$SOOT" ]; then
    echo "ERROR: soot-4.6.0-jar-with-dependencies.jar not found at $ROOT" >&2
    exit 1
fi

# ── Step 1: clean old class files ──────────────────────────────────────────
echo "Cleaning old class files..."
find "$SRC" -name "*.class" -delete 2>/dev/null || true

# ── Step 2: compile analysis code ──────────────────────────────────────────
echo "Compiling..."
javac -cp "$SOOT" \
    "$SRC/Main.java" \
    "$SRC/MonomorphizationTransformer.java" \
    "$SRC/CallSiteInfo.java" \
    "$SRC/IntraProcPTA.java" \
    "$SRC/PointsToState.java" \
    "$SRC/JimpleRewriter.java" \
    -d "$SRC" 2>&1
echo "Compilation done."
echo ""

# ── Step 3: per-testcase analysis and transformation ───────────────────────
printf "%-8s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %7s  %9s  %9s\n" \
    "Test" "Sites" "MONO" "BI" "POLY" "MEGA" "Inl" "Dvt" "Grd" "TypeT" "T_before" "T_after"
printf "%-8s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %7s  %9s  %9s\n" \
    "--------" "-----" "-----" "-----" "-----" "-----" "-----" "-----" "-----" "-------" "---------" "---------"

TOTAL_SITES=0
TOTAL_OPT=0

for i in $(seq 1 12); do
    TC="Test$i"
    TCDIR="$TESTS/$TC"
    ORIG="$TMP/$TC/orig"
    OPT="$TMP/$TC/opt"
    OUTDIR="$SRC/sootOutput"

    mkdir -p "$ORIG" "$OPT"

    # Compile the testcase
    javac "$TCDIR"/*.java -d "$ORIG" 2>/dev/null || true

    # Measure wall-clock time of ORIGINAL code (no JIT)
    MAIN_CLASS="$TC"
    T_BEFORE=$(TIMEFORMAT='%R'; { time java -Xint -cp "$ORIG" "$MAIN_CLASS" >/dev/null 2>&1; } 2>&1 | tail -1 || echo "N/A")

    # Run the optimization pass (transforms code and dumps to sootOutput/)
    REPORT=$(java -cp "$SRC:$SOOT" Main "$ORIG" "$MAIN_CLASS" 2>&1) || true

    # Copy optimised class files (Soot writes to sootOutput/ relative to CWD)
    if [ -d "$OUTDIR" ]; then
        cp -r "$OUTDIR"/. "$OPT/" 2>/dev/null || true
    fi

    # Measure wall-clock time of OPTIMIZED code (no JIT)
    T_AFTER=$(TIMEFORMAT='%R'; { time java -Xint -cp "$OPT" "$MAIN_CLASS" >/dev/null 2>&1; } 2>&1 | tail -1 || echo "N/A")

    # Parse analysis report
    cha_line=$(echo "$REPORT" | grep "After CHA:" | head -1 || true)
    mono=$(echo "$cha_line"  | grep -oP 'MONO=\K[0-9]+' || echo 0)
    bi=$(echo "$cha_line"    | grep -oP 'BI=\K[0-9]+'   || echo 0)
    poly=$(echo "$cha_line"  | grep -oP 'POLY=\K[0-9]+' || echo 0)
    mega=$(echo "$cha_line"  | grep -oP 'MEGA=\K[0-9]+' || echo 0)
    inl=$(echo "$REPORT"  | grep "Inlined"          | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    dvt=$(echo "$REPORT"  | grep "Devirtualised"    | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    grd=$(echo "$REPORT"  | grep "Guarded dispatch" | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    ttest=$(echo "$REPORT" | grep "Type-test chain" | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)

    sites=$(( ${mono:-0} + ${bi:-0} + ${poly:-0} + ${mega:-0} ))
    opt=$(( ${inl:-0} + ${dvt:-0} + ${grd:-0} + ${ttest:-0} ))
    TOTAL_SITES=$(( TOTAL_SITES + sites ))
    TOTAL_OPT=$(( TOTAL_OPT + opt ))

    printf "%-8s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %5s  %7s  %9s  %9s\n" \
        "$TC" "$sites" \
        "${mono:-0}" "${bi:-0}" "${poly:-0}" "${mega:-0}" \
        "${inl:-0}" "${dvt:-0}" "${grd:-0}" "${ttest:-0}" \
        "$T_BEFORE" "$T_AFTER"
done

echo ""
printf "Total virtual call sites: %d\n" "$TOTAL_SITES"
printf "Total sites optimised:    %d\n" "$TOTAL_OPT"
if [ "$TOTAL_SITES" -gt 0 ]; then
    PCT=$(( TOTAL_OPT * 100 / TOTAL_SITES ))
    printf "Overall reduction:        %d%%\n" "$PCT"
fi
echo ""
echo "Optimised class files are in: $SRC/sootOutput/"
