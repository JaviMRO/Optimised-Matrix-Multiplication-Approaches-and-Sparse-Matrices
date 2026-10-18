package es.ulpgc.bigdata.a2.bench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatFactory;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import es.ulpgc.bigdata.a2.util.EnvironmentInfo;
import es.ulpgc.bigdata.a2.util.Inputs;
import es.ulpgc.bigdata.a2.util.NormalizedCsv;
import oshi.SystemInfo;

/**
 * Driver for one part of the study. Usage:
 * <pre>
 * java -cp target/benchmarks.jar es.ulpgc.bigdata.a2.bench.Suite --part PART [--mode bench|memory] [options]
 * </pre>
 * PART is dense, block_sweep, cutoff_sweep or sparse. With {@code --env-only} (no part) it only writes the
 * environment JSON (collected in a JVM with the benchmark flags) and prints its env_id.
 *
 * <p>Mode bench (default):
 * <ol>
 *   <li>Pilot: every configuration is run once in its own JVM under a watchdog ({@link Pilot}). Configurations
 *       are grouped in series (same method and parameters, increasing n); as soon as one run exceeds the time
 *       budget (180 s) or the heap, it is recorded as TIMEOUT/OOM and the larger sizes of that series are not
 *       run and are recorded with the same status and a note. Never a made-up time. Output:
 *       {@code <host>_<part>_pilot_<ts>.csv} and {@code <host>_<part>_timeouts_<ts>.csv}.</li>
 *   <li>JMH on the configurations that passed: one fork, 3 warm-up and 10 measured iterations, -Xmx = -Xms =
 *       75 % of the RAM, GC profiler for gc.alloc.rate.norm. SingleShotTime if the pilot took at least
 *       {@code --avgt-threshold} seconds (default 1 s), AverageTime with 1 s iterations otherwise. Output:
 *       {@code <host>_<part>_<ts>.json} (JMH JSON; rewritten after each configuration, so partial runs are kept).</li>
 * </ol>
 * For block_sweep and cutoff_sweep the best value (lowest median) is stored in
 * {@code results/tuning/<host>.properties} and used by the dense part; the dense part stores the best
 * optimised method there too, which the sparse part compares against (together with the naive one).
 *
 * <p>Mode memory: one {@link es.ulpgc.bigdata.a2.memory.MemoryRunner} JVM per configuration (5 repetitions),
 * with the same stop rule. Output: {@code memory_<host>_<part>_<ts>.csv}.
 */
public final class Suite {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final PrintStream LOG = System.out;

    private final Map<String, String> opt;
    private final String part;
    private final String host;
    private final String stamp;
    private final Path outDir;
    private final double timeout;
    private final List<String> childJvmArgs;
    private String envId;

    private Suite(Map<String, String> opt) {
        this.opt = opt;
        this.part = opt.containsKey("env-only") ? "env" : require(opt, "part");
        this.host = EnvironmentInfo.hostname();
        this.stamp = LocalDateTime.now().format(STAMP);
        this.outDir = Path.of(opt.getOrDefault("out-dir", "results/raw"));
        this.timeout = Double.parseDouble(opt.getOrDefault("timeout", "180"));
        long heapMb = heapBudgetMb(Double.parseDouble(opt.getOrDefault("heap-fraction", "0.75")));
        this.envId = opt.get("env-id");
        this.childJvmArgs = new ArrayList<>(List.of("-Xmx" + heapMb + "m", "-Xms" + heapMb + "m"));
        childJvmArgs.add("-Da2.dataDir=" + System.getProperty("a2.dataDir", "data"));
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parseArgs(args);
        new Suite(opt).run();
    }

