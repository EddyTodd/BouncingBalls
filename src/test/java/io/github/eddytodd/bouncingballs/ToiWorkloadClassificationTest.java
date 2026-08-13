package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.scheduler.AllPairsCcdScheduler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToiWorkloadClassificationTest {
    @Test
    void sharedGravityCancelsFromBallBallRelativeAcceleration() {
        SimulationStats stats = initialAllPairsStats(Workloads.Kind.ACCELERATED, 6);
        long pairs = 6L * 5L / 2L;

        assertEquals(pairs, stats.pairToiQueries);
        assertEquals(pairs, stats.quadraticPairToiQueries,
                "uniform gravity accelerates world trajectories but cancels from pair-relative motion");
        assertEquals(0, stats.quarticPairToiQueries);
        assertEquals(4L * 6L, stats.wallToiQueries);
        assertEquals(stats.pairToiQueries + stats.wallToiQueries, stats.toiQueries);
    }

    @Test
    void differentialAccelerationExercisesTrueQuarticPairToi() {
        SimulationStats stats = initialAllPairsStats(Workloads.Kind.DIFFERENTIAL_ACCELERATION, 6);
        long pairs = 6L * 5L / 2L;

        assertEquals(pairs, stats.pairToiQueries);
        assertEquals(0, stats.quadraticPairToiQueries);
        assertEquals(pairs, stats.quarticPairToiQueries,
                "the dedicated workload must keep nonzero relative acceleration for every generated pair");
        assertEquals(4L * 6L, stats.wallToiQueries);
        assertEquals(stats.pairToiQueries + stats.wallToiQueries, stats.toiQueries);
    }

    private static SimulationStats initialAllPairsStats(Workloads.Kind workload, int balls) {
        Workloads.Setup setup = Workloads.create(workload, balls, 1, 1.0);
        SimulationStats stats = new SimulationStats();
        new AllPairsCcdScheduler().rebuild(
                setup.balls(), setup.bounds(), NumericalPolicy.DEFAULT, stats);
        return stats;
    }
}
