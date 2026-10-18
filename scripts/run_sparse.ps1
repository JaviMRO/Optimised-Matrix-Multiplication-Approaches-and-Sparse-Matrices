# Part B: synthetic sparse matrices and the real SuiteSparse matrices (pilot + JMH).
# Methods: EJML sparse x sparse, dense-to-CSC conversion, naive dense and the best dense method of Part A.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/run_sparse.ps1 [-EnvId ID] [extra Suite options...]
param([string]$EnvId = "")
. "$PSScriptRoot/common.ps1"
& "$PSScriptRoot/../data/fetch_suitesparse.ps1"
if (-not $EnvId) { $EnvId = New-EnvId }

Invoke-Suite (@("--part", "sparse", "--matrices", (Get-MatrixNames), "--env-id", $EnvId) + $args)
