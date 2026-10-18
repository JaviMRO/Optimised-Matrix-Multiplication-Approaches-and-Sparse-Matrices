package es.ulpgc.bigdata.a2.dense;

import java.util.Arrays;

/**
 * Strassen's algorithm: 7 recursive half-size products instead of 8, giving O(n^log2(7)) = O(n^2.81).
 * Below {@code cutoff} the recursion stops and an i-k-j loop (as in {@link LoopIKJ}) is used.
 *
 * <p>Sub-matrices are views (array, offset, row stride) into the parent arrays, so the quadrants of
 * A, B and C are never copied. Each recursion level needs three h x h temporaries (h = half size):
 * two for the sums/differences of quadrants and one for the product M_i. They are allocated once per
 * call to {@link #multiply}, one set per level (about m^2 doubles in total), and this allocation is
 * part of the measured time and of the bytes allocated per operation: it belongs to the algorithm.
 *
 * <p>Padding: the recursion needs an even size at every level until the size is at most cutoff.
 * Instead of padding to the next power of two, n is padded to the smallest m >= n of the form
 * q * 2^L with q <= cutoff (e.g. n = 1000, cutoff = 64: m = 63 * 16 = 1008; n = 768, 1536, 3072 need no
 * padding). If m > n, A and B are copied into zero-padded m x m arrays and the n x n corner of the
 * padded result is copied back to C; these copies are also part of the measured cost.
 */
public final class Strassen {

    private Strassen() {
    }

    /** C = A * B (n x n, row-major). C is fully overwritten. */
    public static void multiply(double[] a, double[] b, double[] c, int n, int cutoff) {
        if (cutoff <= 0) {
            throw new IllegalArgumentException("cutoff must be positive: " + cutoff);
        }
        int m = paddedSize(n, cutoff);
        Workspace ws = new Workspace(m, cutoff);
        if (m == n) {
            recurse(a, 0, n, b, 0, n, c, 0, n, n, cutoff, ws, 0);
            return;
        }
        double[] ap = pad(a, n, m);
        double[] bp = pad(b, n, m);
        double[] cp = new double[m * m];
        recurse(ap, 0, m, bp, 0, m, cp, 0, m, m, cutoff, ws, 0);
        for (int i = 0; i < n; i++) {
            System.arraycopy(cp, i * m, c, i * n, n);
        }
    }

    /** Smallest m >= n such that halving m repeatedly gives even sizes until the size is <= cutoff. */
    public static int paddedSize(int n, int cutoff) {
        int levels = 0;
        while (ceilDiv(n, 1 << levels) > cutoff) {
            levels++;
        }
        return ceilDiv(n, 1 << levels) << levels;
    }

    private static int ceilDiv(int x, int y) {
        return (x + y - 1) / y;
    }

