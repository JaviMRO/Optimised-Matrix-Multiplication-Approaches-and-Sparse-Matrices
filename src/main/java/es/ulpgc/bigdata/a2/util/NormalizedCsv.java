package es.ulpgc.bigdata.a2.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import es.ulpgc.bigdata.a2.bench.RunConfig;

/**
 * Writer for the normalized raw-data CSV shared by every part of the project:
 * {@code host, env_id, part, method, n, density, nnz_A, nnz_B, nnz_C, block_size, cutoff, seed,
 * repetition, time_s, status, metric, value, unit}, plus two extra columns: {@code matrix} (SuiteSparse
 * name or "none") and {@code note} (free text, e.g. why a configuration was not run).
 * Rows are appended and flushed immediately, so a crash never loses earlier rows.
 */
public final class NormalizedCsv implements AutoCloseable {

    public static final String HEADER = "host,env_id,part,method,n,density,nnz_A,nnz_B,nnz_C,block_size,cutoff,"
        + "seed,repetition,time_s,status,metric,value,unit,matrix,note";

    private final BufferedWriter out;
    private final String host;
    private final String envId;

    public NormalizedCsv(Path file, String host, String envId) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        boolean exists = Files.exists(file) && Files.size(file) > 0;
        this.out = openWithRetry(file);
        this.host = host;
        this.envId = envId;
        if (!exists) {
            out.write(HEADER);
            out.newLine();
            out.flush();
        }
    }

    /**
     * On Windows a file that another process has just closed can stay locked for a moment (e.g. while the
     * antivirus scans it), so opening it fails with AccessDeniedException. Retry for a few seconds.
     */
    private static BufferedWriter openWithRetry(Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 50) {
                    throw e;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Values that are unknown are passed as null (or a negative number for nnz/repetition) and left empty. */
    public void row(RunConfig c, long nnzA, long nnzB, long nnzC, int repetition, Double timeS, String status,
                    String metric, Double value, String unit, String note) throws IOException {
        String[] f = {
            host, envId, c.part(), c.method(), Integer.toString(c.n()), RunConfig.formatDensity(c.density()),
            num(nnzA), num(nnzB), num(nnzC),
            c.method().equals("blocked") ? Integer.toString(c.blockSize()) : "",
            c.method().equals("strassen") ? Integer.toString(c.cutoff()) : "",
            Long.toString(c.seed()), repetition < 0 ? "" : Integer.toString(repetition),
            dbl(timeS), status, metric == null ? "" : metric, dbl(value), unit == null ? "" : unit,
            c.matrix(), note == null ? "" : note,
        };
        for (int i = 0; i < f.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(escape(f[i]));
        }
        out.newLine();
        out.flush();
    }

    private static String num(long v) {
        return v < 0 ? "" : Long.toString(v);
    }

    private static String dbl(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.9g", v);
    }

    private static String escape(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
