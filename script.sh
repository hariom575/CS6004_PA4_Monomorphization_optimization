#!/usr/bin/env bash
# CS6004 PA4 - Monomorphization Optimization
# Team: The Heap Farmer
#   Hariom Mewada    (24m2137)
#   Pushpendra Uikey (23b1023)
#
# Usage: bash script.sh
#
# What this script does:
#   1. Deletes all old .class files from src/
#   2. Compiles the analysis code (Main.java and supporting files)
#   3. For each testcase (Test1 to Test12):
#      - Compiles the testcase
#      - Runs the original testcase (no JIT, -Xint) and records time
#      - Runs the optimization pass (outputs transformed .class to sootOutput/)
#      - Prints the full analysis report for this testcase
#      - Runs the optimized code and records time
#   4. Prints a summary table with all results

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src"
SOOT="$ROOT/soot-4.6.0-jar-with-dependencies.jar"
TESTS="$ROOT/tests"
TMP=$(mktemp -d /tmp/pa4_XXXXXX)

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

if [ ! -f "$SOOT" ]; then
    echo "ERROR: soot-4.6.0-jar-with-dependencies.jar not found at $ROOT" >&2
    exit 1
fi

# ── Step 1: clean old class files ──────────────────────────────────────────
echo "Cleaning old class files from src/..."
find "$SRC" -name "*.class" -delete 2>/dev/null || true

# ── Step 2: compile analysis code ──────────────────────────────────────────
echo "Compiling analysis code..."
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

TOTAL_SITES=0
TOTAL_OPT=0

# We collect summary data for the final table
declare -a TC_NAMES TC_SITES TC_MONO TC_BI TC_POLY TC_MEGA
declare -a TC_INL TC_DVT TC_GRD TC_TTEST TC_BEFORE TC_AFTER

