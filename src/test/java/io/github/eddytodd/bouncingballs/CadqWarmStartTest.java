package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.scheduler.ComputeAheadDependencyQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CadqWarmStartTest {
    private static Ball ball(int id, double x, double vx) {
        return new Ball(id, 1, 1, 1, new Vec2(x, 0), new Vec2(vx, 0), new Vec2(0, 0));
    }

    @Test
    void constructionHasNoWarmStartHistory() {
        List<Ball> balls = List.of(ball(0, 0, 1), ball(1, 10, 0), ball(2, 60, 0));
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(balls, new Bounds(-100, -10, 100, 10), NumericalPolicy.DEFAULT, stats);

        assertEquals(0, stats.cadqWarmStartOpportunities);
    }

    @Test
    void fullReselectionUsesPreviouslyRetainedPairFirst() {
        Ball owner = ball(0, 0, 1);
        Ball priorTarget = ball(1, 10, 0);
        Ball far = ball(2, 60, 0);
        List<Ball> balls = List.of(owner, priorTarget, far);
        Bounds bounds = new Bounds(-100, -10, 100, 10);
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(balls, bounds, NumericalPolicy.DEFAULT, stats);

        owner.generation++;
        queue.trajectoriesChanged(Set.of(owner), balls, bounds, NumericalPolicy.DEFAULT, stats);

        assertEquals(1, stats.cadqWarmStartOpportunities);
        assertEquals(1, stats.cadqWarmStartExactProbes);
        assertEquals(1, stats.cadqWarmStartFiniteHits);
        assertEquals(1, stats.cadqWarmStartHorizonTightens);
    }
}
