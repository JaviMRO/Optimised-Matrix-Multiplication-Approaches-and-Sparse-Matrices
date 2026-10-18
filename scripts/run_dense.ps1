# Part A: block-size sweep, Strassen cutoff sweep, then the main dense study (pilot + JMH).
# The sweeps store the best block size / cutoff in results/tuning/<host>.properties, which the main study uses.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/run_dense.ps1 [-EnvId ID] [extra Suite options...]
#   e.g. ... run_dense.ps1 --iterations 7      (extra options are passed to every Suite call)
param([string]$EnvId = "")
. "$PSScriptRoot/common.ps1"
if (-not $EnvId) { $EnvId = New-EnvId }

Invoke-Suite (@("--part", "block_sweep", "--env-id", $EnvId) + $args)
Invoke-Suite (@("--part", "cutoff_sweep", "--env-id", $EnvId) + $args)
Invoke-Suite (@("--part", "dense", "--env-id", $EnvId) + $args)