for i in $(seq 1 12); do
    TC="Test$i"
    ORIG="$TMP/$TC/orig"
    OPT="$TMP/$TC/opt"
    # Soot writes to sootOutput/ relative to the CWD (project root), not src/
    OUTDIR="$ROOT/sootOutput"

    mkdir -p "$ORIG" "$OPT"

    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  $TC"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # Print the scenario comment from the testcase file
    head -5 "$TESTS/$TC.java" | grep "^//" || true
    echo ""

    # Compile the testcase (all classes in one file)
    javac "$TESTS/$TC.java" -d "$ORIG" 2>/dev/null || true

    # Measure wall-clock time of ORIGINAL code (no JIT)
    T_BEFORE=$(TIMEFORMAT='%R'; { time java -Xint -cp "$ORIG" "$TC" >/dev/null 2>&1; } 2>&1 | tail -1 || echo "N/A")

    # Run the optimization pass
    # Main writes transformed .class files to $SRC/sootOutput/
    REPORT=$(java -cp "$SRC:$SOOT" Main "$ORIG" "$TC" 2>&1) || true

    # Print the full analysis report for this testcase
    echo "$REPORT" | grep -v "^Soot\|^SLF4J\|^$\|Optimized class" || true
    echo ""

    # Copy optimized class files
    if [ -d "$OUTDIR" ]; then
        cp -r "$OUTDIR"/. "$OPT/" 2>/dev/null || true
    fi

    # Verify correctness: run both and compare output
    ORIG_OUT=$(java -Xint -cp "$ORIG" "$TC" 2>/dev/null || echo "ERROR")
    OPT_OUT=$(java -Xint -cp "$OPT"  "$TC" 2>/dev/null || echo "ERROR")

    if [ "$ORIG_OUT" = "$OPT_OUT" ]; then
        echo "  [OK] Output matches: $ORIG_OUT"
    else
        echo "  [FAIL] Output mismatch!"
        echo "    Original : $ORIG_OUT"
        echo "    Optimized: $OPT_OUT"
    fi

    # Measure wall-clock time of OPTIMIZED code (no JIT)
    T_AFTER=$(TIMEFORMAT='%R'; { time java -Xint -cp "$OPT" "$TC" >/dev/null 2>&1; } 2>&1 | tail -1 || echo "N/A")

    echo "  Time before: ${T_BEFORE}s   Time after: ${T_AFTER}s"
    echo ""

    # Parse analysis report for summary table
    cha_line=$(echo "$REPORT" | grep "After CHA" | head -1 || true)
    mono=$(echo "$cha_line"  | grep -oP 'MONO=\K[0-9]+' || echo 0)
    bi=$(echo "$cha_line"    | grep -oP 'BI=\K[0-9]+'   || echo 0)
    poly=$(echo "$cha_line"  | grep -oP 'POLY=\K[0-9]+' || echo 0)
    mega=$(echo "$cha_line"  | grep -oP 'MEGA=\K[0-9]+' || echo 0)
    inl=$(echo "$REPORT"   | grep "Inlined"          | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    dvt=$(echo "$REPORT"   | grep "Devirtualised"    | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    grd=$(echo "$REPORT"   | grep "Guarded dispatch" | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    ttest=$(echo "$REPORT" | grep "Type-test chain"  | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)

    sites=$(( ${mono:-0} + ${bi:-0} + ${poly:-0} + ${mega:-0} ))
    opt=$(( ${inl:-0} + ${dvt:-0} + ${grd:-0} + ${ttest:-0} ))
    TOTAL_SITES=$(( TOTAL_SITES + sites ))
    TOTAL_OPT=$(( TOTAL_OPT + opt ))

    TC_NAMES[$i]="$TC"
    TC_SITES[$i]="$sites"
    TC_MONO[$i]="${mono:-0}"
    TC_BI[$i]="${bi:-0}"
    TC_POLY[$i]="${poly:-0}"
    TC_MEGA[$i]="${mega:-0}"
    TC_INL[$i]="${inl:-0}"
    TC_DVT[$i]="${dvt:-0}"
    TC_GRD[$i]="${grd:-0}"
    TC_TTEST[$i]="${ttest:-0}"
    TC_BEFORE[$i]="$T_BEFORE"
    TC_AFTER[$i]="$T_AFTER"
done

# ── Step 4: summary table ──────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════════════════"
echo "  SUMMARY TABLE"
echo "════════════════════════════════════════════════════════════════════════"
printf "%-8s  %5s  %5s  %4s  %4s  %4s  %4s  %4s  %4s  %5s  %9s  %9s\n" \
    "Test" "Sites" "MONO" "BI" "POLY" "MEGA" "Inl" "Dvt" "Grd" "TypeT" "T_before" "T_after"
printf "%-8s  %5s  %5s  %4s  %4s  %4s  %4s  %4s  %4s  %5s  %9s  %9s\n" \
    "--------" "-----" "-----" "----" "----" "----" "----" "----" "----" "-----" "---------" "---------"

for i in $(seq 1 12); do
    printf "%-8s  %5s  %5s  %4s  %4s  %4s  %4s  %4s  %4s  %5s  %9s  %9s\n" \
        "${TC_NAMES[$i]}" \
        "${TC_SITES[$i]}" \
        "${TC_MONO[$i]}" "${TC_BI[$i]}" "${TC_POLY[$i]}" "${TC_MEGA[$i]}" \
        "${TC_INL[$i]}" "${TC_DVT[$i]}" "${TC_GRD[$i]}" "${TC_TTEST[$i]}" \
        "${TC_BEFORE[$i]}" "${TC_AFTER[$i]}"
done

echo ""
printf "Total virtual call sites : %d\n" "$TOTAL_SITES"
printf "Total sites optimised    : %d\n" "$TOTAL_OPT"
if [ "$TOTAL_SITES" -gt 0 ]; then
    PCT=$(( TOTAL_OPT * 100 / TOTAL_SITES ))
    printf "Overall reduction        : %d%%\n" "$PCT"
fi
echo ""
echo "Optimised class files are in: $ROOT/sootOutput/"
