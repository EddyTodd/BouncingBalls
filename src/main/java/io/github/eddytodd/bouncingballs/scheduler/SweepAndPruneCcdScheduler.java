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
            double horizon = SweptAabb.conservativeHorizon(earliestWall, policy);
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
        List<SweptAabb.Box> intervals = new ArrayList<>(balls.size());
        for (Ball ball : balls) intervals.add(SweptAabb.forBall(ball, horizon, policy));
        intervals.sort(Comparator
                .comparingDouble(SweptAabb.Box::minX)
                .thenComparingInt(interval -> interval.ball().id));

        ArrayList<SweptAabb.Box> active = new ArrayList<>();
        for (SweptAabb.Box current : intervals) {
            int write = 0;
            for (int read = 0; read < active.size(); read++) {
                SweptAabb.Box prior = active.get(read);
                stats.sapXActiveChecks++;
                if (prior.maxX() < current.minX()) continue;

                if (write != read) active.set(write, prior);
                write++;
                stats.sapXOverlapPairs++;

                if (prior.maxY() < current.minY() || current.maxY() < prior.minY()) continue;
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
}
