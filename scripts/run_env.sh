#!/usr/bin/env sh
# Records the machine/JVM description in env/<host>_<timestamp>.json and prints its env_id.
. "$(dirname "$0")/common.sh"
echo "env_id: $(new_env_id)"
