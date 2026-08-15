package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SweptUniformGridCcdSchedulerTest {
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

        Simulation simulation = simulation(balls, new Bounds(0, 0, 1000, 100), SchedulerKind.SWEPT_UNIFORM_GRID_CCD);
        SimulationStats stats = simulation.stats();

        assertEquals(1, stats.gridRebuilds);
        assertEquals(190, stats.gridCanonicalPairs);
        assertEquals(0, stats.gridExactPairCandidates);
        assertEquals(0, stats.pairToiQueries);
        assertEquals(0, stats.gridAllPairsFallbackRebuilds);
        assertTrue(stats.gridCellMemberships > 0);
        assertTrue(stats.gridOccupiedCells > 0);
        assertEquals(100.0, stats.gridPairPrunePercent(), 0.0);
    }

    @Test
    void noFiniteWallHorizonFallsBackToCanonicalAllPairs() {
        List<Ball> balls = List.of(
                new Ball(0, 5, 1, 1, new Vec2(100, 50), new Vec2(0, 0), new Vec2(0, 0)),
                new Ball(1, 5, 1, 1, new Vec2(300, 50), new Vec2(0, 0), new Vec2(0, 0)),
                new Ball(2, 5, 1, 1, new Vec2(500, 50), new Vec2(0, 0), new Vec2(0, 0)));

        Simulation simulation = simulation(balls, new Bounds(0, 0, 1000, 100), SchedulerKind.SWEPT_UNIFORM_GRID_CCD);
        SimulationStats stats = simulation.stats();

        assertEquals(1, stats.gridAllPairsFallbackRebuilds);
        assertEquals(3, stats.gridCanonicalPairs);
        assertEquals(3, stats.gridExactPairCandidates);
        assertEquals(3, stats.pairToiQueries);
        assertEquals(0, stats.gridCellMemberships);
    }

    @Test
    void multiCellPairIsDeduplicatedAndMatchesSapExactCandidateSet() {
        List<Ball> balls = new ArrayList<>();
        balls.add(new Ball(0, 3, 1, 1, new Vec2(20, 20), new Vec2(10, 0), new Vec2(0, 0)));
        balls.add(new Ball(1, 3, 1, 1, new Vec2(40, 20), new Vec2(-10, 0), new Vec2(0, 0)));
        int id = 2;
        for (double y : List.of(60.0, 80.0)) {
            for (double x : List.of(10.0, 23.0, 36.0, 49.0, 62.0, 75.0, 88.0)) {
                balls.add(new Ball(id++, 2, 1, 1, new Vec2(x, y), new Vec2(0, 0), new Vec2(0, 0)));
            }
        }
        Bounds bounds = new Bounds(0, 0, 100, 100);

        Simulation sap = simulation(copy(balls), bounds, SchedulerKind.SWEEP_AND_PRUNE_CCD);
        Simulation grid = simulation(copy(balls), bounds, SchedulerKind.SWEPT_UNIFORM_GRID_CCD);

        assertEquals(sap.stats().sapExactPairCandidates, grid.stats().gridExactPairCandidates);
        assertEquals(sap.stats().pairToiQueries, grid.stats().pairToiQueries);
        assertTrue(grid.stats().gridDuplicatePairAttempts > 0, "constructed pair should share multiple cells");
        assertTrue(grid.stats().gridBucketPairAttempts > grid.stats().gridUniqueCellPairs);
    }

    private static List<Ball> copy(List<Ball> balls) {
        List<Ball> result = new ArrayList<>(balls.size());
        for (Ball ball : balls) {
            result.add(new Ball(
                    ball.id,
                    ball.radius,
                    ball.mass,
                    ball.restitution,
                    new Vec2(ball.position.x, ball.position.y),
                    new Vec2(ball.velocity.x, ball.velocity.y),
                    new Vec2(ball.acceleration.x, ball.acceleration.y)));
        }
        return result;
    }

    private static Simulation simulation(List<Ball> balls, Bounds bounds, SchedulerKind scheduler) {
        return new Simulation(
                balls,
                bounds,
                new SimulationConfig(
                        scheduler,
                        ResolverKind.ITERATIVE,
                        NumericalPolicy.DEFAULT,
                        0.001));
    }
}
