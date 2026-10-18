# Downloads the real matrices listed in data/matrices.csv and verifies their SHA-256.
# Usage (from the repository root):  powershell -ExecutionPolicy Bypass -File data/fetch_suitesparse.ps1
# The .mtx.gz files are not committed; the reader decompresses them on the fly.
$ErrorActionPreference = "Stop"
$dataDir = $PSScriptRoot
$list = Import-Csv (Join-Path $dataDir "matrices.csv")

foreach ($m in $list) {
    $file = Join-Path $dataDir ($m.name + ".mtx.gz")
    if (-not (Test-Path $file)) {
        Write-Host "Downloading $($m.group)/$($m.name) from $($m.download_url)"
        Invoke-WebRequest -Uri $m.download_url -OutFile $file -UseBasicParsing
    }
    $hash = (Get-FileHash -Algorithm SHA256 $file).Hash.ToLower()
    if ($hash -ne $m.sha256) {
        Remove-Item $file
        throw "Checksum mismatch for $($m.name): got $hash, expected $($m.sha256) (file deleted)"
    }
    Write-Host "OK  $($m.name)  n=$($m.n)  nnz=$($m.nnz)  sha256=$hash"
}
