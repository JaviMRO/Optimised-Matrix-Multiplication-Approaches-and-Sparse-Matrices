package es.ulpgc.bigdata.a2.bench;

/**
 * Runs one operation in a worker thread and waits at most {@code timeoutS} seconds for it.
 * The kernels never check for interruption, so a timed-out operation cannot be stopped: the caller
 * must end the JVM (that is why every pilot/memory configuration runs in its own child JVM).
 */
public final class Watchdog {

    public enum Status { OK, TIMEOUT, OOM, ERROR }

    /** Outcome of one timed run; timeS is the wall-clock time of the operation (only meaningful for OK). */
    public record Outcome(Status status, double timeS, String message) {
    }

    private Watchdog() {
    }

    public static Outcome run(Runnable op, double timeoutS) throws InterruptedException {
        long[] elapsed = {-1};
        Throwable[] failure = {null};
        Thread worker = new Thread(() -> {
            try {
                long t0 = System.nanoTime();
                op.run();
                elapsed[0] = System.nanoTime() - t0;
            } catch (Throwable t) {
                failure[0] = t;
            }
        }, "measured-operation");
        worker.setDaemon(true);
        worker.start();
        worker.join((long) (timeoutS * 1000));
        if (worker.isAlive()) {
            return new Outcome(Status.TIMEOUT, Double.NaN, "exceeded " + timeoutS + " s");
        }
        if (failure[0] instanceof OutOfMemoryError e) {
            return new Outcome(Status.OOM, Double.NaN, String.valueOf(e.getMessage()));
        }
        if (failure[0] != null) {
            return new Outcome(Status.ERROR, Double.NaN, failure[0].toString());
        }
        return new Outcome(Status.OK, elapsed[0] / 1e9, "");
    }
}
