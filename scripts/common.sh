#!/usr/bin/env sh
# Shared helpers for the POSIX scripts (sourced, not run directly).
set -eu
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"
JAR="target/benchmarks.jar"
SUITE="es.ulpgc.bigdata.a2.bench.Suite"

assert_jar() {
    [ -f "$JAR" ] || { echo "$JAR not found: run 'mvn clean verify' first (or scripts/run_all.sh)." >&2; exit 1; }
}

# Runs the Java driver with the given arguments (stops the script on failure because of set -e).
suite() {
    assert_jar
    echo ">> Suite $*"
    java --enable-native-access=ALL-UNNAMED -cp "$JAR" "$SUITE" "$@"
}

# Writes env/<host>_<timestamp>.json and prints its env_id (last line).
new_env_id() {
    assert_jar
    java --enable-native-access=ALL-UNNAMED -cp "$JAR" "$SUITE" --env-only | tee /dev/stderr | tail -n 1
}

# Names of the real matrices in data/matrices.csv, comma-separated.
matrix_names() {
    tail -n +2 data/matrices.csv | cut -d, -f1 | paste -sd, -
}
