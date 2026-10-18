# Records the machine/JVM description in env/<host>_<timestamp>.json and prints its env_id.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/run_env.ps1
. "$PSScriptRoot/common.ps1"
$id = New-EnvId
Write-Host "env_id: $id"
