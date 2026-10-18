package es.ulpgc.bigdata.a2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.ejml.data.DMatrixSparseCSC;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import es.ulpgc.bigdata.a2.dense.NaiveIJK;
import es.ulpgc.bigdata.a2.sparse.MatrixMarketReader;
import es.ulpgc.bigdata.a2.sparse.SparseMultiplier;
import es.ulpgc.bigdata.a2.util.MatrixGenerator;
import es.ulpgc.bigdata.a2.util.Validation;

/** Sparse generator, EJML product, conversion and Matrix Market reader. */
class SparseTest {

    @ParameterizedTest(name = "n={0} density={1}")
    @CsvSource({"1, 1.0", "10, 0.5", "57, 0.01", "100, 0.001", "200, 0.05", "333, 0.1", "500, 0.25"})
    void sparseProductMatchesDenseReference(int n, double density) {
        DMatrixSparseCSC a = MatrixGenerator.sparse(n, density, 5);
        DMatrixSparseCSC b = MatrixGenerator.sparse(n, density, 6);
        DMatrixSparseCSC c = new SparseMultiplier().multiply(a, b);
        double[] ref = new double[n * n];
        NaiveIJK.multiply(MatrixGenerator.toDense(a), MatrixGenerator.toDense(b), ref, n);
        Validation.Result r = Validation.compare(MatrixGenerator.toDense(c), ref, n);
        assertTrue(r.ok(), r::toString);
    }

    @Test
    void multiplierCanBeReused() {
        int n = 120;
        SparseMultiplier mult = new SparseMultiplier();
        for (double d : new double[] {0.1, 0.01, 0.2}) {
            DMatrixSparseCSC a = MatrixGenerator.sparse(n, d, 1);
            DMatrixSparseCSC b = MatrixGenerator.sparse(n, d, 2);
            double[] ref = new double[n * n];
            NaiveIJK.multiply(MatrixGenerator.toDense(a), MatrixGenerator.toDense(b), ref, n);
            assertTrue(Validation.compare(MatrixGenerator.toDense(mult.multiply(a, b)), ref, n).ok());
        }
    }

    @Test
    void generatorHasExactDistinctSortedNonZeros() {
        int n = 300;
        double density = 0.05;
        DMatrixSparseCSC m = MatrixGenerator.sparse(n, density, 99);
        assertEquals(MatrixGenerator.targetNnz(n, density), m.nz_length);
        for (int col = 0; col < n; col++) {
            for (int p = m.col_idx[col] + 1; p < m.col_idx[col + 1]; p++) {
                assertTrue(m.nz_rows[p - 1] < m.nz_rows[p], "rows strictly increasing => distinct positions");
            }
        }
        for (int p = 0; p < m.nz_length; p++) {
            assertTrue(m.nz_values[p] >= -1.0 && m.nz_values[p] < 1.0);
        }
        DMatrixSparseCSC again = MatrixGenerator.sparse(n, density, 99);
        assertArrayEquals(MatrixGenerator.toDense(m), MatrixGenerator.toDense(again), "same seed, same matrix");
    }

    @Test
    void denseToCscRoundTrip() {
        int n = 150;
        DMatrixSparseCSC s = MatrixGenerator.sparse(n, 0.02, 4);
        double[] dense = MatrixGenerator.toDense(s);
        DMatrixSparseCSC back = SparseMultiplier.denseToCsc(dense, n);
        assertEquals(s.nz_length, back.nz_length);
        assertArrayEquals(dense, MatrixGenerator.toDense(back));
    }

    @Test
    void readsGeneralMatrixMarket() throws IOException {
        String mm = """
            %%MatrixMarket matrix coordinate real general
            % a comment
            3 3 4
            1 1 2.5
            3 1 -1
            2 2 4
            1 3 7e-1
            """;
        DMatrixSparseCSC m = MatrixMarketReader.read(new BufferedReader(new StringReader(mm)), "test");
        assertArrayEquals(new double[] {2.5, 0, 0.7, 0, 4, 0, -1, 0, 0}, MatrixGenerator.toDense(m));
    }

    @Test
    void expandsSymmetricAndPattern() throws IOException {
        String sym = """
            %%MatrixMarket matrix coordinate real symmetric
            2 2 2
            1 1 3
            2 1 5
            """;
        DMatrixSparseCSC s = MatrixMarketReader.read(new BufferedReader(new StringReader(sym)), "sym");
        assertEquals(3, s.nz_length);
        assertArrayEquals(new double[] {3, 5, 5, 0}, MatrixGenerator.toDense(s));

        String pat = """
            %%MatrixMarket matrix coordinate pattern symmetric
            2 2 1
            2 1
            """;
        DMatrixSparseCSC p = MatrixMarketReader.read(new BufferedReader(new StringReader(pat)), "pat");
        assertArrayEquals(new double[] {0, 1, 1, 0}, MatrixGenerator.toDense(p));

        String skew = """
            %%MatrixMarket matrix coordinate real skew-symmetric
            2 2 1
            2 1 4
            """;
        DMatrixSparseCSC k = MatrixMarketReader.read(new BufferedReader(new StringReader(skew)), "skew");
        assertArrayEquals(new double[] {0, -4, 4, 0}, MatrixGenerator.toDense(k));
    }

    /** Runs only after data/fetch_suitesparse has downloaded the matrix: nnz must match SuiteSparse. */
    @Test
    void realMatrixHasSuiteSparseNnzAndCorrectProduct() throws IOException {
        Path file = Path.of("data", "bcsstk13.mtx.gz");
        Assumptions.assumeTrue(Files.exists(file), "bcsstk13 not downloaded");
        DMatrixSparseCSC m = MatrixMarketReader.read(file);
        assertEquals(2003, m.numRows);
        assertEquals(83883, m.nz_length);
        int n = m.numRows;
        double[] dense = MatrixGenerator.toDense(m);
        double[] ref = new double[n * n];
        NaiveIJK.multiply(dense, dense, ref, n);
        Validation.Result r = Validation.compare(MatrixGenerator.toDense(new SparseMultiplier().multiply(m, m)), ref, n);
        assertTrue(r.ok(), r::toString);
    }

    @Test
    void rejectsArrayFormat() {
        String arr = "%%MatrixMarket matrix array real general\n2 2\n1\n2\n3\n4\n";
        assertThrows(IOException.class,
            () -> MatrixMarketReader.read(new BufferedReader(new StringReader(arr)), "array"));
    }

    @Test
    void validationToleranceFormula() {
        double[] ref = {1.0, 1000.0, 0.0};
        // |diff| <= atol + rtol * |ref| with rtol = 1e-10, atol = 1e-12 * n (n = 1 here)
        assertTrue(Validation.compare(new double[] {1.0 + 5e-11, 1000.0 + 5e-8, 5e-13}, ref, 1).ok());
        assertEquals(1, Validation.compare(new double[] {1.0 + 1e-9, 1000.0, 0.0}, ref, 1).failures());
        assertEquals(1, Validation.compare(new double[] {Double.NaN, 1000.0, 0.0}, ref, 1).failures());
    }
}