    private static double[] pad(double[] x, int n, int m) {
        double[] p = new double[m * m];
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, i * n, p, i * m, n);
        }
        return p;
    }

    /** Temporaries t1, t2 and p for every recursion level (level 0 works on half of m). */
    private static final class Workspace {
        final double[][] t1;
        final double[][] t2;
        final double[][] p;

        Workspace(int m, int cutoff) {
            int levels = 0;
            for (int size = m; size > cutoff; size /= 2) {
                levels++;
            }
            t1 = new double[levels][];
            t2 = new double[levels][];
            p = new double[levels][];
            int h = m / 2;
            for (int l = 0; l < levels; l++, h /= 2) {
                t1[l] = new double[h * h];
                t2[l] = new double[h * h];
                p[l] = new double[h * h];
            }
        }
    }

    /**
     * C view = A view * B view, all of size m x m. A view is (array, offset, stride): element (i, j)
     * of the view is array[offset + i * stride + j].
     */
    private static void recurse(double[] A, int ao, int as, double[] B, int bo, int bs,
                                double[] C, int co, int cs, int m, int cutoff, Workspace ws, int level) {
        if (m <= cutoff) {
            baseIKJ(A, ao, as, B, bo, bs, C, co, cs, m);
            return;
        }
        int h = m / 2;
        int a11 = ao, a12 = ao + h, a21 = ao + h * as, a22 = ao + h * as + h;
        int b11 = bo, b12 = bo + h, b21 = bo + h * bs, b22 = bo + h * bs + h;
        int c11 = co, c12 = co + h, c21 = co + h * cs, c22 = co + h * cs + h;
        double[] t1 = ws.t1[level];
        double[] t2 = ws.t2[level];
        double[] p = ws.p[level];
        int next = level + 1;

        // M1 = (A11 + A22)(B11 + B22)  ->  C11 = M1, C22 = M1
        add(A, a11, as, A, a22, as, t1, h);
        add(B, b11, bs, B, b22, bs, t2, h);
        recurse(t1, 0, h, t2, 0, h, p, 0, h, h, cutoff, ws, next);
        store(p, C, c11, cs, h);
        store(p, C, c22, cs, h);

        // M2 = (A21 + A22) B11  ->  C21 = M2, C22 -= M2
        add(A, a21, as, A, a22, as, t1, h);
        recurse(t1, 0, h, B, b11, bs, p, 0, h, h, cutoff, ws, next);
        store(p, C, c21, cs, h);
        accumulate(p, -1.0, C, c22, cs, h);

        // M3 = A11 (B12 - B22)  ->  C12 = M3, C22 += M3
        sub(B, b12, bs, B, b22, bs, t2, h);
        recurse(A, a11, as, t2, 0, h, p, 0, h, h, cutoff, ws, next);
        store(p, C, c12, cs, h);
        accumulate(p, 1.0, C, c22, cs, h);

        // M4 = A22 (B21 - B11)  ->  C11 += M4, C21 += M4
        sub(B, b21, bs, B, b11, bs, t2, h);
        recurse(A, a22, as, t2, 0, h, p, 0, h, h, cutoff, ws, next);
        accumulate(p, 1.0, C, c11, cs, h);
        accumulate(p, 1.0, C, c21, cs, h);

        // M5 = (A11 + A12) B22  ->  C11 -= M5, C12 += M5
        add(A, a11, as, A, a12, as, t1, h);
        recurse(t1, 0, h, B, b22, bs, p, 0, h, h, cutoff, ws, next);
        accumulate(p, -1.0, C, c11, cs, h);
        accumulate(p, 1.0, C, c12, cs, h);

        // M6 = (A21 - A11)(B11 + B12)  ->  C22 += M6
        sub(A, a21, as, A, a11, as, t1, h);
        add(B, b11, bs, B, b12, bs, t2, h);
        recurse(t1, 0, h, t2, 0, h, p, 0, h, h, cutoff, ws, next);
        accumulate(p, 1.0, C, c22, cs, h);

        // M7 = (A12 - A22)(B21 + B22)  ->  C11 += M7
        sub(A, a12, as, A, a22, as, t1, h);
        add(B, b21, bs, B, b22, bs, t2, h);
        recurse(t1, 0, h, t2, 0, h, p, 0, h, h, cutoff, ws, next);
        accumulate(p, 1.0, C, c11, cs, h);
    }

    /** Base case: C view = A view * B view with the i-k-j loop order. */
    private static void baseIKJ(double[] A, int ao, int as, double[] B, int bo, int bs,
                                double[] C, int co, int cs, int m) {
        for (int i = 0; i < m; i++) {
            int rowA = ao + i * as;
            int rowC = co + i * cs;
            Arrays.fill(C, rowC, rowC + m, 0.0);
            for (int k = 0; k < m; k++) {
                double aik = A[rowA + k];
                int rowB = bo + k * bs;
                for (int j = 0; j < m; j++) {
                    C[rowC + j] += aik * B[rowB + j];
                }
            }
        }
    }

    /** out (contiguous h x h) = X view + Y view. */
    private static void add(double[] X, int xo, int xs, double[] Y, int yo, int ys, double[] out, int h) {
        for (int i = 0; i < h; i++) {
            int rx = xo + i * xs, ry = yo + i * ys, ro = i * h;
            for (int j = 0; j < h; j++) {
                out[ro + j] = X[rx + j] + Y[ry + j];
            }
        }
    }

    /** out (contiguous h x h) = X view - Y view. */
    private static void sub(double[] X, int xo, int xs, double[] Y, int yo, int ys, double[] out, int h) {
        for (int i = 0; i < h; i++) {
            int rx = xo + i * xs, ry = yo + i * ys, ro = i * h;
            for (int j = 0; j < h; j++) {
                out[ro + j] = X[rx + j] - Y[ry + j];
            }
        }
    }

    /** C view = p (contiguous h x h). */
    private static void store(double[] p, double[] C, int co, int cs, int h) {
        for (int i = 0; i < h; i++) {
            System.arraycopy(p, i * h, C, co + i * cs, h);
        }
    }

    /** C view += sign * p (contiguous h x h). */
    private static void accumulate(double[] p, double sign, double[] C, int co, int cs, int h) {
        for (int i = 0; i < h; i++) {
            int rc = co + i * cs, rp = i * h;
            for (int j = 0; j < h; j++) {
                C[rc + j] += sign * p[rp + j];
            }
        }
    }
}
