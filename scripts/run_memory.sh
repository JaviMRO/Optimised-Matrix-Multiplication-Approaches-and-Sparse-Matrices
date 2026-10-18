#!/usr/bin/env sh
# Peak heap used around one multiplication: one JVM per configuration, 5 repetitions each.
# Usage: sh scripts/run_memory.sh [extra Suite options...]   (set ENV_ID to reuse an environment id)
. "$(dirname "$0")/common.sh"
sh data/fetch_suitesparse.sh
ENV_ID=${ENV_ID:-$(new_env_id)}
suite --part dense --mode memory --env-id "$ENV_ID" "$@"
suite --part sparse --mode memory --matrices "$(matrix_names)" --env-id "$ENV_ID" "$@"
