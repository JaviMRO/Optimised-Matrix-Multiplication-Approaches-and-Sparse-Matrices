package es.ulpgc.bigdata.a2.dense;

import java.util.Arrays;

/**
 * Cache blocking (tiling). The i, k and j ranges are cut into tiles of bs elements so that
 * one tile of A, one of B and one of C (3 * bs^2 doubles) can stay in cache while they are reused.
 * Inside a tile the loop order is i-k-j, as in {@link LoopIKJ}. n does not need to be a
 * multiple of bs: the last tile in each direction is simply smaller.
 */
public final class Blocked {

    private Blocked() {
    }

    /** C = A * B with block size bs. C is cleared first (O(n^2), part of the measured time). */
    public static void multiply(double[] a, double[] b, double[] c, int n, int bs) {
        if (bs <= 0) {
            throw new IllegalArgumentException("block size must be positive: " + bs);
        }
        Arrays.fill(c, 0, n * n, 0.0);
        for (int ii = 0; ii < n; ii += bs) {
            int iEnd = Math.min(ii + bs, n);
            for (int kk = 0; kk < n; kk += bs) {
                int kEnd = Math.min(kk + bs, n);
                for (int jj = 0; jj < n; jj += bs) {
                    int jEnd = Math.min(jj + bs, n);
                    // Multiply tile A[ii.., kk..] by tile B[kk.., jj..] into tile C[ii.., jj..].
                    for (int i = ii; i < iEnd; i++) {
                        int rowA = i * n;
                        int rowC = i * n;
                        for (int k = kk; k < kEnd; k++) {
                            double aik = a[rowA + k];
                            int rowB = k * n;
                            for (int j = jj; j < jEnd; j++) {
                                c[rowC + j] += aik * b[rowB + j];
                            }
                        }
                    }
                }
            }
        }
    }
}
