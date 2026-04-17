#!/usr/bin/env bash
# CS6004 PA4 — run monomorphization analysis on all 12 testcases
# Usage: ./run_tests.sh [test_number]
#   No argument: runs all 12 tests and prints a summary table.
#   With argument: runs only that test and prints full analysis output.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANALYSIS="$ROOT/src"
TESTCASES="$ROOT/testcases"
SOOT="$ROOT/soot-4.6.0-jar-with-dependencies.jar"
TMP="/tmp/pa4_$$"

cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

if [ ! -f "$SOOT" ]; then
    echo "ERROR: $SOOT not found." >&2
    exit 1
fi

echo "Compiling analysis code..."
javac -cp "$SOOT" \
    "$ANALYSIS/PA4.java" \
    "$ANALYSIS/MonomorphizationTransformer.java" \
    "$ANALYSIS/CallSiteInfo.java" \
    "$ANALYSIS/IntraProcPTA.java" \
    "$ANALYSIS/PointsToState.java" \
    "$ANALYSIS/JimpleRewriter.java" \
    -d "$ANALYSIS" 2>&1
echo "Compilation successful."
echo ""

run_one() {
    local name="$1"
    local verbose="$2"
    local src="$TESTCASES/$name"
    local out="$TMP/$name"

    mkdir -p "$out"
    javac "$src"/*.java -d "$out" 2>/dev/null || true

    java -cp "$ANALYSIS:$SOOT" PA4 "$out" "$name" 2>&1 || true
}

if [ "${1:-}" != "" ]; then
    # Single test, full output
    run_one "Test$1" "true"
    echo ""
    echo "Jimple output in: $ROOT/src/sootOutput/"
    exit 0
fi

# All 12 tests — summary table
printf "%-8s  %-6s %-6s %-6s %-6s  %-8s %-8s %-8s %-8s %-8s\n" \
    "Test" "MONO" "BI" "POLY" "MEGA" "Inlined" "Devirt" "Guarded" "TypeTest" "SkipMEGA"
printf "%-8s  %-6s %-6s %-6s %-6s  %-8s %-8s %-8s %-8s %-8s\n" \
    "--------" "------" "------" "------" "------" "--------" "--------" "--------" "--------" "--------"

TOTAL_TRANSFORMED=0
TOTAL_SITES=0

for i in $(seq 1 12); do
    OUTPUT=$(run_one "Test$i" "false" 2>&1) || true

    cha_line=$(echo "$OUTPUT" | grep "After CHA:" | head -1 || true)
    mono=$(echo "$cha_line"  | grep -oP 'MONO=\K[0-9]+' || echo 0)
    bi=$(echo "$cha_line"    | grep -oP 'BI=\K[0-9]+'   || echo 0)
    poly=$(echo "$cha_line"  | grep -oP 'POLY=\K[0-9]+' || echo 0)
    mega=$(echo "$cha_line"  | grep -oP 'MEGA=\K[0-9]+' || echo 0)

    inlined=$(echo "$OUTPUT"  | grep "Inlined"         | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    devirt=$(echo "$OUTPUT"   | grep "Devirtualised"   | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    guarded=$(echo "$OUTPUT"  | grep "Guarded dispatch"| grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    typetest=$(echo "$OUTPUT" | grep "Type-test chain" | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)
    skipmega=$(echo "$OUTPUT" | grep "Skipped (MEGA)"  | grep -oP ':\s+\K[0-9]+' | head -1 || echo 0)

    errors=$(echo "$OUTPUT" | grep "\[rewriter\] FAILED" | wc -l || echo 0)

    flag=""
    if [ "$errors" -gt 0 ]; then flag=" ERRORS:$errors"; fi

    printf "%-8s  %-6s %-6s %-6s %-6s  %-8s %-8s %-8s %-8s %-8s%s\n" \
        "Test$i" \
        "${mono:-?}" "${bi:-?}" "${poly:-?}" "${mega:-?}" \
        "${inlined:-?}" "${devirt:-?}" "${guarded:-?}" "${typetest:-?}" "${skipmega:-?}" \
        "$flag"

    sites=$((${mono:-0} + ${bi:-0} + ${poly:-0} + ${mega:-0}))
    xformed=$((${inlined:-0} + ${devirt:-0} + ${guarded:-0} + ${typetest:-0}))
    TOTAL_SITES=$((TOTAL_SITES + sites))
    TOTAL_TRANSFORMED=$((TOTAL_TRANSFORMED + xformed))
done

echo ""
echo "Total call sites scanned : $TOTAL_SITES"
echo "Total rewrites applied   : $TOTAL_TRANSFORMED"
echo ""
echo "Jimple output in: $ROOT/src/sootOutput/"
