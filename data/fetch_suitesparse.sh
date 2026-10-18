#!/usr/bin/env sh
# Downloads the real matrices listed in data/matrices.csv and verifies their SHA-256.
# Usage (from the repository root):  sh data/fetch_suitesparse.sh
# The .mtx.gz files are not committed; the reader decompresses them on the fly.
set -eu
DATA_DIR=$(cd "$(dirname "$0")" && pwd)

if command -v sha256sum >/dev/null 2>&1; then SHA="sha256sum"; else SHA="shasum -a 256"; fi

# Columns: name,group,n,nnz,symmetry,kind,values_in_file,suitesparse_page,download_url,sha256
tail -n +2 "$DATA_DIR/matrices.csv" | while IFS=, read -r name group n nnz sym kind values page url sha; do
    file="$DATA_DIR/$name.mtx.gz"
    if [ ! -f "$file" ]; then
        echo "Downloading $group/$name from $url"
        curl -fsSL -o "$file" "$url"
    fi
    got=$($SHA "$file" | cut -d' ' -f1)
    if [ "$got" != "$sha" ]; then
        rm -f "$file"
        echo "Checksum mismatch for $name: got $got, expected $sha (file deleted)" >&2
        exit 1
    fi
    echo "OK  $name  n=$n  nnz=$nnz  sha256=$got"
done