    private void run() throws Exception {
        Files.createDirectories(outDir);
        checkCommitHeadroom();
        if (envId == null) {
            envId = collectEnvironment();
        }
        childJvmArgs.add("-Da2.host=" + host);
        childJvmArgs.add("-Da2.envId=" + envId);
        if (opt.containsKey("env-only")) {
            // Used by scripts/run_env: the last line of the output is the env_id.
            LOG.println(envId);
            return;
        }
        LOG.printf("Host %s, env_id %s, child JVM flags %s%n", host, envId, childJvmArgs);

        List<List<RunConfig>> series = buildSeries();
        int count = series.stream().mapToInt(List::size).sum();
        LOG.printf("Part %s: %d configurations in %d series%n", part, count, series.size());

        if (opt.getOrDefault("mode", "bench").equals("memory")) {
            runMemory(series);
        } else {
            Map<RunConfig, Double> feasible = runPilot(series);
            if (opt.containsKey("plan-only")) {
                LOG.println("--plan-only: stopping after the pilot.");
                return;
            }
            runJmh(feasible);
        }
    }

    // ------------------------------------------------------------------ configurations

    private List<List<RunConfig>> buildSeries() throws IOException {
        long seed = Long.parseLong(opt.getOrDefault("seed", "42"));
        Properties tuning = loadTuning();
        int bs = Integer.parseInt(opt.getOrDefault("block-size", tuning.getProperty("block_size", "64")));
        int cutoff = Integer.parseInt(opt.getOrDefault("cutoff", tuning.getProperty("cutoff", "128")));
        if (!opt.containsKey("block-size") && !tuning.containsKey("block_size")) {
            LOG.println("Note: no tuned block size yet (run block_sweep first); using 64.");
        }
        if (!opt.containsKey("cutoff") && !tuning.containsKey("cutoff")) {
            LOG.println("Note: no tuned Strassen cutoff yet (run cutoff_sweep first); using 128.");
        }

        List<String> methods;
        List<Integer> sizes;
        List<Double> densities = List.of(1.0);
        List<Integer> blockSizes = List.of(bs);
        List<Integer> cutoffs = List.of(cutoff);
        List<String> matrices = List.of();
        switch (part) {
            case "dense" -> {
                methods = list(opt.getOrDefault("methods", "naive,ikj,blocked,strassen"));
                sizes = ints(opt.getOrDefault("sizes", "128,256,512,768,1024,1536,2048,3072,4096"));
            }
            case "block_sweep" -> {
                methods = List.of("blocked");
                sizes = ints(opt.getOrDefault("sizes", "1024"));
                blockSizes = ints(opt.getOrDefault("block-sizes", "16,32,64,128"));
            }
            case "cutoff_sweep" -> {
                methods = List.of("strassen");
                sizes = ints(opt.getOrDefault("sizes", "1024"));
                cutoffs = ints(opt.getOrDefault("cutoffs", "64,128,256"));
            }
            case "sparse" -> {
                String best = tuning.getProperty("best_dense_method", "blocked");
                if (!opt.containsKey("methods") && !tuning.containsKey("best_dense_method")) {
                    LOG.println("Note: no best dense method recorded yet (run the dense part first); using blocked.");
                }
                String defaults = "ejml,convert,naive" + (best.equals("naive") ? "" : "," + best);
                methods = list(opt.getOrDefault("methods", defaults));
                sizes = ints(opt.getOrDefault("sizes", "1000,2000,4000,8000"));
                densities = doubles(opt.getOrDefault("densities", "0.001,0.01,0.05,0.1"));
                matrices = list(opt.getOrDefault("matrices", ""));
            }
            default -> throw new IllegalArgumentException("unknown part: " + part);
        }

        List<List<RunConfig>> series = new ArrayList<>();
        for (String m : methods) {
            for (int b : m.equals("blocked") ? blockSizes : List.of(bs)) {
                for (int c : m.equals("strassen") ? cutoffs : List.of(cutoff)) {
                    for (double d : densities) {
                        List<RunConfig> s = new ArrayList<>();
                        for (int n : sizes) {
                            s.add(new RunConfig(part, m, n, d, RunConfig.NO_MATRIX, b, c, seed));
                        }
                        series.add(s);
                    }
                    for (String mat : matrices) {
                        series.add(List.of(new RunConfig(part, m, Inputs.matrixSize(mat), 1.0, mat, b, c, seed)));
                    }
                }
            }
        }
        return series;
    }

