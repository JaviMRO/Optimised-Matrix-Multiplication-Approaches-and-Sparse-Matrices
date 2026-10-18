# Peak heap used around one multiplication: one JVM per configuration, 5 repetitions each.
# Output: results/raw/memory_<host>_<part>_<timestamp>.csv
# Usage: powershell -ExecutionPolicy Bypass -File scripts/run_memory.ps1 [-EnvId ID] [extra Suite options...]
param([string]$EnvId = "")
. "$PSScriptRoot/common.ps1"
& "$PSScriptRoot/../data/fetch_suitesparse.ps1"
if (-not $EnvId) { $EnvId = New-EnvId }

Invoke-Suite (@("--part", "dense", "--mode", "memory", "--env-id", $EnvId) + $args)
Invoke-Suite (@("--part", "sparse", "--mode", "memory", "--matrices", (Get-MatrixNames), "--env-id", $EnvId) + $args)
