package es.ulpgc.bigdata.a2.bench;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.ejml.data.DMatrixSparseCSC;
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

import es.ulpgc.bigdata.a2.sparse.SparseMultiplier;
import es.ulpgc.bigdata.a2.util.Inputs;

/**
 * Part B: sparse x sparse product C = A * B with EJML (CSC). Measured region: only
 * {@code CommonOps_DSCC.mult}; the inputs are built in CSC in the trial setup. The output matrix and
 * EJML's work arrays are reused between invocations (see {@link SparseMultiplier}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class SparseBenchmark {

    @Param({"1000"})
    public int n;

    @Param({"0.001", "0.01", "0.05", "0.1"})
    public double density;

    @Param({"none"})
    public String matrix;

    @Param({"42"})
    public long seed;

    private DMatrixSparseCSC a;
    private DMatrixSparseCSC b;
    private SparseMultiplier multiplier;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        SparseMultiplier.forceSingleThread();
        Inputs.Sparse s = Inputs.sparse(matrix, n, density, seed);
        a = s.a();
        b = s.b();
        multiplier = new SparseMultiplier();
    }

    @Benchmark
    public DMatrixSparseCSC multiply() {
        return multiplier.multiply(a, b);
    }
}