    // ------------------------------------------------------------------ pilot

    private Map<RunConfig, Double> runPilot(List<List<RunConfig>> series) throws Exception {
        Path pilotFile = outDir.resolve(host + "_" + part + "_pilot_" + stamp + ".csv");
        Path timeoutFile = outDir.resolve(host + "_" + part + "_timeouts_" + stamp + ".csv");
        Map<RunConfig, Double> feasible = new LinkedHashMap<>();
        try (NormalizedCsv pilot = new NormalizedCsv(pilotFile, host, envId);
             NormalizedCsv limits = new NormalizedCsv(timeoutFile, host, envId)) {
            for (List<RunConfig> s : series) {
                String stopped = null;
                for (RunConfig c : s) {
                    if (stopped != null) {
                        String note = "not run: " + stopped;
                        pilot.row(c, -1, -1, -1, -1, null, stopped.substring(0, stopped.indexOf(' ')),
                            "pilot_time", null, "s", note);
                        limits.row(c, -1, -1, -1, -1, null, stopped.substring(0, stopped.indexOf(' ')),
                            "pilot_time", null, "s", note);
                        continue;
                    }
                    LOG.printf("[pilot] %s ... ", c.describe());
                    List<String> cmd = javaCommand(Pilot.class, c.toArgs());
                    cmd.add("--timeout");
                    cmd.add(Double.toString(timeout));
                    Map<String, String> r = parseResult(runChild(cmd, timeout + 900), "RESULT");
                    String status = r.getOrDefault("status", "ERROR");
                    if (r.getOrDefault("msg", "").startsWith("child_JVM_printed_no")) {
                        throw new IllegalStateException("pilot JVM for " + c.describe()
                            + " ended without a result (crash at start-up?); see its output above");
                    }
                    Double t =r.containsKey("time_s") && !r.get("time_s").isEmpty() ? Double.parseDouble(r.get("time_s")) : null;
                    long nnzA = Long.parseLong(r.getOrDefault("nnz_a", "-1"));
                    long nnzB = Long.parseLong(r.getOrDefault("nnz_b", "-1"));
                    long nnzC = Long.parseLong(r.getOrDefault("nnz_c", "-1"));
                    String msg = r.getOrDefault("msg", "").replace('_', ' ');
                    pilot.row(c, nnzA, nnzB, nnzC, 0, t, status, "pilot_time", t, "s", msg);
                    if (status.equals("OK")) {
                        LOG.printf(Locale.ROOT, "%.3f s%n", t);
                        feasible.put(c, t);
                    } else {
                        LOG.printf("%s (%s)%n", status, msg);
                        String limit = status.equals("TIMEOUT") ? "time budget " + timeout + " s"
                            : status.equals("OOM") ? "heap " + childJvmArgs.get(0) : "error";
                        limits.row(c, nnzA, nnzB, nnzC, 0, null, status, "pilot_time", null, "s", limit + "; " + msg);
                        stopped = status + " at n=" + c.n() + " (" + limit + ")";
                    }
                }
            }
        }
        LOG.printf("Pilot written to %s and %s%n", pilotFile, timeoutFile);
        int it = warmup() + iterations();
        double est = 0;
        for (double t : feasible.values()) {
            est += 3 + (t >= avgtThreshold() ? it * t : it * 1.0);
        }
        LOG.printf(Locale.ROOT, "Estimated JMH time for %d feasible configurations: %.1f min%n", feasible.size(), est / 60);
        return feasible;
    }

    // ------------------------------------------------------------------ JMH

