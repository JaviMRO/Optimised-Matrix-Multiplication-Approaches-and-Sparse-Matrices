# Full pipeline: build + tests (aborts if any test fails), environment, Part A, Part B, memory, analysis.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/run_all.ps1 [extra Suite options...]
# Close other applications first: the measuring JVMs commit 75 % of the RAM (-Xms = -Xmx).
. "$PSScriptRoot/common.ps1"

Write-Host ">> mvn clean verify (build + JUnit tests)"
& mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw "Build or tests failed: no benchmark was run." }

$envId = New-EnvId
& "$PSScriptRoot/run_dense.ps1" -EnvId $envId @args
& "$PSScriptRoot/run_sparse.ps1" -EnvId $envId @args
& "$PSScriptRoot/run_memory.ps1" -EnvId $envId @args

Write-Host ">> Analysis"
& python scripts/analysis/make_tables_and_plots.py
if ($LASTEXITCODE -ne 0) { throw "Analysis failed" }
