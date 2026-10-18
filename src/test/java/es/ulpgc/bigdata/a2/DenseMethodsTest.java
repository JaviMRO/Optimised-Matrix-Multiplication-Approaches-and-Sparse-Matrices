package es.ulpgc.bigdata.a2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import es.ulpgc.bigdata.a2.dense.DenseMethod;
import es.ulpgc.bigdata.a2.dense.Strassen;
import es.ulpgc.bigdata.a2.util.MatrixGenerator;
import es.ulpgc.bigdata.a2.util.Validation;

/**
 * Correctness of the four dense methods: hand-computed cases and comparison against an independent
 * reference (EJML's dense CommonOps_DDRM.mult). Only square matrices are supported by the kernels.
 */
class DenseMethodsTest {

    /**
     * Strassen is less stable than the classical algorithm: each level adds and subtracts quadrants
     * before multiplying, so rounding errors grow with the recursion depth (the worst-case bound grows
     * like n^log2(12) instead of n). Measured against the naive result (inputs in [-1, 1)), the maximum
     * absolute error was 4e-14 (n=256, cutoff 64), 2e-12 (n=256, cutoff 1, deepest recursion) and
     * 5e-13 (n=1024, cutoff 64), i.e. 10-100x the error of the classical loops but still more than
     * 100x below the default atol = 1e-12 * n. So no larger tolerance is needed; the factor is kept here
     * so it can be raised if bigger sizes or smaller cutoffs are ever tested.
     */
    static final double STRASSEN_ATOL_FACTOR = 1.0;

    /** Every method with small block sizes / cutoffs, so tiling edges and recursion are exercised. */
    static Stream<Arguments> variants() {
        List<Arguments> v = new ArrayList<>();
        v.add(Arguments.of(DenseMethod.NAIVE, 0, 0));
        v.add(Arguments.of(DenseMethod.IKJ, 0, 0));
        for (int bs : new int[] {1, 2, 7, 16, 64}) {
            v.add(Arguments.of(DenseMethod.BLOCKED, bs, 0));
        }
        for (int cutoff : new int[] {1, 2, 8, 16, 64}) {
            v.add(Arguments.of(DenseMethod.STRASSEN, 0, cutoff));
        }
        return v.stream();
    }

    static double[] multiply(DenseMethod m, double[] a, double[] b, int n, int bs, int cutoff) {
        double[] c = new double[n * n];
        // Garbage in C must not leak into the result (C is reused between JMH invocations).
        java.util.Arrays.fill(c, Double.NaN);
        m.multiply(a, b, c, n, bs, cutoff);
        return c;
    }

    static double atol(DenseMethod m, int n) {
        return Validation.defaultAtol(n) * (m == DenseMethod.STRASSEN ? STRASSEN_ATOL_FACTOR : 1.0);
    }

    @ParameterizedTest(name = "{0} bs={1} cutoff={2}")
    @MethodSource("variants")
    void twoByTwoByHand(DenseMethod m, int bs, int cutoff) {
        double[] a = {1, 2, 3, 4};
        double[] b = {5, 6, 7, 8};
        // [1 2; 3 4] * [5 6; 7 8] = [19 22; 43 50]
        assertArrayEquals(new double[] {19, 22, 43, 50}, multiply(m, a, b, 2, bs, cutoff), 1e-12);
    }

