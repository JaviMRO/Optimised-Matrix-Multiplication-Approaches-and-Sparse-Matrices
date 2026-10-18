#!/usr/bin/env sh
# Part B: synthetic sparse matrices and the real SuiteSparse matrices (pilot + JMH).
# Usage: sh scripts/run_sparse.sh [extra Suite options...]   (set ENV_ID to reuse an environment id)
. "$(dirname "$0")/common.sh"
sh data/fetch_suitesparse.sh
ENV_ID=${ENV_ID:-$(new_env_id)}
suite --part sparse --matrices "$(matrix_names)" --env-id "$ENV_ID" "$@"
