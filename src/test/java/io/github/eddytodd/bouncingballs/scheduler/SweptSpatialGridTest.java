package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SweptSpatialGridTest {
    private static final String SPATIAL_PROPERTY = "bouncingballs.cadqSpatialPruning";
    private static final String TEMPORAL_PROPERTY = "bouncingballs.cadqTemporalPruning";

    @Test
    void sweptGridNeverDropsAnExactCollisionInsideTheQueryHorizon() {
        Random random = new Random(0x5eedL);
        Ball[] balls = new Ball[24];
        for (int i = 0; i < balls.length; i++) {
            balls[i] = new Ball(
                    i,
                    1,
                    1,
                    1,
                    new Vec2(20 + random.nextDouble() * 960, 20 + random.nextDouble() * 960),
                    new Vec2(-200 + random.nextDouble() * 400, -200 + random.nextDouble() * 400),
                    new Vec2(-20 + random.nextDouble() * 40, -20 + random.nextDouble() * 40));
        }

        SweptSpatialGrid grid = new SweptSpatialGrid();
        SimulationStats stats = new SimulationStats();
        grid.rebuild(balls, new Bounds(0, 0, 1000, 1000), stats);
        int[] candidates = new int[balls.length];

        for (double horizon : new double[] {0.01, 0.1, 0.5, 2.0}) {
            for (int owner = 0; owner < balls.length; owner++) {
                int count = grid.queryCanonicalCandidates(
                        owner, horizon, NumericalPolicy.DEFAULT, stats, candidates);
                BitSet included = new BitSet(balls.length);
                for (int i = 0; i < count; i++) included.set(candidates[i]);

                for (int other = owner + 1; other < balls.length; other++) {
                    double exact = TimeOfImpact.ballBall(balls[owner], balls[other], NumericalPolicy.DEFAULT);
                    if (Double.isFinite(exact)
                            && exact <= horizon + NumericalPolicy.DEFAULT.tolerance(Math.max(exact, horizon))) {
                        assertTrue(
                                included.get(other),
                                () -> "grid excluded exact collision owner=" + owner + " other=" + other
                                        + " horizon=" + horizon + " toi=" + exact);
                    }
                }
            }
        }
    }

    @Test
    void cadqSpatialLayerExcludesAxisSeparatedPairsThatTemporalNormCannotReject() {
        SimulationStats enabled = build(axisSeparatedSetup(), true);
        SimulationStats disabled = build(axisSeparatedSetup(), false);

        assertTrue(enabled.cadqSpatialQueries > 0);
        assertTrue(enabled.cadqSpatialPairsExcluded >= 20,
                "short swept Y envelope should exclude the vertically separated bodies");
        assertTrue(enabled.cadqSpatialExcludePercent() > 0);
        assertTrue(enabled.toiQueries < disabled.toiQueries,
                "spatial rejection should avoid exact pair TOI that the scalar temporal norm admits");
        assertEquals(0, disabled.cadqSpatialQueries);
        assertEquals(0, disabled.cadqSpatialPairsExcluded);
    }

    @Test
    void spatialGridIncludesFastAndDifferentiallyAcceleratedContacts() {
        List<Ball> fast = List.of(
                ball(0, 100, 500, 500, 0, 0, 0),
                ball(1, 600, 500, -500, 0, 0, 0),
                ball(2, 900, 900, 0, 0, 0, 0));
        assertExactContactIncluded(fast, 0, 1);

        List<Ball> accelerated = List.of(
                ball(0, 100, 500, 0, 0, 120, 0),
                ball(1, 300, 500, 0, 0, -80, 0),
                ball(2, 900, 900, 0, 0, 0, 0));
        assertExactContactIncluded(accelerated, 0, 1);
    }

    private static void assertExactContactIncluded(List<Ball> source, int owner, int other) {
        Ball[] balls = source.toArray(Ball[]::new);
        double horizon = TimeOfImpact.ballBall(balls[owner], balls[other], NumericalPolicy.DEFAULT);
        assertTrue(Double.isFinite(horizon));

        SweptSpatialGrid grid = new SweptSpatialGrid();
        SimulationStats stats = new SimulationStats();
        grid.rebuild(balls, new Bounds(0, 0, 1000, 1000), stats);
        int[] candidates = new int[balls.length];
        int count = grid.queryCanonicalCandidates(owner, horizon, NumericalPolicy.DEFAULT, stats, candidates);

        for (int i = 0; i < count; i++) if (candidates[i] == other) return;
        fail("exact contact candidate was excluded at its collision horizon");
    }

    private static SimulationStats build(List<Ball> balls, boolean spatialEnabled) {
        String oldSpatial = System.getProperty(SPATIAL_PROPERTY);
        String oldTemporal = System.getProperty(TEMPORAL_PROPERTY);
        try {
            System.setProperty(SPATIAL_PROPERTY, Boolean.toString(spatialEnabled));
            System.setProperty(TEMPORAL_PROPERTY, "true");
            ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
            SimulationStats stats = new SimulationStats();
            queue.rebuild(balls, new Bounds(0, -1000, 1000, 1000), NumericalPolicy.DEFAULT, stats);
            return stats;
        } finally {
            restore(SPATIAL_PROPERTY, oldSpatial);
            restore(TEMPORAL_PROPERTY, oldTemporal);
        }
    }

    private static List<Ball> axisSeparatedSetup() {
        List<Ball> balls = new ArrayList<>();
        balls.add(ball(0, 10, 0, 100, 0, 0, 0));
        for (int i = 1; i <= 24; i++) {
            balls.add(ball(i, 10, i * 40 - 500, 0, 0, 0, 0));
        }
        return balls;
    }

    private static Ball ball(
            int id,
            double x,
            double y,
            double vx,
            double vy,
            double ax,
            double ay) {
        return new Ball(id, 1, 1, 1, new Vec2(x, y), new Vec2(vx, vy), new Vec2(ax, ay));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
