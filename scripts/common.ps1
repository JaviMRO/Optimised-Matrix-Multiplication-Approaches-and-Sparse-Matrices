# Shared helpers for the PowerShell scripts (dot-sourced, not run directly).
$ErrorActionPreference = "Stop"

# Every script runs from the repository root, so all paths below are relative and free of spaces.
$RepoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $RepoRoot

$Jar = "target/benchmarks.jar"
$SuiteClass = "es.ulpgc.bigdata.a2.bench.Suite"

# Maven downloads over HTTPS. On some Windows machines (antivirus/proxy TLS inspection) Java does not trust
# the certificate chain; the Windows certificate store does. Harmless when not needed.
if (-not $env:MAVEN_OPTS) { $env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=Windows-ROOT" }

function Assert-Jar {
    if (-not (Test-Path $Jar)) { throw "$Jar not found: run 'mvn clean verify' first (or scripts/run_all.ps1)." }
}

# Runs the Java driver with the given arguments and stops the script if it fails.
function Invoke-Suite([string[]]$SuiteArgs) {
    Assert-Jar
    Write-Host ">> Suite $($SuiteArgs -join ' ')"
    & java --enable-native-access=ALL-UNNAMED -cp $Jar $SuiteClass @SuiteArgs
    if ($LASTEXITCODE -ne 0) { throw "Suite failed (exit code $LASTEXITCODE): $($SuiteArgs -join ' ')" }
}

# Writes env/<host>_<timestamp>.json (in a JVM with the benchmark flags) and returns its env_id.
function New-EnvId([string[]]$ExtraArgs = @()) {
    Assert-Jar
    $out = & java --enable-native-access=ALL-UNNAMED -cp $Jar $SuiteClass --env-only @ExtraArgs
    if ($LASTEXITCODE -ne 0) { throw "Environment collection failed" }
    $out | ForEach-Object { Write-Host $_ }
    return ($out | Select-Object -Last 1).Trim()
}

# Names of the real matrices listed in data/matrices.csv, comma-separated.
function Get-MatrixNames {
    return ((Import-Csv "data/matrices.csv") | ForEach-Object { $_.name }) -join ","
}
