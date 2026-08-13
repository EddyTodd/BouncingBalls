package io.github.eddytodd.bouncingballs.research;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;

/** Same-JVM interleaved A/B for the CADQ canonical local-refresh traversal bound. */
public final class CadqLocalTraversalTimingCli {
    private static final String PROPERTY = "bouncingballs.cadqCanonicalLocalTraversal";
    private static final List<Workloads.Kind> WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM, Workloads.Kind.DENSE_UNIFORM, Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY, Workloads.Kind.WALL_DOMINATED, Workloads.Kind.ACCELERATED,
            Workloads.Kind.DIFFERENTIAL_ACCELERATION, Workloads.Kind.ADVERSARIAL_INVALIDATION);
    private record Run(StateSnapshot state, SimulationStats stats, long construction, long advance) {}

    private CadqLocalTraversalTimingCli() {}

    public static void main(String[] args) {
        System.out.println("workload,balls,seed,repetition,baselineConstruction,candidateConstruction,baselineAdvance,candidateAdvance,baselineTotal,candidateTotal");
        for (Workloads.Kind workload : WORKLOADS) for (int balls : new int[]{100, 300}) for (long seed = 1; seed <= 3; seed++) {
            for (int warmup = 0; warmup < 3; warmup++) { run(workload, balls, seed, false); run(workload, balls, seed, true); }
            for (int repetition = 0; repetition < 20; repetition++) {
                Run baseline, candidate;
                if ((repetition & 1) == 0) { baseline = run(workload, balls, seed, false); candidate = run(workload, balls, seed, true); }
                else { candidate = run(workload, balls, seed, true); baseline = run(workload, balls, seed, false); }
                verify(workload, balls, seed, baseline, candidate);
                System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                        workload, balls, seed, repetition,
                        baseline.construction, candidate.construction, baseline.advance, candidate.advance,
                        baseline.construction + baseline.advance, candidate.construction + candidate.advance);
            }
        }
    }

    private static Run run(Workloads.Kind workload, int balls, long seed, boolean bounded) {
        System.setProperty(PROPERTY, Boolean.toString(bounded));
        Workloads.Setup setup = Workloads.create(workload, balls, seed, 1.0);
        long started = System.nanoTime();
        Simulation simulation = new Simulation(setup.balls(), setup.bounds(), new SimulationConfig(
                SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, 1e-3));
        long construction = System.nanoTime() - started;
        started = System.nanoTime();
        simulation.advance(0.25, 100_000);
        long advance = System.nanoTime() - started;
        return new Run(StateSnapshot.capture(simulation), simulation.stats(), construction, advance);
    }

    private static void verify(Workloads.Kind workload, int balls, long seed, Run x, Run y) {
        if (!x.state.equals(y.state)
                || x.stats.physicalContactHash != y.stats.physicalContactHash
                || x.stats.pairToiQueries != y.stats.pairToiQueries
                || x.stats.cadqLocalPairRefreshes != y.stats.cadqLocalPairRefreshes) {
            throw new IllegalStateException("A/B mechanism changed for " + workload + " balls=" + balls + " seed=" + seed);
        }
    }
}
