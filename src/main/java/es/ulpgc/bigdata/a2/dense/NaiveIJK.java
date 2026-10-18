package es.ulpgc.bigdata.a2.dense;

/**
 * Baseline: textbook i-j-k triple loop, copied verbatim from Assignment 1
 * (repo JaviMRO/bigdata-matrix-multiplication, commit cb967de, file assignment1/src/java/MatMul.java).
 *
 * Storage (shared by every dense method in this project): n x n matrix as a flat double[] in
 * row-major order, element (i, j) at index i*n + j. Each C element is written exactly once,
 * so C does not need to be zeroed beforehand.
 */
public final class NaiveIJK {

    private NaiveIJK() {
    }

    /** The kernel: textbook i-j-k triple loop on row-major arrays (element (i, j) is at i*n + j). */
    public static void multiply(double[] a, double[] b, double[] c, int n) {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double sum = 0.0;
                for (int k = 0; k < n; k++) {
                    sum += a[i * n + k] * b[k * n + j];
                }
                c[i * n + j] = sum;
            }
        }
    }
}