    private void runJmh(Map<RunConfig, Double> feasible) throws IOException {
        Path json = outDir.resolve(host + "_" + part + "_" + stamp + ".json");
        List<RunResult> all = new ArrayList<>();
        int i = 0;
        for (Map.Entry<RunConfig, Double> e : feasible.entrySet()) {
            RunConfig c = e.getKey();
            boolean singleShot = e.getValue() >= avgtThreshold();
            LOG.printf("%n[jmh %d/%d] %s (%s)%n", ++i, feasible.size(), c.describe(), singleShot ? "SingleShotTime" : "AverageTime");
            try {
                Collection<RunResult> res = new Runner(jmhOptions(c, singleShot).build()).run();
                all.addAll(res);
            } catch (RunnerException ex) {
                LOG.println("JMH failed for " + c.describe() + ": " + ex);
                appendFailure(c, ex);
            }
            try (PrintStream ps = new PrintStream(Files.newOutputStream(json), true, StandardCharsets.UTF_8)) {
                ResultFormatFactory.getInstance(ResultFormatType.JSON, ps).writeOut(all);
            }
        }
        LOG.println("JMH results written to " + json);
        updateTuning(all);
    }

    private ChainedOptionsBuilder jmhOptions(RunConfig c, boolean singleShot) {
        ChainedOptionsBuilder b = new OptionsBuilder()
            .forks(1)
            .warmupIterations(warmup())
            .measurementIterations(iterations())
            .jvmArgs(childJvmArgs.toArray(new String[0]))
            .addProfiler(GCProfiler.class)
            .timeUnit(TimeUnit.MILLISECONDS)
            .shouldFailOnError(true)
            .param("seed", Long.toString(c.seed()))
            .param("matrix", c.matrix())
            .param("n", Integer.toString(c.n()))
            .param("density", RunConfig.formatDensity(c.density()));
        if (singleShot) {
            b.mode(Mode.SingleShotTime);
        } else {
            b.mode(Mode.AverageTime).warmupTime(TimeValue.seconds(1)).measurementTime(TimeValue.seconds(1));
        }
        switch (c.method()) {
            case "ejml" -> b.include("^" + SparseBenchmark.class.getName() + "\\.multiply$");
            case "convert" -> b.include("^" + ConversionBenchmark.class.getName() + "\\.convert$");
            default -> b.include("^" + DenseBenchmark.class.getName() + "\\.multiply$")
                .param("method", c.method())
                .param("blockSize", Integer.toString(c.blockSize()))
                .param("cutoff", Integer.toString(c.cutoff()));
        }
        return b;
    }