    @ParameterizedTest(name = "{0} bs={1} cutoff={2}")
    @MethodSource("variants")
    void threeByThreeByHand(DenseMethod m, int bs, int cutoff) {
        double[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        double[] b = {9, 8, 7, 6, 5, 4, 3, 2, 1};
        // Row 1: 1*9+2*6+3*3 = 30, 1*8+2*5+3*2 = 24, 1*7+2*4+3*1 = 18; rows 2 and 3 likewise.
        double[] expected = {30, 24, 18, 84, 69, 54, 138, 114, 90};
        assertArrayEquals(expected, multiply(m, a, b, 3, bs, cutoff), 1e-12);
    }

    @ParameterizedTest(name = "{0} bs={1} cutoff={2}")
    @MethodSource("variants")
    void identityAndZero(DenseMethod m, int bs, int cutoff) {
        int n = 37;
        double[] a = MatrixGenerator.dense(n, 1);
        double[] id = new double[n * n];
        for (int i = 0; i < n; i++) {
            id[i * n + i] = 1.0;
        }
        double[] zero = new double[n * n];
        assertArrayEquals(a, multiply(m, a, id, n, bs, cutoff), 1e-12, "A * I = A");
        assertArrayEquals(a, multiply(m, id, a, n, bs, cutoff), 1e-12, "I * A = A");
        assertArrayEquals(zero, multiply(m, a, zero, n, bs, cutoff), 0.0, "A * 0 = 0");
    }

    static Stream<Arguments> variantsAndSizes() {
        List<Arguments> v = new ArrayList<>();
        int[] sizes = {1, 2, 3, 5, 16, 31, 64, 100, 127, 129, 200, 256};
        variants().forEach(a -> {
            for (int n : sizes) {
                Object[] x = a.get();
                v.add(Arguments.of(x[0], x[1], x[2], n));
            }
        });
        return v.stream();
    }

    @ParameterizedTest(name = "{0} bs={1} cutoff={2} n={3}")
    @MethodSource("variantsAndSizes")
    void matchesEjmlReference(DenseMethod m, int bs, int cutoff, int n) {
        double[] a = MatrixGenerator.dense(n, 11);
        double[] b = MatrixGenerator.dense(n, 12);
        DMatrixRMaj ref = new DMatrixRMaj(n, n);
        CommonOps_DDRM.mult(DMatrixRMaj.wrap(n, n, a), DMatrixRMaj.wrap(n, n, b), ref);
        double[] c = multiply(m, a, b, n, bs, cutoff);
        Validation.Result r = Validation.compare(c, ref.data, Validation.DEFAULT_RTOL, atol(m, n));
        assertTrue(r.ok(), () -> m + " n=" + n + ": " + r);
    }

    /** Larger sizes with the parameters used in the study (including non powers of two). */
    @ParameterizedTest(name = "{0} n={1}")
    @MethodSource("studySizes")
    void matchesNaiveAtStudyParameters(DenseMethod m, int n) {
        double[] a = MatrixGenerator.dense(n, 21);
        double[] b = MatrixGenerator.dense(n, 22);
        double[] ref = multiply(DenseMethod.NAIVE, a, b, n, 0, 0);
        double[] c = multiply(m, a, b, n, 64, 128);
        Validation.Result r = Validation.compare(c, ref, Validation.DEFAULT_RTOL, atol(m, n));
        assertTrue(r.ok(), () -> m + " n=" + n + ": " + r);
    }

    static Stream<Arguments> studySizes() {
        List<Arguments> v = new ArrayList<>();
        for (DenseMethod m : new DenseMethod[] {DenseMethod.IKJ, DenseMethod.BLOCKED, DenseMethod.STRASSEN}) {
            for (int n : new int[] {300, 512, 768, 1000}) {
                v.add(Arguments.of(m, n));
            }
        }
        return v.stream();
    }

    @Test
    void strassenPaddedSize() {
        assertEquals(64, Strassen.paddedSize(64, 64));
        assertEquals(768, Strassen.paddedSize(768, 64));   // 48 * 16
        assertEquals(1536, Strassen.paddedSize(1536, 128)); // 96 * 16
        assertEquals(3072, Strassen.paddedSize(3072, 256)); // 192 * 16
        assertEquals(1008, Strassen.paddedSize(1000, 64));  // 63 * 16
        assertEquals(130, Strassen.paddedSize(129, 128));   // 65 * 2
        assertEquals(8, Strassen.paddedSize(7, 1));          // power of two when cutoff = 1
    }

    @Test
    void generatorIsReproducible() {
        assertArrayEquals(MatrixGenerator.dense(50, 7), MatrixGenerator.dense(50, 7));
        double[] x = MatrixGenerator.dense(200, 3);
        for (double v : x) {
            assertTrue(v >= -1.0 && v < 1.0);
        }
    }
}
