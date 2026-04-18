#!/usr/bin/env bash
# run_benchmark.sh — DaCapo benchmark analysis using monomorphization pass
#
# Runs the CHA/VTA analysis on all 5 DaCapo benchmarks and prints a summary.
# Full PTA + k-obj is skipped because real-world programs (82k+ call sites)
# need 8+ GB RAM; the CHA/VTA layer alone shows meaningful distribution stats.
#
# Requirements:
#   - Java 8+ on PATH
#   - dacapo-9.12-MR1-bach.jar in this directory
#   - tami-outs/ directory extracted here (unzip tami-outs.zip first)
#
# Usage:
#   cd pa4-benchmark
#   bash run_benchmark.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOOT_JAR="$SCRIPT_DIR/../soot-4.6.0-jar-with-dependencies.jar"
TAMI_OUTS="$SCRIPT_DIR/tami-outs"
BENCHMARKS="avrora luindex xalan fop batik"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'

# ── Sanity checks ──────────────────────────────────────────────────────────────
if [ ! -f "$SCRIPT_DIR/dacapo-9.12-MR1-bach.jar" ]; then
    echo -e "${RED}ERROR: dacapo-9.12-MR1-bach.jar not found in $SCRIPT_DIR${NC}"
    exit 1
fi

if [ ! -d "$TAMI_OUTS" ]; then
    echo -e "${YELLOW}tami-outs/ not found. Extracting tami-outs.zip ...${NC}"
    unzip -q "$SCRIPT_DIR/tami-outs.zip" -d "$SCRIPT_DIR"
fi

if [ ! -f "$SOOT_JAR" ]; then
    echo -e "${RED}ERROR: soot jar not found at $SOOT_JAR${NC}"
    exit 1
fi

# ── Compile ────────────────────────────────────────────────────────────────────
echo -e "${CYAN}Compiling benchmark analysis sources...${NC}"
cd "$SCRIPT_DIR"

# Copy latest src files from parent (overwrite if already copied)
cp ../src/MonomorphizationTransformer.java \
   ../src/JimpleRewriter.java \
   ../src/CallSiteInfo.java \
   ../src/IntraProcPTA.java \
   ../src/PointsToState.java \
   ../src/CallSiteRewriter.java . 2>/dev/null || true

javac -cp .:../soot-4.6.0-jar-with-dependencies.jar \
    CallSiteInfo.java PointsToState.java IntraProcPTA.java \
    JimpleRewriter.java MonomorphizationTransformer.java \
    GetSootArgs.java SampleSceneTransform.java Main.java \
    2>&1

echo -e "${GREEN}Compilation done.${NC}"
echo ""

# ── Run each benchmark ─────────────────────────────────────────────────────────
declare -A TOTAL MONO BI POLY MEGA UNKNOWN OPTIMISABLE

for bench in $BENCHMARKS; do
    OUT_DIR="$TAMI_OUTS/out-$bench"
    if [ ! -d "$OUT_DIR" ]; then
        echo -e "${YELLOW}Skipping $bench — $OUT_DIR not found${NC}"
        continue
    fi

    echo -e "${CYAN}========== Running analysis on: $bench ==========${NC}"
    RESULT=$(java -Xmx1g -Dstats.only=true \
        -cp .:../soot-4.6.0-jar-with-dependencies.jar \
        Main "$OUT_DIR" 2>&1 | grep -v "^SLF4J" | grep -v "^\[")

    echo "$RESULT" | grep -E "Total|MONO|BIMORPHIC|POLY|MEGA|UNKNOWN|Optimis|Top|  [a-zA-Z]"

    # Parse for summary table
    TOTAL[$bench]=$(echo   "$RESULT" | grep "Total virtual" | grep -oP '\d+$')
    MONO[$bench]=$(echo    "$RESULT" | grep "MONO " | grep "%" | grep -oP 'MONO\s+\(1 target\)\s+:\s+\K\d+')
    BI[$bench]=$(echo      "$RESULT" | grep "BIMORPHIC" | grep "%" | grep -oP 'BIMORPHIC.*:\s+\K\d+')
    POLY[$bench]=$(echo    "$RESULT" | grep "POLY " | grep "%" | grep -oP 'POLY.*:\s+\K\d+')
    MEGA[$bench]=$(echo    "$RESULT" | grep "MEGA " | grep "%" | grep -oP 'MEGA.*:\s+\K\d+')
    UNKNOWN[$bench]=$(echo "$RESULT" | grep "UNKNOWN" | grep "%" | grep -oP 'UNKNOWN.*:\s+\K\d+')
    OPTIMISABLE[$bench]=$(echo "$RESULT" | grep "Optimisable" | grep -oP 'Optimisable.*:\s+\K\d+')
    echo ""
done

# ── Summary table ──────────────────────────────────────────────────────────────
echo -e "${GREEN}══════════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  DaCapo Benchmark — Monomorphization Analysis Summary (CHA/VTA)  ${NC}"
echo -e "${GREEN}══════════════════════════════════════════════════════════════════${NC}"
printf "%-12s %8s %8s %6s %6s %8s %8s %12s\n" \
    "Benchmark" "Total" "MONO" "BI" "POLY" "MEGA" "UNKNOWN" "Optimisable"
printf "%-12s %8s %8s %6s %6s %8s %8s %12s\n" \
    "----------" "-----" "----" "--" "----" "----" "-------" "-----------"

for bench in $BENCHMARKS; do
    if [ -z "${TOTAL[$bench]}" ]; then continue; fi
    T=${TOTAL[$bench]:-0}
    OPT=${OPTIMISABLE[$bench]:-0}
    PCT=$(awk "BEGIN { printf \"%.1f%%\", 100*$OPT/$T }" 2>/dev/null || echo "?")
    printf "%-12s %8s %8s %6s %6s %8s %8s %10s\n" \
        "$bench" \
        "${TOTAL[$bench]:-?}" \
        "${MONO[$bench]:-?}" \
        "${BI[$bench]:-?}" \
        "${POLY[$bench]:-?}" \
        "${MEGA[$bench]:-?}" \
        "${UNKNOWN[$bench]:-?}" \
        "$OPT ($PCT)"
done

echo ""
echo -e "${YELLOW}Note: UNKNOWN = call sites in unreachable methods (no CHA edges).${NC}"
echo -e "${YELLOW}      Full PTA+k-obj pass requires 8+ GB RAM for real-world programs.${NC}"
