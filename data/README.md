# Input data

## Synthetic matrices

They are not stored anywhere: they are regenerated in memory from their seed every time
(`MatrixGenerator`, `SplittableRandom(seed)`, values uniform in [-1, 1)).

- Dense (Part A): `A = dense(n, seed)`, `B = dense(n, seed + 1)`.
- Sparse (Part B): `A = sparse(n, density, seed)`, `B = sparse(n, density, seed + 1)`, with exactly
  `round(density * n^2)` non-zeros at distinct, uniformly random positions (selection sampling).
  The dense methods of Part B receive the dense copies of these same matrices.
- Default seed: `42` (option `--seed` of `Suite`; it is recorded in every result row).

## Real matrices (SuiteSparse Matrix Collection)

Listed in [`matrices.csv`](matrices.csv) and downloaded by:

```powershell
powershell -ExecutionPolicy Bypass -File data/fetch_suitesparse.ps1   # Windows
sh data/fetch_suitesparse.sh                                          # Linux / macOS
```

The `.mtx.gz` files are not committed. The scripts verify a pinned SHA-256.

| Matrix | Group | n | nnz (full, both triangles) | Symmetry | Kind | Role |
|---|---|---|---|---|---|---|
| [bcsstk13](https://sparse.tamu.edu/HB/bcsstk13) | HB | 2,003 | 83,883 | symmetric | CFD problem | medium: dense still feasible |
| [bcsstk30](https://sparse.tamu.edu/HB/bcsstk30) | HB | 28,924 | 2,043,492 | symmetric | structural problem | large: dense does not fit the heap (OOM) |

Dimensions and nnz were checked on the SuiteSparse pages (2026-10-18) and again after download: the files
store one triangle (bcsstk13: 42,943 entries; bcsstk30: 1,036,208), which `MatrixMarketReader` expands to the
full nnz above.

**Download source.** On 2026-10-18 the SuiteSparse file server (`sparse.tamu.edu/MM/...`) answered
`403 Forbidden` to every download (MM, RB and MAT formats). Both matrices belong to the Harwell-Boeing (HB)
group, whose original files are also distributed by the NIST Matrix Market, so they are downloaded from
there. bcsstk13 has the same values; the NIST copy of **bcsstk30 is a pattern file** (structure only), so
its entries are read as 1.0. The sparsity structure, which is what determines the cost of the sparse
product and the nnz of the result, is identical (nnz checked).

The product computed for a real matrix M is `M * M` (A = B = M).
