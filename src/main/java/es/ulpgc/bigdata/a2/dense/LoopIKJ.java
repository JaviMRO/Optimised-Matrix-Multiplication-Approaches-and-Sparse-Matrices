package es.ulpgc.bigdata.a2.dense;

import java.util.Arrays;

/**
 * Loop reordering i-k-j. Same arithmetic as the naive version, but the innermost loop walks
 * a row of B and a row of C with unit stride, instead of a column of B with stride n.
 */
public final class LoopIKJ {

    private LoopIKJ() {
    }

    /** C = A * B. C is cleared first (O(n^2), part of the measured time) and then accumulated. */
    public static void multiply(double[] a, double[] b, double[] c, int n) {
        Arrays.fill(c, 0, n * n, 0.0);
        for (int i = 0; i < n; i++) {
            int rowA = i * n;
            int rowC = i * n;
            for (int k = 0; k < n; k++) {
                double aik = a[rowA + k];
                int rowB = k * n;
                for (int j = 0; j < n; j++) {
                    c[rowC + j] += aik * b[rowB + j];
                }
            }
        }
    }
}