    private void appendFailure(RunConfig c, Exception ex) throws IOException {
        Path f = outDir.resolve(host + "_" + part + "_timeouts_" + stamp + ".csv");
        try (NormalizedCsv csv = new NormalizedCsv(f, host, envId)) {
            csv.row(c, -1, -1, -1, -1, null, "ERROR", "jmh", null, "", "JMH run failed: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ tuning

    private Path tuningFile() {
        return Path.of(opt.getOrDefault("tuning-dir", "results/tuning")).resolve(host + ".properties");
    }

    private Properties loadTuning() throws IOException {
        Properties p = new Properties();
        Path f = tuningFile();
        if (Files.exists(f)) {
            try (var in = Files.newInputStream(f)) {
                p.load(in);
            }
        }
        return p;
    }

    /** Stores the best block size / cutoff (sweeps) or the best optimised dense method (dense part). */
    private void updateTuning(List<RunResult> results) throws IOException {
        if (results.isEmpty() || part.equals("sparse")) {
            return;
        }
        Properties p = loadTuning();
        switch (part) {
            case "block_sweep" -> p.setProperty("block_size", best(results, "blockSize"));
            case "cutoff_sweep" -> p.setProperty("cutoff", best(results, "cutoff"));
            case "dense" -> {
                // Largest n where every optimised method has a result; best = lowest median there.
                int nMax = -1;
                for (RunResult r : results) {
                    int n = Integer.parseInt(r.getParams().getParam("n"));
                    long methodsAtN = results.stream()
                        .filter(x -> x.getParams().getParam("n").equals(Integer.toString(n)))
                        .map(x -> x.getParams().getParam("method"))
                        .filter(m -> !m.equals("naive")).distinct().count();
                    if (methodsAtN == 3 && n > nMax) {
                        nMax = n;
                    }
                }
                if (nMax < 0) {
                    return;
                }
                final String nKey = Integer.toString(nMax);
                List<RunResult> atN = results.stream().filter(x -> x.getParams().getParam("n").equals(nKey)
                    && !x.getParams().getParam("method").equals("naive")).toList();
                p.setProperty("best_dense_method", best(atN, "method"));
                p.setProperty("best_dense_method_at_n", nKey);
            }
            default -> {
                return;
            }
        }
        p.setProperty("updated_by", part + " " + stamp);
        Files.createDirectories(tuningFile().getParent());
        try (var out = Files.newOutputStream(tuningFile())) {
            p.store(out, "Best parameters on " + host + " (written by Suite; delete to reset)");
        }
        LOG.println("Tuning updated in " + tuningFile() + ": " + p);
    }

    private static String best(List<RunResult> results, String param) {
        String bestValue = null;
        double bestMedian = Double.MAX_VALUE;
        for (RunResult r : results) {
            double median = r.getPrimaryResult().getStatistics().getPercentile(50);
            if (median < bestMedian) {
                bestMedian = median;
                bestValue = r.getParams().getParam(param);
            }
        }
        return bestValue;
    }

    // ------------------------------------------------------------------ memory

    private void runMemory(List<List<RunConfig>> series) throws Exception {
        Path csvFile = outDir.resolve("memory_" + host + "_" + part + "_" + stamp + ".csv");
        int reps = Integer.parseInt(opt.getOrDefault("reps", "5"));
        for (List<RunConfig> s : series) {
            String stopped = null;
            for (RunConfig c : s) {
                if (stopped != null) {
                    try (NormalizedCsv csv = new NormalizedCsv(csvFile, host, envId)) {
                        csv.row(c, -1, -1, -1, -1, null, stopped.substring(0, stopped.indexOf(' ')),
                            "peak_heap_used", null, "B", "not run: " + stopped);
                    }
                    continue;
                }
                LOG.printf("[memory] %s%n", c.describe());
                List<String> args = new ArrayList<>(c.toArgs());
                args.addAll(List.of("--reps", Integer.toString(reps), "--timeout", Double.toString(timeout),
                    "--warmup", Boolean.toString(!opt.containsKey("no-memory-warmup")),
                    "--out", csvFile.toString(), "--host", host, "--env-id", envId));
                List<String> cmd = javaCommand(es.ulpgc.bigdata.a2.memory.MemoryRunner.class, args);
                // G1 reports pool usage in whole heap regions (several MiB), and thread-local allocation buffers
                // (TLABs, ~17 MiB here) are counted as used as soon as they are handed out. Both hide temporaries.
                // With the serial collector and no TLABs every allocation moves the eden top, so the pool usage is
                // exact to the byte. Only the memory runner JVMs use these flags; JMH keeps the default G1.
                cmd.add(1, "-XX:+UseSerialGC");
                cmd.add(2, "-XX:-UseTLAB");
                Map<String, String> r = parseResult(runChild(cmd, reps * timeout + 900), "MEMORY");
                String status = r.getOrDefault("status", "ERROR");
                if (r.getOrDefault("msg", "").startsWith("child_JVM_printed_no")) {
                    throw new IllegalStateException("memory JVM for " + c.describe()
                        + " ended without a result; see its output above");
                }
                if (!status.equals("OK")) {
                    stopped = status + " at n=" + c.n();
                }
            }
        }
        LOG.println("Memory results written to " + csvFile);
    }

    // ------------------------------------------------------------------ child processes

    private String collectEnvironment() throws Exception {
        List<String> extra = new ArrayList<>(childJvmArgs);
        extra.add("--enable-native-access=ALL-UNNAMED");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.addAll(extra);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), EnvironmentInfo.class.getName()));
        List<String> out = runChild(cmd, 300);
        String last = out.isEmpty() ? "" : out.get(out.size() - 1).trim();
        if (!last.matches(java.util.regex.Pattern.quote(host) + "_\\d{8}_\\d{6}")) {
            throw new IOException("EnvironmentInfo failed (the child JVM may not have started; see output above)");
        }
        return last;
    }

