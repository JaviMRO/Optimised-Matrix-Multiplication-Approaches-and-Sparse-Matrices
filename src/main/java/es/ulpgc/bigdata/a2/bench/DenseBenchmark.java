package es.ulpgc.bigdata.a2.bench;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import es.ulpgc.bigdata.a2.dense.DenseMethod;
import es.ulpgc.bigdata.a2.util.Inputs;

/**
 * Part A (and the dense side of Part B): C = A * B with one of the dense methods.
 *
 * <p>Measured region: only the call to the kernel. A, B and C are allocated in the trial setup, outside
 * the measurement. NaiveIJK writes every C element once; IKJ, Blocked and Strassen clear C themselves
 * (an O(n^2) pass, negligible against the O(n^3) work), so no per-invocation setup is needed and the
 * AverageTime mode can be used for small n. Strassen's temporaries and padding are allocated inside the
 * kernel and are therefore part of its time and of gc.alloc.rate.norm.
 *
 * <p>The annotations are defaults for manual runs; {@link Suite} sets mode, iterations and JVM flags.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class DenseBenchmark {

    @Param({"naive", "ikj", "blocked", "strassen"})
    public String method;

    @Param({"1024"})
    public int n;

    /** 1.0 = dense generator; below 1 the inputs are the densified synthetic sparse matrices of Part B. */
    @Param({"1.0"})
    public double density;

    /** "none" or a SuiteSparse matrix name (then n and density are ignored and A = B = that matrix). */
    @Param({"none"})
    public String matrix;

    @Param({"64"})
    public int blockSize;

    @Param({"128"})
    public int cutoff;

    @Param({"42"})
    public long seed;

    private DenseMethod kernel;
    private double[] a;
    private double[] b;
    private double[] c;
    private int size;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        kernel = DenseMethod.parse(method);
        Inputs.Dense d = Inputs.dense(matrix, n, density, seed);
        a = d.a();
        b = d.b();
        size = d.n();
        c = new double[size * size];
    }

    /** Returning C lets JMH consume it (Blackhole), so the computation cannot be removed. */
    @Benchmark
    public double[] multiply() {
        kernel.multiply(a, b, c, size, blockSize, cutoff);
        return c;
    }
}
