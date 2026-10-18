package es.ulpgc.bigdata.a2.bench;

import java.util.Locale;

/**
 * Pilot run of one configuration, executed in its own JVM by {@link Suite}: builds the inputs, runs the
 * operation once (cold, so it is an upper bound of the steady-state time) under the watchdog, and prints
 * one line {@code RESULT status=... time_s=... n=... nnz_a=... nnz_b=... nnz_c=...}.
 * The JVM is halted right after, so a timed-out kernel does not keep running.
 *
 * <p>Arguments: the ones of {@link RunConfig#toArgs()} plus {@code --timeout SECONDS}.
 */
public final class Pilot {

    private Pilot() {
    }

    public static void main(String[] args) {
        RunConfig cfg = RunConfig.fromArgs(args);
        double timeout = 180;
        for (int i = 0; i + 1 < args.length; i++) {
            if (args[i].equals("--timeout")) {
                timeout = Double.parseDouble(args[i + 1]);
            }
        }
        String line;
        try {
            Operation op = Operation.prepare(cfg, false);
            Watchdog.Outcome out = Watchdog.run(op::run, timeout);
            line = String.format(Locale.ROOT, "RESULT status=%s time_s=%s n=%d nnz_a=%d nnz_b=%d nnz_c=%d msg=%s",
                out.status(), out.status() == Watchdog.Status.OK ? Double.toString(out.timeS()) : "",
                op.n(), op.nnzA(), op.nnzB(), out.status() == Watchdog.Status.OK ? op.nnzC() : -1,
                out.message().replace(' ', '_'));
        } catch (OutOfMemoryError e) {
            line = "RESULT status=OOM time_s= n=" + cfg.n() + " nnz_a=-1 nnz_b=-1 nnz_c=-1 msg=inputs_do_not_fit";
        } catch (Throwable t) {
            String msg = t.toString().replace(' ', '_');
            // Array sizes above Integer.MAX_VALUE cannot exist in Java: report as OOM (does not fit).
            String status = t instanceof ArithmeticException ? "OOM" : "ERROR";
            line = "RESULT status=" + status + " time_s= n=" + cfg.n() + " nnz_a=-1 nnz_b=-1 nnz_c=-1 msg=" + msg;
        }
        System.out.println(line);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
