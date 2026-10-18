# Assignment 2 — Optimised Matrix Multiplication Approaches and Sparse Matrices

Big Data course (Grado en Ciencia e Ingeniería de Datos, ULPGC, 2026-2027). Author: Javier Martín Rodríguez.
This repository contains the code, raw data, scripts and figures; the report is written separately (LaTeX/Overleaf).

## Research questions

1. **Part A.** How do loop reordering, cache blocking and Strassen's algorithm affect the time and memory of
   sequential dense matrix multiplication, as a function of the matrix size *n*?
2. **Part B.** Under which of the tested density conditions does a sparse representation pay off against the
   dense one?

## Design summary

| Aspect | Decision |
|---|---|
| Language / build | Java 25 (LTS), Maven, JMH 1.37 (annotation processor), uber-jar `target/benchmarks.jar` |
| Numbers | `double` |
| Dense storage | flat `double[]`, row-major, element (i, j) at `i*n + j`: the same layout as Assignment 1, used by every dense method |
| Baseline | `NaiveIJK`: verbatim copy of the Assignment 1 Java kernel (see below) |
| Optimisations | `LoopIKJ` (i-k-j order), `Blocked` (tiling, block size `bs`), `Strassen` (recursive, `cutoff`, i-k-j base case) |
| Sparse | EJML 0.46.1, `DMatrixSparseCSC` + `CommonOps_DSCC.mult` (CSC, sparse × sparse) |
| Threads | everything sequential; EJML forced to 1 thread (`EjmlConcurrency`) |
| Budget | 180 s per multiplication; heap `-Xmx` = `-Xms` = 75 % of the RAM (computed with OSHI) |
| Benchmark | JMH, 1 fork, 3 warm-up + 10 measured iterations, `-prof gc` |

### What is measured (time boundary)

* **Dense** (`DenseBenchmark`): only the kernel call. A, B and C are allocated in `@Setup(Level.Trial)`.
  Naive writes every C element once; IKJ, Blocked and Strassen clear C themselves (an O(n²) `Arrays.fill`,
  negligible against the O(n³) work). This replaces a `@Setup(Level.Invocation)` reset, which JMH discourages
  for short invocations and which would rule out `AverageTime` for small *n*. **Strassen's temporaries and
  padding are allocated inside the kernel and are part of its time and of `gc.alloc.rate.norm`**: they belong
  to the algorithm.
* **Sparse** (`SparseBenchmark`): only `CommonOps_DSCC.mult`. Inputs are built in CSC during the setup; the
  output matrix and EJML's work arrays are reused between invocations (like the pre-allocated dense C).
* **Conversion** (`ConversionBenchmark`): dense row-major → CSC of *both* operands, measured separately, never
  inside the sparse kernel.
