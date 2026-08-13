package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

final class EventPredictions {
    private EventPredictions() {}
    static long sequence;

    static void addFor(Ball a, List<Ball> balls, Bounds bounds, NumericalPolicy p, SimulationStats s,
                       Collection<CollisionEvent> out, boolean pairs, double now) {
        for (Ball b : balls) if (pairs && b.id > a.id) addPair(a, b, p, s, out, now);
        for (int w = 0; w < 4; w++) addWall(a, bounds, w, p, s, out, now);
    }

    /** Evaluate pair TOI without allocating a CollisionEvent. Returns relative time from {@code now}. */
    static double pairTime(Ball a, Ball b, NumericalPolicy p, SimulationStats s) {
        s.candidateChecks++;
        s.toiQueries++;
        s.pairToiQueries++;
        if (hasRelativeAcceleration(a, b)) s.quarticPairToiQueries++;
        else s.quadraticPairToiQueries++;
        return TimeOfImpact.ballBall(a, b, p);
    }

    /** Evaluate wall TOI without allocating a CollisionEvent. Returns relative time from {@code now}. */
    static double wallTime(Ball a, Bounds q, int w, NumericalPolicy p, SimulationStats s) {
        s.toiQueries++;
        s.wallToiQueries++;
        return TimeOfImpact.wall(a, q, w, p);
    }

    /** Materialize a previously selected pair prediction at an absolute simulation time. */
    static CollisionEvent materializePair(Ball a, Ball b, double time, SimulationStats s) {
        s.predictedEventMaterializations++;
        return new CollisionEvent(time, a, b, CollisionEvent.NONE, a.generation, b.generation, sequence++);
    }

    /** Materialize a previously selected wall prediction at an absolute simulation time. */
    static CollisionEvent materializeWall(Ball a, int wall, double time, SimulationStats s) {
        s.predictedEventMaterializations++;
        return new CollisionEvent(time, a, null, wall, a.generation, -1, sequence++);
    }

    static CollisionEvent pair(Ball a, Ball b, NumericalPolicy p, SimulationStats s, double now) {
        double t = pairTime(a, b, p, s);
        return Double.isFinite(t) ? materializePair(a, b, now + t, s) : null;
    }

    static CollisionEvent wall(Ball a, Bounds q, int w, NumericalPolicy p, SimulationStats s, double now) {
        double t = wallTime(a, q, w, p, s);
        return Double.isFinite(t) ? materializeWall(a, w, now + t, s) : null;
    }

    static void addPair(Ball a, Ball b, NumericalPolicy p, SimulationStats s,
                        Collection<CollisionEvent> out, double now) {
        CollisionEvent event = pair(a, b, p, s, now);
        if (event != null) out.add(event);
    }

    static void addWall(Ball a, Bounds q, int w, NumericalPolicy p, SimulationStats s,
                        Collection<CollisionEvent> out, double now) {
        CollisionEvent event = wall(a, q, w, p, s, now);
        if (event != null) out.add(event);
    }

    static boolean valid(CollisionEvent e) {
        return e.a().generation == e.generationA()
                && (e.b() == null || e.b().generation == e.generationB());
    }

    /**
     * Under piecewise-constant acceleration the ball-ball contact polynomial has a positive quartic coefficient
     * whenever relative acceleration is nonzero. Equal acceleration vectors cancel exactly and reduce the pair
     * equation to the constant-relative-velocity quadratic even though both world trajectories are accelerated.
     */
    private static boolean hasRelativeAcceleration(Ball a, Ball b) {
        return a.acceleration.x != b.acceleration.x || a.acceleration.y != b.acceleration.y;
    }
}
