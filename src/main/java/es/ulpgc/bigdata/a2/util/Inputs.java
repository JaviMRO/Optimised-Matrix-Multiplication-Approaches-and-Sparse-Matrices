package es.ulpgc.bigdata.a2.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import org.ejml.data.DMatrixSparseCSC;

import es.ulpgc.bigdata.a2.sparse.MatrixMarketReader;

/**
 * Builds the input matrices of a configuration, so that the benchmarks, the pilot and the memory runner
 * all use exactly the same logical matrices:
 * <ul>
 *   <li>synthetic dense (density = 1, no matrix): A = dense(n, seed), B = dense(n, seed + 1);</li>
 *   <li>synthetic sparse (density &lt; 1): A = sparse(n, density, seed), B = sparse(n, density, seed + 1),
 *       and the dense methods receive the dense copies of these same matrices;</li>
 *   <li>real matrix: A = B = the SuiteSparse matrix read from the data directory (product A * A).</li>
 * </ul>
 * The data directory is {@code data} by default and can be changed with {@code -Da2.dataDir=...}.
 */
public final class Inputs {

    private Inputs() {
    }

    public record Sparse(DMatrixSparseCSC a, DMatrixSparseCSC b, int n) {
    }

    public record Dense(double[] a, double[] b, int n, long nnzA, long nnzB) {
    }

    public static Sparse sparse(String matrix, int n, double density, long seed) throws IOException {
        if (isReal(matrix)) {
            DMatrixSparseCSC m = MatrixMarketReader.read(matrixPath(matrix));
            if (m.numRows != m.numCols) {
                throw new IOException(matrix + " is not square: " + m.numRows + " x " + m.numCols);
            }
            return new Sparse(m, m, m.numRows);
        }
        return new Sparse(MatrixGenerator.sparse(n, density, seed), MatrixGenerator.sparse(n, density, seed + 1), n);
    }

    public static Dense dense(String matrix, int n, double density, long seed) throws IOException {
        if (!isReal(matrix) && density >= 1.0) {
            long full = (long) n * n;
            return new Dense(MatrixGenerator.dense(n, seed), MatrixGenerator.dense(n, seed + 1), n, full, full);
        }
        Sparse s = sparse(matrix, n, density, seed);
        double[] a = MatrixGenerator.toDense(s.a());
        double[] b = s.a() == s.b() ? a : MatrixGenerator.toDense(s.b());
        return new Dense(a, b, s.n(), s.a().nz_length, s.b().nz_length);
    }

    public static boolean isReal(String matrix) {
        return matrix != null && !matrix.isBlank() && !matrix.equals("none");
    }

    /** Looks for NAME.mtx, NAME.mtx.gz or NAME/NAME.mtx (the layout of the SuiteSparse tar files). */
    public static Path matrixPath(String name) throws IOException {
        Path dir = Path.of(System.getProperty("a2.dataDir", "data"));
        Path[] candidates = {
            dir.resolve(name + ".mtx"),
            dir.resolve(name + ".mtx.gz"),
            dir.resolve(name).resolve(name + ".mtx"),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IOException("matrix " + name + " not found in " + dir.toAbsolutePath()
            + " (run data/fetch_suitesparse.ps1 first)");
    }

    /** Number of rows of a Matrix Market file, read from its size line without loading the entries. */
    public static int matrixSize(String name) throws IOException {
        Path p = matrixPath(name);
        InputStream in = Files.newInputStream(p);
        if (p.toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("%") && !line.isBlank()) {
                    return Integer.parseInt(line.trim().split("\\s+")[0]);
                }
            }
        }
        throw new IOException(p + ": missing size line");
    }
}