* C is returned from the benchmark method (consumed by JMH's `Blackhole`).

### Strassen padding

The recursion needs an even size at every level until the size is ≤ `cutoff`. Instead of padding to the next
power of two, *n* is padded to the smallest *m* ≥ *n* of the form *q*·2^L with *q* ≤ `cutoff`
(e.g. *n* = 1000, cutoff 64 → *m* = 1008; *n* = 768, 1536 and 3072 need no padding). When *m* > *n*, A and B
are copied into zero-padded arrays and the result is copied back; these copies are part of the measured cost.
Each recursion level uses three *h*×*h* temporaries, allocated once per call (≈ *m*² doubles in total).

### Mode selection, time budget and failures

`Suite` (the Java driver) first runs a **pilot**: every configuration once, in its own JVM, under a
watchdog. Configurations are grouped in series (same method and parameters, increasing *n*). When one run
exceeds 180 s or the heap, it is recorded as `TIMEOUT`/`OOM` (with its limit) and the larger sizes of that
series are not run and are recorded with the same status — never with a made-up time. These rows are in
`*_pilot_*.csv` and in the separate `*_timeouts_*.csv`. The configurations that passed are then measured with
JMH: `SingleShotTime` if the pilot took ≥ 1 s, otherwise `AverageTime` with 1 s iterations (time per
multiplication is reported in both cases).

The block-size sweep (`bs` ∈ {16, 32, 64, 128}, *n* = 1024) and the cutoff sweep (`cutoff` ∈ {64, 128, 256},
*n* = 1024) store the best value in `results/tuning/<host>.properties`; the main dense study uses it. The
dense study then stores the best optimised method there, and Part B compares the sparse product against it
and against the naive method.

### Memory: three different quantities

1. **Analytic storage**: dense 8*n*² bytes per matrix; CSC 12·nnz + 4(*n*+1) bytes.
2. **Bytes allocated per operation**: JMH `gc.alloc.rate.norm` (shows Strassen's temporaries and the
   conversion cost).
3. **Peak heap used**: `MemoryRunner`, one JVM per configuration, 5 repetitions after one unrecorded warm-up.
   It resets the heap pools' peaks, runs one operation and reports max(sum of pool peaks, used heap right after).
   These JVMs use `-XX:+UseSerialGC -XX:-UseTLAB`: with G1 the pools are reported in whole regions (MiBs) and
   TLABs are counted as used when they are handed out, both of which hide temporaries; with these flags the
   accounting is exact to the byte (checked: Strassen *n* = 256 shows its 393,216 B of temporaries). The
   timing runs keep the default G1 collector.

### Inputs

`MatrixGenerator` with `SplittableRandom(seed)`, values uniform in [-1, 1). A uses `seed`, B uses `seed + 1`
(default seed 42, recorded in every row). Sparse matrices have exactly `round(density·n²)` non-zeros at
distinct uniform positions (selection sampling), and the dense methods of Part B receive the dense copies of
the same matrices. Real matrices: see [`data/README.md`](data/README.md) (bcsstk13, n = 2003, and
bcsstk30, n = 28,924, from the SuiteSparse collection; product A·A).

Note on `nnz_C`: for EJML it is the number of stored entries (structural non-zeros); for the dense methods it
is the number of entries that are exactly non-zero, which can be slightly smaller because of cancellation
(bcsstk13: 396,773 vs 395,923).

## Requirements

| Tool | Version used on DESKTOP-Q8Q1E0H |
|---|---|
| JDK | Oracle Java 25.0.3 LTS (HotSpot) |
| Maven | 3.9.16 |
| Python | 3.10.2 with `scripts/analysis/requirements.txt` (numpy 2.2.3, pandas 2.3.3, matplotlib 3.10.0) |

Libraries (from the `pom.xml`): JMH 1.37, EJML 0.46.1, OSHI 6.12.0, JUnit 5.14.4. The exact machine and JVM
description of every run is in `env/<host>_<timestamp>.json` (CPU model, cores, caches, RAM, OS, Java version,
JVM flags, effective `-Xmx`, library versions).

If Maven fails with `PKIX path building failed` (antivirus or proxy inspecting HTTPS on Windows), use the
Windows certificate store: `$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` (the PowerShell
scripts set this automatically).

## How to run

All commands from the repository root. **Close other applications first**: the measuring JVMs commit
75 % of the RAM at start-up (`-Xms` = `-Xmx`); the driver checks the free commit charge and stops with a
message if it is not enough.

```powershell
pip install -r scripts/analysis/requirements.txt

# Everything: build + JUnit tests (aborts if any fails), environment, Part A, Part B, memory, analysis
powershell -ExecutionPolicy Bypass -File scripts/run_all.ps1

# Or step by step
mvn clean verify                                                       # build + tests -> target/benchmarks.jar
powershell -ExecutionPolicy Bypass -File scripts/run_env.ps1            # env/<host>_<ts>.json
powershell -ExecutionPolicy Bypass -File scripts/run_dense.ps1          # block sweep, cutoff sweep, dense study
powershell -ExecutionPolicy Bypass -File data/fetch_suitesparse.ps1     # real matrices (+ SHA-256 check)
powershell -ExecutionPolicy Bypass -File scripts/run_sparse.ps1         # Part B
powershell -ExecutionPolicy Bypass -File scripts/run_memory.ps1         # peak heap
python scripts/analysis/make_tables_and_plots.py                        # tables + figures
```

Linux/macOS: the same scripts exist as `.sh` (`sh scripts/run_all.sh`).

Extra options are passed to every `Suite` call, e.g. `scripts/run_dense.ps1 --iterations 7` (fewer measured
iterations, if the total time is not feasible; document it in the report) or `--plan-only` (pilot only, prints
the estimated JMH time).

The driver can also be called directly:

```powershell
java -cp target/benchmarks.jar es.ulpgc.bigdata.a2.bench.Suite --part dense --sizes 128,256,512 --methods naive,ikj
java -cp target/benchmarks.jar es.ulpgc.bigdata.a2.bench.Suite --part sparse --mode memory --densities 0.01
```

Options: `--part dense|block_sweep|cutoff_sweep|sparse`, `--mode bench|memory`, `--sizes`, `--methods`,
`--densities`, `--matrices`, `--block-sizes`, `--cutoffs`, `--block-size`, `--cutoff`, `--seed`,
`--timeout` (s, default 180), `--warmup` (3), `--iterations` (10), `--reps` (memory, 5),
`--heap-fraction` (0.75), `--avgt-threshold` (s, 1.0), `--env-id`, `--out-dir`, `--plan-only`.

Plain JMH also works (then pass the heap yourself):

```powershell
java -jar target/benchmarks.jar DenseBenchmark -p method=ikj -p n=1024 -jvmArgs "-Xmx12g -Xms12g" -prof gc -rf json -rff results/raw/manual.json
```

## Outputs

| Path | Content |
|---|---|
| `env/<host>_<ts>.json` | machine/JVM description; its name is the `env_id` |
| `results/raw/<host>_<part>_<ts>.json` | JMH results (JSON; `jvmArgs` contain `-Da2.host` and `-Da2.envId`) |
| `results/raw/<host>_<part>_pilot_<ts>.csv` | pilot run of every configuration (status, time, nnz) |
| `results/raw/<host>_<part>_timeouts_<ts>.csv` | configurations that hit TIMEOUT/OOM, with their limit |
| `results/raw/memory_<host>_<part>_<ts>.csv` | peak heap measurements |
| `results/tuning/<host>.properties` | best block size, cutoff and dense method found on that host |
| `results/tables/normalized_all.csv` | every raw row in the normalized format (no row dropped) |
| `results/tables/<host>/*.tex, *.csv` | booktabs tables (median and IQR, repetitions, units) |
| `results/figures/<host>/*.pdf, *.png` | figures (PDF for Overleaf) |

Raw files are never deleted or overwritten (every run has its own timestamp). Results of different machines
are never mixed: every table and figure is produced per host.

Normalized CSV columns: `host, env_id, part, method, n, density, nnz_A, nnz_B, nnz_C, block_size, cutoff, seed,
repetition, time_s, status (OK/TIMEOUT/OOM/ERROR), metric, value, unit`, plus `matrix` (SuiteSparse name or
`none`) and `note` (e.g. the limit that was hit); the analysis adds `kind, mode, source, run_ts`.

## Tests

`mvn clean verify` runs the JUnit 5 tests before packaging:

* hand-computed 2×2 and 3×3 products, identity and zero matrices, for every method (with small block sizes
  and cutoffs so that tile edges and the recursion are exercised; C is pre-filled with NaN to catch kernels
  that do not overwrite it);
* every method against EJML's dense `CommonOps_DDRM.mult` for *n* ∈ {1, 2, 3, 5, 16, 31, 64, 100, 127, 129,
  200, 256}, and against the naive method for *n* ∈ {300, 512, 768, 1000} with the study parameters;
