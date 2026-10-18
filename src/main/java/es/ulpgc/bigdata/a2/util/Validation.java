package es.ulpgc.bigdata.a2.util;

/**
 * Element-wise comparison against a reference result:
 * an element passes if |C - Cref| <= atol + rtol * |Cref|.
 * Default tolerances: rtol = 1e-10 and atol = 1e-12 * n (the absolute rounding error of a dot product
 * of length n grows roughly linearly with n).
 */
public final class Validation {

    public static final double DEFAULT_RTOL = 1e-10;

    private Validation() {
    }

    public static double defaultAtol(int n) {
        return 1e-12 * n;
    }

    /** Outcome of a comparison: number of failing elements and the largest absolute error. */
    public record Result(long failures, double maxAbsError, long worstIndex) {
        public boolean ok() {
            return failures == 0;
        }
    }

    public static Result compare(double[] c, double[] ref, double rtol, double atol) {
        if (c.length != ref.length) {
            throw new IllegalArgumentException("length mismatch: " + c.length + " vs " + ref.length);
        }
        long failures = 0;
        double maxErr = 0.0;
        long worst = -1;
        for (int i = 0; i < c.length; i++) {
            double err = Math.abs(c[i] - ref[i]);
            // Written as !(err <= tol) so that NaN also counts as a failure.
            if (!(err <= atol + rtol * Math.abs(ref[i]))) {
                failures++;
            }
            if (err > maxErr || Double.isNaN(err)) {
                maxErr = err;
                worst = i;
            }
        }
        return new Result(failures, maxErr, worst);
    }

    public static Result compare(double[] c, double[] ref, int n) {
        return compare(c, ref, DEFAULT_RTOL, defaultAtol(n));
    }
}
