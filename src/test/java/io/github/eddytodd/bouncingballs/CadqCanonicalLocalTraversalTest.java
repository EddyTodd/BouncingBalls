package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.scheduler.ComputeAheadDependencyQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CadqCanonicalLocalTraversalTest {
    private static final String PROPERTY = "bouncingballs.cadqCanonicalLocalTraversal";

    @Test
    void lowSlotChangesSkipAllProvablyEmptyUnaffectedOwnerVisits() {
        String previous = System.getProperty(PROPERTY);
        try {
            System.setProperty(PROPERTY, "true");
            List<Ball> balls = new ArrayList<>();
            balls.add(ball(0, 10, 1));
            balls.add(ball(1, 14, 0));
            for (int i = 2; i < 8; i++) balls.add(ball(i, 300 + i * 70, 10));

            Bounds bounds = new Bounds(0, -10, 1000, 10);
            ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
            SimulationStats stats = new SimulationStats();
            queue.rebuild(balls, bounds, NumericalPolicy.DEFAULT, stats);
            long initialOwnerVisits = stats.cadqLocalOwnersVisited;
            long initialPairRefreshes = stats.cadqLocalPairRefreshes;

            balls.get(0).generation++;
            balls.get(1).generation++;
            queue.trajectoriesChanged(
                    Set.of(balls.get(0), balls.get(1)),
                    balls,
                    bounds,
                    NumericalPolicy.DEFAULT,
                    stats);

            assertEquals(0, stats.cadqLocalOwnersVisited - initialOwnerVisits,
                    "owners at or above the highest changed slot cannot canonically own a changed pair");
            assertEquals(0, stats.cadqLocalPairRefreshes - initialPairRefreshes,
                    "the traversal bound must remove only owner visits that contain no local pair work");
        } finally {
            if (previous == null) System.clearProperty(PROPERTY);
            else System.setProperty(PROPERTY, previous);
        }
    }

    private static Ball ball(int id, double x, double vx) {
        return new Ball(id, 1, 1, 1, new Vec2(x, 0), new Vec2(vx, 0), new Vec2(0, 0));
    }
}
