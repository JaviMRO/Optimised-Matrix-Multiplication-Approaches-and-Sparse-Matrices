package es.ulpgc.bigdata.a2.bench;

import java.io.IOException;

import org.ejml.data.DMatrixSparseCSC;

import es.ulpgc.bigdata.a2.dense.DenseMethod;
import es.ulpgc.bigdata.a2.sparse.SparseMultiplier;
import es.ulpgc.bigdata.a2.util.Inputs;

/**
 * One prepared configuration outside JMH (used by the pilot and the memory runner): the inputs are
 * built in {@link #prepare}, and {@link #run} executes only the operation that JMH measures.
 * Uses exactly the same inputs and kernels as the JMH benchmarks.
 */
public final class Operation {

    private final RunConfig cfg;
    private final int n;
    private final long nnzA;
    private final long nnzB;
    private final boolean freshOutput;

    private DenseMethod denseMethod;
    private double[] a;
    private double[] b;
    private double[] c;

    private Inputs.Sparse sparse;
    private SparseMultiplier multiplier;
    private DMatrixSparseCSC lastSparse;
    private DMatrixSparseCSC convertedA;

    private Operation(RunConfig cfg, int n, long nnzA, long nnzB, boolean freshOutput) {
        this.cfg = cfg;
        this.n = n;
        this.nnzA = nnzA;
        this.nnzB = nnzB;
        this.freshOutput = freshOutput;
    }

    /**
     * Builds the inputs. With freshOutput = true the sparse product allocates a new output matrix on every
     * run (used by the memory runner, so that the output shows up in the heap peak); otherwise the output is
     * reused as in the JMH benchmark. Dense methods always write into a C allocated here, as in JMH.
     */
    public static Operation prepare(RunConfig cfg, boolean freshOutput) throws IOException {
        SparseMultiplier.forceSingleThread();
        switch (cfg.method()) {
            case "ejml" -> {
                Inputs.Sparse s = Inputs.sparse(cfg.matrix(), cfg.n(), cfg.density(), cfg.seed());
                Operation op = new Operation(cfg, s.n(), s.a().nz_length, s.b().nz_length, freshOutput);
                op.sparse = s;
                op.multiplier = new SparseMultiplier();
                return op;
            }
            case "convert" -> {
                Inputs.Dense d = Inputs.dense(cfg.matrix(), cfg.n(), cfg.density(), cfg.seed());
                Operation op = new Operation(cfg, d.n(), d.nnzA(), d.nnzB(), freshOutput);
                op.a = d.a();
                op.b = d.b();
                return op;
            }
            default -> {
                DenseMethod m = DenseMethod.parse(cfg.method());
                Inputs.Dense d = Inputs.dense(cfg.matrix(), cfg.n(), cfg.density(), cfg.seed());
                Operation op = new Operation(cfg, d.n(), d.nnzA(), d.nnzB(), freshOutput);
                op.denseMethod = m;
                op.a = d.a();
                op.b = d.b();
                op.c = new double[Math.multiplyExact(d.n(), d.n())];
                return op;
            }
        }
    }

    /** The measured operation. */
    public void run() {
        switch (cfg.method()) {
            case "ejml" -> {
                if (freshOutput) {
                    multiplier = new SparseMultiplier();
                }
                lastSparse = multiplier.multiply(sparse.a(), sparse.b());
            }
            case "convert" -> {
                // Both operands are converted: that is what a dense-to-sparse switch would cost.
                convertedA = SparseMultiplier.denseToCsc(a, n);
                lastSparse = SparseMultiplier.denseToCsc(b, n);
            }
            default -> denseMethod.multiply(a, b, c, n, cfg.blockSize(), cfg.cutoff());
        }
    }

    public int n() {
        return n;
    }

    public long nnzA() {
        return nnzA;
    }

    public long nnzB() {
        return nnzB;
    }

    /** Non-zeros of the product after {@link #run}; -1 for the conversion, which has no product. */
    public long nnzC() {
        return switch (cfg.method()) {
            case "ejml" -> lastSparse == null ? -1 : lastSparse.nz_length;
            case "convert" -> -1;
            default -> {
                long count = 0;
                for (double v : c) {
                    if (v != 0.0) {
                        count++;
                    }
                }
                yield count;
            }
        };
    }

    /**
     * Analytic storage of the matrices involved, in bytes: dense 8 n^2 per matrix, CSC 12 nnz + 4 (n + 1).
     * Dense methods: A, B and C. EJML: A, B and C in CSC. Conversion: dense A and B plus their CSC copies.
     */
    public long analyticBytes() {
        long dense = 8L * n * n;
        return switch (cfg.method()) {
            case "ejml" -> SparseMultiplier.cscBytes(nnzA, n) + SparseMultiplier.cscBytes(nnzB, n)
                + SparseMultiplier.cscBytes(Math.max(0, nnzC()), n);
            case "convert" -> 2 * dense + SparseMultiplier.cscBytes(nnzA, n) + SparseMultiplier.cscBytes(nnzB, n);
            default -> 3 * dense;
        };
    }
}