* sparse: EJML's product converted to dense against the naive dense product, generator properties (exact nnz,
  distinct positions, reproducibility), dense↔CSC round trip, Matrix Market reader (general, symmetric,
  skew-symmetric, pattern), and bcsstk13 (nnz = 83,883 and A·A correct) when it has been downloaded.

Tolerance: |C − C_ref| ≤ atol + rtol·|C_ref| element-wise, rtol = 1e-10, atol = 1e-12·*n*. Strassen's errors were
measured (max 2e-12 at *n* = 256 with cutoff 1; 5e-13 at *n* = 1024 with cutoff 64): 10-100× those of the
classical loops but far below atol, so it uses the same tolerance (see the comment in `DenseMethodsTest`).
The kernels support square matrices only.

## Baseline from Assignment 1

`NaiveIJK.multiply` is copied verbatim from the Java implementation of Assignment 1:
<https://github.com/JaviMRO/bigdata-matrix-multiplication>, commit
`cb967ded2fe1c0e8488c95a8c61ac0c8e4dc316e`, file `assignment1/src/java/MatMul.java` (method `multiply`).
The storage layout (flat row-major `double[]`) is also the one of Assignment 1.

## Generative AI use

Claude Code (Anthropic) was used to generate and debug code, scripts and documentation in this repository.
The author reviewed the code, validated the results and is responsible for all content.