    private List<String> javaCommand(Class<?> main, List<String> args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaExecutable());
        cmd.addAll(childJvmArgs);
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), main.getName()));
        cmd.addAll(args);
        return cmd;
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Runs a child JVM, echoes and returns its standard output; kills it after maxSeconds. */
    private static List<String> runChild(List<String> cmd, double maxSeconds) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (lines) {
                        lines.add(line);
                    }
                    if (!line.startsWith("RESULT") && !line.startsWith("MEMORY")) {
                        LOG.println("    | " + line);
                    }
                }
            } catch (IOException e) {
                // process ended
            }
        });
        reader.start();
        if (!p.waitFor((long) maxSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            lines.add("RESULT status=TIMEOUT time_s= msg=killed_by_parent_watchdog");
            lines.add("MEMORY status=TIMEOUT");
        }
        reader.join(5000);
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    private static Map<String, String> parseResult(List<String> lines, String prefix) {
        Map<String, String> m = new HashMap<>();
        for (String line : lines) {
            if (line.startsWith(prefix + " ")) {
                for (String kv : line.substring(prefix.length() + 1).split(" ")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        m.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
                return m;
            }
        }
        m.put("status", "ERROR");
        m.put("msg", "child_JVM_printed_no_" + prefix + "_line");
        return m;
    }

    /**
     * With -Xms = -Xmx the whole heap is committed when a child JVM starts. On Windows that needs free
     * commit charge (RAM + page file not yet promised to other processes); if it is missing the JVM dies at
     * start-up. Check it first and stop with a clear message instead of recording broken runs.
     */
    private void checkCommitHeadroom() {
        var vm = new SystemInfo().getHardware().getMemory().getVirtualMemory();
        long free = vm.getVirtualMax() - vm.getVirtualInUse();
        long heap = Long.parseLong(childJvmArgs.get(0).replaceAll("\\D", "")) * 1024 * 1024;
        long margin = 512L * 1024 * 1024; // metaspace, code cache, thread stacks of the child JVM
        if (free > 0 && heap + margin > free) {
            String msg = String.format(Locale.ROOT,
                "Only %.1f GiB of commit charge is free but the measuring JVMs need %.1f GiB (%s, -Xms = -Xmx)."
                    + " Close other applications (browsers, IDEs) and retry, or lower --heap-fraction"
                    + " (record it in the report).", free / 1073741824.0, (heap + margin) / 1073741824.0,
                childJvmArgs.get(0));
            if (opt.containsKey("ignore-commit-check")) {
                LOG.println("WARNING: " + msg);
            } else {
                throw new IllegalStateException(msg);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** -Xmx for every measuring JVM: heapFraction of the physical RAM (read with OSHI), in MiB. */
    static long heapBudgetMb(double heapFraction) {
        long total = new SystemInfo().getHardware().getMemory().getTotal();
        return (long) (total * heapFraction / (1024 * 1024));
    }

    private int warmup() {
        return Integer.parseInt(opt.getOrDefault("warmup", "3"));
    }

    private int iterations() {
        return Integer.parseInt(opt.getOrDefault("iterations", "10"));
    }

    private double avgtThreshold() {
        return Double.parseDouble(opt.getOrDefault("avgt-threshold", "1.0"));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument: " + args[i]);
            }
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(key, args[++i]);
            } else {
                m.put(key, "true");
            }
        }
        return m;
    }

    private static String require(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v;
    }

    private static List<String> list(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static List<Integer> ints(String csv) {
        return list(csv).stream().map(Integer::parseInt).toList();
    }

    private static List<Double> doubles(String csv) {
        return list(csv).stream().map(Double::parseDouble).toList();
    }
}
