package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.StateSnapshot;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SweepAndPruneSchedulerTest {
    private static final double STATE_TOLERANCE_MULTIPLIER = 10_000;

    @Test
    void matchesAllPairsAcrossDeterministicWorkloadMatrix() {
        for (Workloads.Kind workload : Workloads.Kind.values()) {
            int count = workload == Workloads.Kind.SYMMETRIC_IMPACT ? 3
                    : workload == Workloads.Kind.NEWTON_CRADLE ? 8 : 24;
            for (long seed = 1; seed <= 3; seed++) {
                StateSnapshot reference = run(workload, count, seed, SchedulerKind.ALL_PAIRS_CCD);
                StateSnapshot actual = run(workload, count, seed, SchedulerKind.SWEEP_AND_PRUNE_CCD);
                StateSnapshot.Difference difference = actual.compareTo(
                        reference,
                        NumericalPolicy.DEFAULT,
                        STATE_TOLERANCE_MULTIPLIER);
                String message = workload + " seed=" + seed
                        + " reason=" + difference.reason()
                        + " maxPositionError=" + difference.maxPositionError()
                        + " maxVelocityError=" + difference.maxVelocityError();
                assertTrue(difference.equivalent(), message);
            }
        }
    }

    @Test
    void sparseSweepEliminatesProvablySeparatedConstructionPairs() {
        List<Ball> balls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            balls.add(new Ball(
                    i,
                    1,
                    1,
                    1,
                    new Vec2(20 + 40.0 * i, 0),
                    new Vec2(1, 0),
                    new Vec2(0, 0)));
        }

        Simulation simulation = new Simulation(
                balls,
                new Bounds(0, -10, 800, 10),
                new SimulationConfig(
                        SchedulerKind.SWEEP_AND_PRUNE_CCD,
                        ResolverKind.ITERATIVE,
                        NumericalPolicy.DEFAULT,
                        0.001));

        SimulationStats stats = simulation.stats();
        assertEquals(190, stats.sapCanonicalPairs);
        assertEquals(0, stats.sapExactPairCandidates);
        assertEquals(0, stats.pairToiQueries);
        assertEquals(0, stats.sapAllPairsFallbackRebuilds);
        assertEquals(100.0, stats.sapPairPrunePercent(), 1e-12);
    }

    private static StateSnapshot run(
            Workloads.Kind workload,
            int count,
            long seed,
            SchedulerKind scheduler) {
        Workloads.Setup setup = Workloads.create(workload, count, seed, 1);
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(
                        scheduler,
                        ResolverKind.ITERATIVE,
                        NumericalPolicy.DEFAULT,
                        0.001));
        simulation.advance(2.0, 100_000);
        return StateSnapshot.capture(simulation);
    }
}
