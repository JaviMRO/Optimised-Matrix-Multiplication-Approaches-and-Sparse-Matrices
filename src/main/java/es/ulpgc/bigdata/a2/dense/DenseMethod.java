package es.ulpgc.bigdata.a2.dense;

import java.util.Locale;

/** The four dense methods of Part A, selected by name in the benchmarks and runners. */
public enum DenseMethod {
    NAIVE, IKJ, BLOCKED, STRASSEN;

    /** C = A * B (n x n, row-major). blockSize is only used by BLOCKED, cutoff only by STRASSEN. */
    public void multiply(double[] a, double[] b, double[] c, int n, int blockSize, int cutoff) {
        switch (this) {
            case NAIVE -> NaiveIJK.multiply(a, b, c, n);
            case IKJ -> LoopIKJ.multiply(a, b, c, n);
            case BLOCKED -> Blocked.multiply(a, b, c, n, blockSize);
            case STRASSEN -> Strassen.multiply(a, b, c, n, cutoff);
        }
    }

    /** Lower-case name used in parameters, CSV files and plots. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DenseMethod parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
