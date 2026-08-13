package io.github.eddytodd.bouncingballs.research;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;

/** Deterministic A/B mechanism gate for CADQ canonical local-refresh traversal. */
public final class CadqLocalTraversalProbeCli {
    private static final String PROPERTY = "bouncingballs.cadqCanonicalLocalTraversal";
    private static final List<Workloads.Kind> WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM, Workloads.Kind.DENSE_UNIFORM, Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY, Workloads.Kind.WALL_DOMINATED, Workloads.Kind.ACCELERATED,
            Workloads.Kind.DIFFERENTIAL_ACCELERATION, Workloads.Kind.ADVERSARIAL_INVALIDATION);
    private record Result(StateSnapshot state, SimulationStats stats) {}

    private CadqLocalTraversalProbeCli() {}

    public static void main(String[] args) {
        System.out.println("workload,balls,seed,batches,pairToi,localPairs,baselineOwners,candidateOwners,reductionPercent");
        for (Workloads.Kind workload : WORKLOADS) for (int balls : new int[]{100, 300}) for (long seed = 1; seed <= 3; seed++) {
            Result baseline = run(workload, balls, seed, false);
            Result candidate = run(workload, balls, seed, true);
            verify(workload, balls, seed, baseline, candidate);
            long a = baseline.stats.cadqLocalOwnersVisited, b = candidate.stats.cadqLocalOwnersVisited;
            double reduction = a == 0 ? 0 : 100.0 * (a - b) / a;
            System.out.printf(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%d,%.6f%n",
                    workload, balls, seed, baseline.stats.physicalContactBatches, baseline.stats.pairToiQueries,
                    baseline.stats.cadqLocalPairRefreshes, a, b, reduction);
        }
    }

    private static Result run(Workloads.Kind workload, int balls, long seed, boolean bounded) {
        String old = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, Boolean.toString(bounded));
            Workloads.Setup setup = Workloads.create(workload, balls, seed, 1.0);
            Simulation simulation = new Simulation(setup.balls(), setup.bounds(), new SimulationConfig(
                    SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, 1e-3));
            simulation.advance(0.25, 100_000);
            return new Result(StateSnapshot.capture(simulation), simulation.stats());
        } finally {
            if (old == null) System.clearProperty(PROPERTY); else System.setProperty(PROPERTY, old);
        }
    }

    private static void verify(Workloads.Kind workload, int balls, long seed, Result x, Result y) {
        SimulationStats a = x.stats, b = y.stats;
        if (!x.state.equals(y.state)
                || a.physicalContactBatches != b.physicalContactBatches
                || a.physicalContactsObserved != b.physicalContactsObserved
                || a.physicalContactHash != b.physicalContactHash
                || a.pairToiQueries != b.pairToiQueries
                || a.quadraticPairToiQueries != b.quadraticPairToiQueries
                || a.quarticPairToiQueries != b.quarticPairToiQueries
                || a.cadqLocalPairRefreshes != b.cadqLocalPairRefreshes
                || a.cadqLocalOwnersModified != b.cadqLocalOwnersModified
                || a.cadqFullReselections != b.cadqFullReselections) {
            throw new IllegalStateException("mechanism changed for " + workload + " balls=" + balls + " seed=" + seed);
        }
    }
}
