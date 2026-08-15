package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SweptBvhCcdSchedulerTest {
    @Test
    void finiteWallHorizonPrunesSeparatedSweptBoxesBeforeExactToi() {
        List<Ball> balls = new ArrayList<>();
        balls.add(new Ball(0, 5, 1, 1, new Vec2(6, 50), new Vec2(-10, 0), new Vec2(0, 0)));
        for (int id = 1; id < 20; id++) {
            balls.add(new Ball(
                    id,
                    5,
                    1,
                    1,
                    new Vec2(100 + 40 * (id - 1), 50),
                    new Vec2(0, 0),
                    new Vec2(0, 0)));
        }

        Simulation simulation = simulation(balls, new Bounds(0, 0, 1000, 100));
        SimulationStats stats = simulation.stats();

        assertEquals(1, stats.bvhRebuilds);
        assertEquals(190, stats.bvhCanonicalPairs);
        assertEquals(0, stats.bvhExactPairCandidates);
        assertEquals(0, stats.pairToiQueries);
        assertEquals(39, stats.bvhNodesBuilt);
        assertEquals(0, stats.bvhAllPairsFallbackRebuilds);
        assertTrue(stats.bvhNodeVisits > 0);
        assertEquals(100.0, stats.bvhPairPrunePercent(), 0.0);
    }

    @Test
    void noFiniteWallHorizonFallsBackToCanonicalAllPairs() {
        List<Ball> balls = List.of(
                new Ball(0, 5, 1, 1, new Vec2(100, 50), new Vec2(0, 0), new Vec2(0, 0)),
                new Ball(1, 5, 1, 1, new Vec2(300, 50), new Vec2(0, 0), new Vec2(0, 0)),
                new Ball(2, 5, 1, 1, new Vec2(500, 50), new Vec2(0, 0), new Vec2(0, 0)));

        Simulation simulation = simulation(balls, new Bounds(0, 0, 1000, 100));
        SimulationStats stats = simulation.stats();

        assertEquals(1, stats.bvhAllPairsFallbackRebuilds);
        assertEquals(3, stats.bvhCanonicalPairs);
        assertEquals(3, stats.bvhExactPairCandidates);
        assertEquals(3, stats.pairToiQueries);
        assertEquals(0, stats.bvhNodesBuilt);
    }

    private static Simulation simulation(List<Ball> balls, Bounds bounds) {
        return new Simulation(
                balls,
                bounds,
                new SimulationConfig(
                        SchedulerKind.SWEPT_BVH_CCD,
                        ResolverKind.ITERATIVE,
                        NumericalPolicy.DEFAULT,
                        0.001));
    }
}
