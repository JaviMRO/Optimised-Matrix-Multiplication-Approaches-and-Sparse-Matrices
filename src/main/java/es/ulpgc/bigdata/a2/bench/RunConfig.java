package es.ulpgc.bigdata.a2.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One configuration to measure.
 *
 * @param part      dense, block_sweep, cutoff_sweep or sparse
 * @param method    naive, ikj, blocked, strassen (dense kernels), ejml (sparse x sparse) or convert (dense to CSC)
 * @param n         matrix size (for a real matrix: its size, read from the file header)
 * @param density   fraction of non-zeros of the synthetic inputs (1.0 = fully dense generator)
 * @param matrix    "none" for synthetic inputs, otherwise the SuiteSparse matrix name (A = B = that matrix)
 * @param blockSize tile size of the blocked method
 * @param cutoff    recursion cutoff of Strassen
 * @param seed      seed of A (B uses seed + 1)
 */
public record RunConfig(String part, String method, int n, double density, String matrix,
                        int blockSize, int cutoff, long seed) {

    public static final String NO_MATRIX = "none";

    public boolean isSparseMethod() {
        return method.equals("ejml") || method.equals("convert");
    }

    public boolean isRealMatrix() {
        return !matrix.equals(NO_MATRIX);
    }

    /** Short human-readable description for logs. */
    public String describe() {
        String in = isRealMatrix() ? matrix + " (n=" + n + ")" : "n=" + n + (density < 1.0 ? ", density=" + density : "");
        String extra = switch (method) {
            case "blocked" -> ", bs=" + blockSize;
            case "strassen" -> ", cutoff=" + cutoff;
            default -> "";
        };
        return part + "/" + method + " " + in + extra;
    }

    /** Command-line arguments that recreate this configuration (used for child JVMs). */
    public List<String> toArgs() {
        List<String> a = new ArrayList<>();
        a.add("--part"); a.add(part);
        a.add("--method"); a.add(method);
        a.add("--n"); a.add(Integer.toString(n));
        a.add("--density"); a.add(formatDensity(density));
        a.add("--matrix"); a.add(matrix);
        a.add("--block-size"); a.add(Integer.toString(blockSize));
        a.add("--cutoff"); a.add(Integer.toString(cutoff));
        a.add("--seed"); a.add(Long.toString(seed));
        return a;
    }

    /** Parses the arguments written by {@link #toArgs()}; unknown arguments are ignored. */
    public static RunConfig fromArgs(String[] args) {
        String part = "dense", method = "naive", matrix = NO_MATRIX;
        int n = 128, bs = 64, cutoff = 128;
        double density = 1.0;
        long seed = 42;
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--part" -> part = args[++i];
                case "--method" -> method = args[++i];
                case "--n" -> n = Integer.parseInt(args[++i]);
                case "--density" -> density = Double.parseDouble(args[++i]);
                case "--matrix" -> matrix = args[++i];
                case "--block-size" -> bs = Integer.parseInt(args[++i]);
                case "--cutoff" -> cutoff = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                default -> { }
            }
        }
        return new RunConfig(part, method, n, density, matrix, bs, cutoff, seed);
    }

    public static String formatDensity(double d) {
        return String.format(Locale.ROOT, "%s", d);
    }
}
