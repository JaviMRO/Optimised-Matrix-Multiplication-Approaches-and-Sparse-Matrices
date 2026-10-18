package es.ulpgc.bigdata.a2.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import es.ulpgc.bigdata.a2.bench.Operation;
import es.ulpgc.bigdata.a2.bench.RunConfig;
import es.ulpgc.bigdata.a2.bench.Watchdog;
import es.ulpgc.bigdata.a2.util.NormalizedCsv;

/**
 * Peak heap used around one multiplication, in its own JVM per configuration (started by Suite).
 *
 * <p>For every repetition: two System.gc() calls, reset the peak usage of every heap memory pool,
 * read the current used heap (baseline: the inputs and, for dense methods, the pre-allocated C), run the
 * operation, and read the peaks. The reported peak is max(sum of the per-pool peaks, used heap right after
 * the operation); the sum of per-pool peaks is an upper bound of the true simultaneous peak because pools
 * may peak at different moments. "Used heap" includes garbage not yet collected, as the JVM reports it.
 * The sparse product allocates a fresh output every repetition so that C shows up in the peak.
 * One unrecorded warm-up repetition runs first ({@code --warmup false} disables it).
 *
 * <p>Suite starts these JVMs with -XX:+UseSerialGC -XX:-UseTLAB: G1 reports pool usage in whole regions
 * (MiBs) and TLABs are counted as used when handed out, which would hide temporaries; with these flags the
 * eden usage is exact to the byte. The timing runs (JMH) keep the default G1 collector and TLABs.
 *
 * <p>Rows (normalized CSV, metrics in bytes): baseline_heap_used, peak_heap_used,
 * peak_minus_baseline and analytic_storage (8 n^2 per dense matrix, 12 nnz + 4 (n + 1) per CSC matrix),
 * plus the time of the repetition. Exit code: 0 = OK, 2 = TIMEOUT, 3 = OOM, 1 = other error.
 *
 * <p>Arguments: the ones of {@link RunConfig#toArgs()} plus {@code --reps R --timeout S --out FILE
 * --host H --env-id E}.
 */
public final class MemoryRunner {

    private MemoryRunner() {
    }

    public static void main(String[] args) throws Exception {
        RunConfig cfg0 = RunConfig.fromArgs(args);
        int reps = 5;
        double timeout = 180;
        Path out = Path.of("results/raw/memory.csv");
        String host = "unknown-host";
        String envId = "unknown-env";
        boolean warmup = true;
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--warmup" -> warmup = Boolean.parseBoolean(args[++i]);
                case "--reps" -> reps = Integer.parseInt(args[++i]);
                case "--timeout" -> timeout = Double.parseDouble(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--host" -> host = args[++i];
                case "--env-id" -> envId = args[++i];
                default -> { }
            }
        }
        int exit = 0;
        try (NormalizedCsv csv = new NormalizedCsv(out, host, envId)) {
            Operation op;
            try {
                op = Operation.prepare(cfg0, true);
            } catch (OutOfMemoryError | ArithmeticException e) {
                csv.row(cfg0, -1, -1, -1, -1, null, "OOM", "peak_heap_used", null, "B", "inputs do not fit: " + e);
                System.out.println("MEMORY status=OOM");
                Runtime.getRuntime().halt(3);
                return;
            }
            RunConfig cfg = new RunConfig(cfg0.part(), cfg0.method(), op.n(), cfg0.density(), cfg0.matrix(),
                cfg0.blockSize(), cfg0.cutoff(), cfg0.seed());
            List<MemoryPoolMXBean> heap = heapPools();
            // rep = -1 is an unrecorded warm-up run: the first execution also allocates JVM-internal objects
            // (class loading, the watchdog thread, ...) that are not part of the algorithm.
            for (int rep = warmup ? -1 : 0; rep < reps; rep++) {
                System.gc();
                System.gc();
                for (MemoryPoolMXBean p : heap) {
                    p.resetPeakUsage();
                }
                long baseline = sumUsed(heap);
                Watchdog.Outcome o = Watchdog.run(op::run, timeout);
                // Pool peaks are only refreshed at GC events. Between GCs the used heap never decreases (garbage
                // stays until collected), so the usage right after the operation is also a lower bound of the
                // peak; take the larger of the two.
                long peak = Math.max(sumPeak(heap), sumUsed(heap));
                if (o.status() != Watchdog.Status.OK) {
                    csv.row(cfg, op.nnzA(), op.nnzB(), -1, rep, null, o.status().name(), "peak_heap_used", null, "B",
                        o.message() + (rep < 0 ? " (warm-up run)" : ""));
                    exit = o.status() == Watchdog.Status.TIMEOUT ? 2 : o.status() == Watchdog.Status.OOM ? 3 : 1;
                    break;
                }
                if (rep < 0) {
                    continue;
                }
                long nnzC = op.nnzC();
                long analytic = op.analyticBytes();
                csv.row(cfg, op.nnzA(), op.nnzB(), nnzC, rep, o.timeS(), "OK", "time", o.timeS(), "s", "");
                csv.row(cfg, op.nnzA(), op.nnzB(), nnzC, rep, o.timeS(), "OK", "baseline_heap_used", (double) baseline, "B", "");
                csv.row(cfg, op.nnzA(), op.nnzB(), nnzC, rep, o.timeS(), "OK", "peak_heap_used", (double) peak, "B", "");
                csv.row(cfg, op.nnzA(), op.nnzB(), nnzC, rep, o.timeS(), "OK", "peak_minus_baseline",
                    (double) (peak - baseline), "B", "");
                csv.row(cfg, op.nnzA(), op.nnzB(), nnzC, rep, o.timeS(), "OK", "analytic_storage", (double) analytic, "B", "");
                System.out.printf("  rep %d: %.3f s, peak %.1f MiB (baseline %.1f MiB)%n", rep, o.timeS(),
                    peak / 1048576.0, baseline / 1048576.0);
            }
        }
        System.out.println("MEMORY status=" + (exit == 0 ? "OK" : exit == 2 ? "TIMEOUT" : exit == 3 ? "OOM" : "ERROR"));
        System.out.flush();
        Runtime.getRuntime().halt(exit);
    }

    private static List<MemoryPoolMXBean> heapPools() {
        List<MemoryPoolMXBean> list = new ArrayList<>();
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() == MemoryType.HEAP && p.isValid()) {
                list.add(p);
            }
        }
        return list;
    }

    private static long sumUsed(List<MemoryPoolMXBean> pools) {
        long s = 0;
        for (MemoryPoolMXBean p : pools) {
            s += p.getUsage().getUsed();
        }
        return s;
    }

    private static long sumPeak(List<MemoryPoolMXBean> pools) {
        long s = 0;
        for (MemoryPoolMXBean p : pools) {
            s += p.getPeakUsage().getUsed();
        }
        return s;
    }
}
