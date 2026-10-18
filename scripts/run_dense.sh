#!/usr/bin/env sh
# Part A: block-size sweep, Strassen cutoff sweep, then the main dense study (pilot + JMH).
# Usage: sh scripts/run_dense.sh [extra Suite options...]   (set ENV_ID to reuse an environment id)
. "$(dirname "$0")/common.sh"
ENV_ID=${ENV_ID:-$(new_env_id)}
suite --part block_sweep --env-id "$ENV_ID" "$@"
suite --part cutoff_sweep --env-id "$ENV_ID" "$@"
suite --part dense --env-id "$ENV_ID" "$@"
