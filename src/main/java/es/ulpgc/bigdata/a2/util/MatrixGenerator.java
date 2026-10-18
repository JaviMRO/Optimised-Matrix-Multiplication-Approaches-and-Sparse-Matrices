package es.ulpgc.bigdata.a2.util;

import java.util.SplittableRandom;

import org.ejml.data.DMatrixSparseCSC;

/**
 * Reproducible input matrices. Every generator takes an explicit seed and uses
 * {@link SplittableRandom}, so the same (n, density, seed) always gives the same matrix.
 * Values are uniform in [-1, 1). By convention A uses {@code seed} and B uses {@code seed + 1}.
 */
public final class MatrixGenerator {

    private MatrixGenerator() {
    }

    /** Dense n x n matrix, flat row-major (element (i, j) at i*n + j). */
    public static double[] dense(int n, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        double[] m = new double[Math.multiplyExact(n, n)];
        for (int i = 0; i < m.length; i++) {
            m[i] = uniform(rng);
        }
        return m;
    }

    /** Number of non-zeros of a synthetic sparse n x n matrix with the given density. */
    public static int targetNnz(int n, double density) {
        if (density < 0.0 || density > 1.0) {
            throw new IllegalArgumentException("density must be in [0, 1]: " + density);
        }
        return Math.toIntExact(Math.round(density * (double) n * n));
    }

    /**
     * Sparse n x n matrix in CSC format with exactly {@link #targetNnz} non-zeros at uniformly random,
     * distinct positions. Uses selection sampling (Knuth, TAOCP vol. 2, Algorithm S): the n^2 positions
     * are visited in column-major order and each one is kept with probability needed / remaining. This
     * gives a uniform sample without repetition, already sorted by column and row, so the CSC arrays
     * are filled directly.
     */
    public static DMatrixSparseCSC sparse(int n, double density, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        int nnz = targetNnz(n, density);
        DMatrixSparseCSC m = new DMatrixSparseCSC(n, n, nnz);
        long remaining = (long) n * n;
        int needed = nnz;
        int k = 0;
        for (int col = 0; col < n; col++) {
            m.col_idx[col] = k;
            for (int row = 0; row < n; row++, remaining--) {
                if (needed > 0 && rng.nextDouble() * remaining < needed) {
                    m.nz_rows[k] = row;
                    m.nz_values[k] = uniform(rng);
                    k++;
                    needed--;
                }
            }
        }
        m.col_idx[n] = k;
        m.nz_length = k;
        m.indicesSorted = true;
        return m;
    }

    /** Dense row-major copy of a CSC matrix (explicit zeros everywhere else). */
    public static double[] toDense(DMatrixSparseCSC s) {
        int rows = s.numRows;
        int cols = s.numCols;
        double[] d = new double[Math.multiplyExact(rows, cols)];
        for (int col = 0; col < cols; col++) {
            for (int p = s.col_idx[col]; p < s.col_idx[col + 1]; p++) {
                d[s.nz_rows[p] * cols + col] += s.nz_values[p];
            }
        }
        return d;
    }

    private static double uniform(SplittableRandom rng) {
        return rng.nextDouble() * 2.0 - 1.0;
    }
}
