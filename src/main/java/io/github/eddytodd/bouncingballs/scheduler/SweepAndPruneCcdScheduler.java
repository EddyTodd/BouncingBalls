package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Rebuild-on-change continuous scheduler with a conservative swept-AABB sweep-and-prune broad phase.
 *
 * <p>The broad phase never predicts contact itself. It first computes every exact wall TOI and uses the earliest
 * finite wall event as a conservative global horizon. Any ball-ball event that could beat or tie that wall must occur
 * inside that horizon. Each body's exact constant-acceleration trajectory is therefore enclosed over the horizon by
 * radius-expanded X/Y intervals, including the interior parabolic extremum when present. A pair reaches the exact TOI
 * solver only when those swept intervals overlap on both axes.</p>
 *
 * <p>If no finite conservative horizon exists, the scheduler deliberately falls back to canonical all-pairs CCD.
 * After every trajectory-changing event batch the broad phase is rebuilt from the synchronized state. This makes the
 * implementation a clean architectural comparator to {@link AllPairsCcdScheduler}, rather than another CADQ filter.</p>
 */
public final class SweepAndPruneCcdScheduler implements EventScheduler {
    private static final double SLACK_MULTIPLIER = 8.0;

    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private double now;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        queue.clear();
        stats.sapRebuilds++;

        double earliestWall = Double.POSITIVE_INFINITY;
        for (Ball ball : balls) {
            for (int wall = 0; wall < 4; wall++) {
                double t = EventPredictions.wallTime(ball, bounds, wall, policy, stats);
                if (!Double.isFinite(t)) continue;
                queue.add(EventPredictions.materializeWall(ball, wall, now + t, stats));
                earliestWall = Math.min(earliestWall, t);
            }
        }

        long n = balls.size();
        long canonicalPairs = n * (n - 1L) / 2L;
        stats.sapCanonicalPairs += canonicalPairs;

        if (canonicalPairs != 0) {
            double horizon = conservativeHorizon(earliestWall, policy);
            if (Double.isFinite(horizon)) {
                addSweepCandidates(balls, horizon, policy, stats);
            } else {
                stats.sapAllPairsFallbackRebuilds++;
                stats.sapExactPairCandidates += canonicalPairs;
                addAllPairs(balls, policy, stats);
            }
        }

        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private void addSweepCandidates(
            List<Ball> balls,
            double horizon,
            NumericalPolicy policy,
            SimulationStats stats) {
        List<SweptBounds> intervals = new ArrayList<>(balls.size());
        for (Ball ball : balls) intervals.add(sweptBounds(ball, horizon, policy));
        intervals.sort(Comparator
                .comparingDouble(SweptBounds::minX)
                .thenComparingInt(interval -> interval.ball().id));

        ArrayList<SweptBounds> active = new ArrayList<>();
        for (SweptBounds current : intervals) {
            int write = 0;
            for (int read = 0; read < active.size(); read++) {
                SweptBounds prior = active.get(read);
                stats.sapXActiveChecks++;
                if (prior.maxX() < current.minX()) continue;

                if (write != read) active.set(write, prior);
                write++;
                stats.sapXOverlapPairs++;

                if (!overlaps(prior.minY(), prior.maxY(), current.minY(), current.maxY())) continue;
                stats.sapExactPairCandidates++;
                EventPredictions.addPair(prior.ball(), current.ball(), policy, stats, queue, now);
            }

            if (write < active.size()) active.subList(write, active.size()).clear();
            active.add(current);
        }
    }

    private void addAllPairs(List<Ball> balls, NumericalPolicy policy, SimulationStats stats) {
        for (int i = 0; i < balls.size(); i++) {
            for (int j = i + 1; j < balls.size(); j++) {
                EventPredictions.addPair(balls.get(i), balls.get(j), policy, stats, queue, now);
            }
        }
    }

