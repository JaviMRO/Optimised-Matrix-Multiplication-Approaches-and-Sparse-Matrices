package es.ulpgc.bigdata.a2.sparse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DMatrixSparseTriplet;
import org.ejml.ops.DConvertMatrixStruct;

/**
 * Minimal reader for Matrix Market files in coordinate format (as distributed by SuiteSparse).
 * Supports real, integer and pattern fields (pattern entries get value 1.0) and the general,
 * symmetric and skew-symmetric layouts. For (skew-)symmetric files only one triangle is stored,
 * so every off-diagonal entry (i, j) is expanded to also add (j, i), with the sign flipped for
 * skew-symmetric. Files ending in .gz are decompressed on the fly.
 */
public final class MatrixMarketReader {

    private MatrixMarketReader() {
    }

    public static DMatrixSparseCSC read(Path path) throws IOException {
        InputStream in = Files.newInputStream(path);
        if (path.toString().endsWith(".gz")) {
            in = new GZIPInputStream(in);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            return read(r, path.toString());
        }
    }

    public static DMatrixSparseCSC read(BufferedReader r, String name) throws IOException {
        String header = r.readLine();
        if (header == null) {
            throw new IOException(name + ": empty file");
        }
        String[] h = header.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (h.length < 5 || !h[0].equals("%%matrixmarket") || !h[1].equals("matrix")) {
            throw new IOException(name + ": not a Matrix Market matrix header: " + header);
        }
        if (!h[2].equals("coordinate")) {
            throw new IOException(name + ": only the coordinate format is supported, got " + h[2]);
        }
        String field = h[3];
        String symmetry = h[4];
        boolean pattern = field.equals("pattern");
        if (!pattern && !field.equals("real") && !field.equals("integer")) {
            throw new IOException(name + ": unsupported field " + field);
        }
        boolean symmetric = symmetry.equals("symmetric");
        boolean skew = symmetry.equals("skew-symmetric");
        if (!symmetric && !skew && !symmetry.equals("general")) {
            throw new IOException(name + ": unsupported symmetry " + symmetry);
        }

        String line = r.readLine();
        while (line != null && (line.isBlank() || line.startsWith("%"))) {
            line = r.readLine();
        }
        if (line == null) {
            throw new IOException(name + ": missing size line");
        }
        String[] size = line.trim().split("\\s+");
        int rows = Integer.parseInt(size[0]);
        int cols = Integer.parseInt(size[1]);
        int entries = Integer.parseInt(size[2]);

        int capacity = (symmetric || skew) ? Math.multiplyExact(entries, 2) : entries;
        DMatrixSparseTriplet t = new DMatrixSparseTriplet(rows, cols, capacity);
        int read = 0;
        while (read < entries && (line = r.readLine()) != null) {
            if (line.isBlank() || line.startsWith("%")) {
                continue;
            }
            String[] tok = line.trim().split("\\s+");
            int i = Integer.parseInt(tok[0]) - 1; // Matrix Market indices are 1-based
            int j = Integer.parseInt(tok[1]) - 1;
            double v = pattern ? 1.0 : Double.parseDouble(tok[2]);
            t.addItem(i, j, v);
            if ((symmetric || skew) && i != j) {
                t.addItem(j, i, skew ? -v : v);
            }
            read++;
        }
        if (read != entries) {
            throw new IOException(name + ": expected " + entries + " entries, found " + read);
        }
        DMatrixSparseCSC csc = DConvertMatrixStruct.convert(t, (DMatrixSparseCSC) null);
        if (!csc.indicesSorted) {
            csc.sortIndices(null);
        }
        return csc;
    }
}
