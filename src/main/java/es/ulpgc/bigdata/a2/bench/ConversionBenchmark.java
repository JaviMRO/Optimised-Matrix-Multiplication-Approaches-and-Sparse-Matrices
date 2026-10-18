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
import org.openjdk.jmh.infra.Blackhole;

import es.ulpgc.bigdata.a2.sparse.SparseMultiplier;
import es.ulpgc.bigdata.a2.util.Inputs;

/**
 * Part B: dense-to-CSC conversion of both operands, measured separately from the sparse kernel.
 * The dense row-major arrays are the same matrices used by {@link DenseBenchmark} at this density.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class ConversionBenchmark {

    @Param({"1000"})
    public int n;

    @Param({"0.001", "0.01", "0.05", "0.1"})
    public double density;

    @Param({"none"})
    public String matrix;

    @Param({"42"})
    public long seed;

    private double[] a;
    private double[] b;
    private int size;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        Inputs.Dense d = Inputs.dense(matrix, n, density, seed);
        a = d.a();
        b = d.b();
        size = d.n();
    }

    @Benchmark
    public void convert(Blackhole bh) {
        bh.consume(SparseMultiplier.denseToCsc(a, size));
        bh.consume(SparseMultiplier.denseToCsc(b, size));
    }
}
