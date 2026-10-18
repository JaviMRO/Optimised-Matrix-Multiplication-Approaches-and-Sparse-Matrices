package es.ulpgc.bigdata.a2.sparse;

import org.ejml.concurrency.EjmlConcurrency;
import org.ejml.data.DGrowArray;
import org.ejml.data.DMatrixRMaj;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.IGrowArray;
import org.ejml.ops.DConvertMatrixStruct;
import org.ejml.sparse.csc.CommonOps_DSCC;

/**
 * Sparse x sparse product with EJML ({@link CommonOps_DSCC#mult}, CSC format), plus the separate
 * dense-to-CSC conversion. EJML is forced to a single thread (the whole study is sequential).
 *
 * <p>The work arrays and the output matrix are kept between calls, like the pre-allocated C of the dense
 * methods: after the first call EJML only grows them if needed, so the measured kernel is the
 * multiplication itself. The first call (made during JMH warm-up) pays for sizing the output.
 */
public final class SparseMultiplier {

    static {
        forceSingleThread();
    }

    private final IGrowArray gw = new IGrowArray();
    private final DGrowArray gx = new DGrowArray();
    private final DMatrixSparseCSC out = new DMatrixSparseCSC(1, 1, 0);

    /** Disables EJML's concurrent code paths. Safe to call more than once. */
    public static void forceSingleThread() {
        EjmlConcurrency.USE_CONCURRENT = false;
        EjmlConcurrency.setMaxThreads(1);
    }

    /** C = A * B. The returned matrix is owned by this object and is overwritten by the next call. */
    public DMatrixSparseCSC multiply(DMatrixSparseCSC a, DMatrixSparseCSC b) {
        return CommonOps_DSCC.mult(a, b, out, gw, gx);
    }

    /** Converts a dense n x n row-major array to CSC, keeping every element that is exactly non-zero. */
    public static DMatrixSparseCSC denseToCsc(double[] dense, int n) {
        DMatrixRMaj wrapped = DMatrixRMaj.wrap(n, n, dense);
        return DConvertMatrixStruct.convert(wrapped, (DMatrixSparseCSC) null, 0.0);
    }

    /** Fraction of non-zero entries. */
    public static double density(DMatrixSparseCSC m) {
        return m.nz_length / ((double) m.numRows * m.numCols);
    }

    /** Analytic CSC storage in bytes: 8 (value) + 4 (row index) per non-zero, plus 4 * (n + 1) column pointers. */
    public static long cscBytes(long nnz, int numCols) {
        return 12L * nnz + 4L * (numCols + 1);
    }
}
