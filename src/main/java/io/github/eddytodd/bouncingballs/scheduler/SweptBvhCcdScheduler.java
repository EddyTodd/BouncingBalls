package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Rebuild-on-change continuous scheduler using a conservative swept-AABB binary volume hierarchy.
 *
 * <p>This scheduler shares the same exact wall horizon and trajectory-envelope proof as
 * {@link SweepAndPruneCcdScheduler}. The only intended architectural difference is candidate enumeration: swept
 * boxes are arranged into a median-split BVH, and each leaf queries the hierarchy for overlapping leaves with a
 * larger stable body id. Exact TOI prediction remains the authority for every surviving pair.</p>
 *
 * <p>If the conservative horizon is unavailable, or any swept box cannot be represented by finite bounds, the
 * scheduler falls back to canonical all-pairs CCD for that rebuild. The hierarchy is rebuilt after every
 * trajectory-changing event batch.</p>
 */
public final class SweptBvhCcdScheduler implements EventScheduler {
    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private double now;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        queue.clear();
        stats.bvhRebuilds++;

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
        stats.bvhCanonicalPairs += canonicalPairs;

        if (canonicalPairs != 0) {
            double horizon = SweptAabb.conservativeHorizon(earliestWall, policy);
            if (Double.isFinite(horizon) && addBvhCandidates(balls, horizon, policy, stats)) {
                // Candidate enumeration completed conservatively through the hierarchy.
            } else {
                stats.bvhAllPairsFallbackRebuilds++;
                stats.bvhExactPairCandidates += canonicalPairs;
                addAllPairs(balls, policy, stats);
            }
        }

        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private boolean addBvhCandidates(
            List<Ball> balls,
            double horizon,
            NumericalPolicy policy,
            SimulationStats stats) {
        List<SweptAabb.Box> boxes = new ArrayList<>(balls.size());
        for (Ball ball : balls) {
            SweptAabb.Box box = SweptAabb.forBall(ball, horizon, policy);
            if (!box.finite()) return false;
            boxes.add(box);
        }

        Node root = build(boxes, 0, stats);
        for (SweptAabb.Box query : boxes) {
            collect(query, root, policy, stats);
        }
        return true;
    }

    private Node build(List<SweptAabb.Box> boxes, int depth, SimulationStats stats) {
        stats.bvhNodesBuilt++;
        stats.bvhMaxDepth = Math.max(stats.bvhMaxDepth, depth);
        if (boxes.size() == 1) return new Node(boxes.get(0), null, null, boxes.get(0));

        double minCx = Double.POSITIVE_INFINITY;
        double maxCx = Double.NEGATIVE_INFINITY;
        double minCy = Double.POSITIVE_INFINITY;
        double maxCy = Double.NEGATIVE_INFINITY;
        for (SweptAabb.Box box : boxes) {
            minCx = Math.min(minCx, box.centroidX());
            maxCx = Math.max(maxCx, box.centroidX());
            minCy = Math.min(minCy, box.centroidY());
            maxCy = Math.max(maxCy, box.centroidY());
        }

        boolean splitX = maxCx - minCx >= maxCy - minCy;
        boxes.sort(splitX
                ? Comparator.comparingDouble(SweptAabb.Box::centroidX)
                        .thenComparingInt(box -> box.ball().id)
                : Comparator.comparingDouble(SweptAabb.Box::centroidY)
                        .thenComparingInt(box -> box.ball().id));

        int middle = boxes.size() / 2;
        Node left = build(new ArrayList<>(boxes.subList(0, middle)), depth + 1, stats);
        Node right = build(new ArrayList<>(boxes.subList(middle, boxes.size())), depth + 1, stats);
        return new Node(SweptAabb.Box.union(left.bounds, right.bounds), left, right, null);
    }

    private void collect(
            SweptAabb.Box query,
            Node node,
            NumericalPolicy policy,
            SimulationStats stats) {
        stats.bvhNodeVisits++;
        if (!query.overlaps(node.bounds)) return;

        if (node.leaf != null) {
            if (node.leaf.ball().id <= query.ball().id) return;
            stats.bvhExactPairCandidates++;
            EventPredictions.addPair(query.ball(), node.leaf.ball(), policy, stats, queue, now);
            return;
        }

        collect(query, node.left, policy, stats);
        collect(query, node.right, policy, stats);
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

    private static final class Node {
        final SweptAabb.Box bounds;
        final Node left;
        final Node right;
        final SweptAabb.Box leaf;

        Node(SweptAabb.Box bounds, Node left, Node right, SweptAabb.Box leaf) {
            this.bounds = bounds;
            this.left = left;
            this.right = right;
            this.leaf = leaf;
        }
    }
}
