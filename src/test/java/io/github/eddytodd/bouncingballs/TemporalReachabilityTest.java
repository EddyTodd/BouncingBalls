package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.scheduler.ComputeAheadDependencyQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TemporalReachabilityTest {
    private static final String PROPERTY = "bouncingballs.cadqTemporalPruning";

    @Test
    void velocityBoundNeverRejectsTheExactCollisionHorizon() {
        Ball a = ball(0, 0, 0, 1, 0, 0, 0);
        Ball b = ball(1, 10, 0, -1, 0, 0, 0);
        double exact = TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT);

        assertEquals(4.0, exact, 1e-10);
        assertFalse(TemporalReachability.couldContactWithin(a, b, 3.9, NumericalPolicy.DEFAULT));
        assertTrue(TemporalReachability.couldContactWithin(a, b, exact, NumericalPolicy.DEFAULT));
    }

    @Test
    void accelerationBoundNeverRejectsTheExactQuarticCollisionHorizon() {
        Ball a = ball(0, 0, 0, 0, 0, 2, 0);
        Ball b = ball(1, 10, 0, 0, 0, 0, 0);
        double exact = TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT);

        assertEquals(Math.sqrt(8.0), exact, 1e-9);
        assertFalse(TemporalReachability.couldContactWithin(a, b, 2.7, NumericalPolicy.DEFAULT));
        assertTrue(TemporalReachability.couldContactWithin(a, b, exact, NumericalPolicy.DEFAULT));
    }

    @Test
    void cadqSkipsExactPairToiWhenWallHorizonMakesContactImpossible() {
        Bounds bounds = new Bounds(0, -100, 20, 100);
        List<Ball> balls = verticalSeparationSetup();
        SimulationStats stats = updateOwnerOnce(balls, bounds, true);

        assertTrue(stats.cadqTemporalBoundChecks >= 4, "far pairs should be tested by the temporal broad phase");
        assertTrue(stats.cadqTemporalPrunes >= 4, "far pairs cannot reach the moving owner before its right wall");
        assertTrue(stats.cadqTemporalPrunePercent() > 0);
    }

    @Test
    void temporalPruningCanBeDisabledForMatchedResearchBaseline() {
        Bounds bounds = new Bounds(0, -100, 20, 100);
        SimulationStats enabled = updateOwnerOnce(verticalSeparationSetup(), bounds, true);
        SimulationStats disabled = updateOwnerOnce(verticalSeparationSetup(), bounds, false);

        assertTrue(enabled.cadqTemporalPrunes > 0);
        assertEquals(0, disabled.cadqTemporalBoundChecks);
        assertEquals(0, disabled.cadqTemporalPrunes);
        assertTrue(enabled.toiQueries < disabled.toiQueries,
                "the enabled path must save exact TOI queries rather than only adding a bookkeeping counter");
    }

    private static SimulationStats updateOwnerOnce(List<Ball> balls, Bounds bounds, boolean enabled) {
        String previous = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, Boolean.toString(enabled));
            ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
            SimulationStats stats = new SimulationStats();
            queue.rebuild(balls, bounds, NumericalPolicy.DEFAULT, stats);

            Ball owner = balls.get(0);
            owner.generation++;
            queue.trajectoriesChanged(Set.of(owner), balls, bounds, NumericalPolicy.DEFAULT, stats);
            return stats;
        } finally {
            if (previous == null) System.clearProperty(PROPERTY);
            else System.setProperty(PROPERTY, previous);
        }
    }

    private static List<Ball> verticalSeparationSetup() {
        List<Ball> balls = new ArrayList<>();
        balls.add(ball(0, 10, 0, 1, 0, 0, 0));
        balls.add(ball(1, 10, 25, 0, 0, 0, 0));
        balls.add(ball(2, 10, 45, 0, 0, 0, 0));
        balls.add(ball(3, 10, 65, 0, 0, 0, 0));
        balls.add(ball(4, 10, 85, 0, 0, 0, 0));
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
}
