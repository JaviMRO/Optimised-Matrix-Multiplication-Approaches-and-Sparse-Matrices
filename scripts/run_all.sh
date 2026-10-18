#!/usr/bin/env sh
# Full pipeline: build + tests (aborts if any test fails), environment, Part A, Part B, memory, analysis.
# Usage: sh scripts/run_all.sh [extra Suite options...]
# Close other applications first: the measuring JVMs commit 75 % of the RAM (-Xms = -Xmx).
. "$(dirname "$0")/common.sh"
echo ">> mvn clean verify (build + JUnit tests)"
mvn -B clean verify || { echo "Build or tests failed: no benchmark was run." >&2; exit 1; }
ENV_ID=$(new_env_id)
export ENV_ID
sh scripts/run_dense.sh "$@"
sh scripts/run_sparse.sh "$@"
sh scripts/run_memory.sh "$@"
echo ">> Analysis"
PY=$(command -v python3 || command -v python)
"$PY" scripts/analysis/make_tables_and_plots.py