    private static double conservativeHorizon(double exactWallHorizon, NumericalPolicy policy) {
        if (!Double.isFinite(exactWallHorizon)) return Double.POSITIVE_INFINITY;
        double slack = SLACK_MULTIPLIER * policy.tolerance(exactWallHorizon);
        double expanded = exactWallHorizon + slack;
        return Double.isFinite(expanded) ? Math.nextUp(expanded) : Double.POSITIVE_INFINITY;
    }

    private static SweptBounds sweptBounds(Ball ball, double horizon, NumericalPolicy policy) {
        AxisBounds x = sweptAxis(
                ball.position.x,
                ball.velocity.x,
                ball.acceleration.x,
                ball.radius,
                horizon,
                policy);
        AxisBounds y = sweptAxis(
                ball.position.y,
                ball.velocity.y,
                ball.acceleration.y,
                ball.radius,
                horizon,
                policy);
        return new SweptBounds(ball, x.min(), x.max(), y.min(), y.max());
    }

    private static AxisBounds sweptAxis(
            double position,
            double velocity,
            double acceleration,
            double radius,
            double horizon,
            NumericalPolicy policy) {
        if (!Double.isFinite(position)
                || !Double.isFinite(velocity)
                || !Double.isFinite(acceleration)
                || !Double.isFinite(radius)
                || !Double.isFinite(horizon)) {
            return AxisBounds.UNBOUNDED;
        }

        double end = positionAt(position, velocity, acceleration, horizon);
        if (!Double.isFinite(end)) return AxisBounds.UNBOUNDED;

        double min = Math.min(position, end);
        double max = Math.max(position, end);

        if (acceleration != 0.0) {
            double vertex = -velocity / acceleration;
            double timeSlack = SLACK_MULTIPLIER * policy.tolerance(horizon);
            if (Double.isFinite(vertex) && vertex >= -timeSlack && vertex <= horizon + timeSlack) {
                double t = Math.max(0.0, Math.min(horizon, vertex));
                double value = positionAt(position, velocity, acceleration, t);
                if (!Double.isFinite(value)) return AxisBounds.UNBOUNDED;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        double scale = Math.max(1.0, Math.max(Math.abs(min), Math.max(Math.abs(max), Math.abs(radius))));
        double padding = radius + SLACK_MULTIPLIER * policy.tolerance(scale);
        if (!Double.isFinite(padding)) return AxisBounds.UNBOUNDED;

        double lower = min - padding;
        double upper = max + padding;
        if (!Double.isFinite(lower) || !Double.isFinite(upper)) return AxisBounds.UNBOUNDED;
        return new AxisBounds(Math.nextDown(lower), Math.nextUp(upper));
    }

    private static double positionAt(double position, double velocity, double acceleration, double t) {
        return Math.fma(0.5 * acceleration * t, t, Math.fma(velocity, t, position));
    }

    private static boolean overlaps(double aMin, double aMax, double bMin, double bMax) {
        return aMax >= bMin && bMax >= aMin;
    }

    @Override
    public List<CollisionEvent> nextBatch(NumericalPolicy policy, SimulationStats stats) {
        if (queue.isEmpty()) return List.of();
        CollisionEvent first = queue.poll();
        stats.validEvents++;
        List<CollisionEvent> result = new ArrayList<>();
        result.add(first);
        while (!queue.isEmpty() && policy.sameTime(first.time(), queue.peek().time())) {
            result.add(queue.poll());
            stats.validEvents++;
        }
        return result;
    }

    @Override
    public void trajectoriesChanged(
            Set<Ball> changed,
            List<Ball> balls,
            Bounds bounds,
            NumericalPolicy policy,
            SimulationStats stats) {
        rebuild(balls, bounds, policy, stats);
    }

    @Override
    public void timeAdvanced(double dt) {
        now += dt;
    }

    private record AxisBounds(double min, double max) {
        private static final AxisBounds UNBOUNDED =
                new AxisBounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private record SweptBounds(Ball ball, double minX, double maxX, double minY, double maxY) {}
}
