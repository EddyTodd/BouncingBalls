package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Rebuild-on-change continuous scheduler using a conservative swept-AABB binary volume hierarchy.
 *
 * <p>This scheduler shares the same exact wall horizon and trajectory-envelope proof as
 * {@link SweepAndPruneCcdScheduler}. The only intended architectural difference is candidate enumeration: swept
 * boxes are arranged into a spatial BVH, and each leaf queries the hierarchy for overlapping leaves with a larger
 * stable body id. Exact TOI prediction remains the authority for every surviving pair.</p>
 *
 * <p>The hierarchy uses reusable flat node arrays rather than allocating node objects on every event batch. Rebuilds
 * partition leaves around the midpoint of the widest centroid axis in linear time; severely imbalanced partitions
 * fall back to a deterministic median sort for bounded depth. If the conservative horizon is unavailable, or any
 * swept box cannot be represented by finite bounds, the scheduler falls back to canonical all-pairs CCD.</p>
 */
public final class SweptBvhCcdScheduler implements EventScheduler {
    private static final Comparator<SweptAabb.Box> X_ORDER = Comparator
            .comparingDouble(SweptAabb.Box::centroidX)
            .thenComparingInt(box -> box.ball().id);
    private static final Comparator<SweptAabb.Box> Y_ORDER = Comparator
            .comparingDouble(SweptAabb.Box::centroidY)
            .thenComparingInt(box -> box.ball().id);

    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private SweptAabb.Box[] leaves = new SweptAabb.Box[0];
    private double[] nodeMinX = new double[0];
    private double[] nodeMaxX = new double[0];
    private double[] nodeMinY = new double[0];
    private double[] nodeMaxY = new double[0];
    private int[] nodeLeft = new int[0];
    private int[] nodeRight = new int[0];
    private int[] nodeLeaf = new int[0];
    private int nextNode;
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
        ensureCapacity(balls.size());
        for (int i = 0; i < balls.size(); i++) {
            SweptAabb.Box box = SweptAabb.forBall(balls.get(i), horizon, policy);
            if (!box.finite()) return false;
            leaves[i] = box;
        }

        nextNode = 0;
        int root = build(0, balls.size(), 0, stats);
        for (int i = 0; i < balls.size(); i++) collect(leaves[i], root, policy, stats);
        return true;
    }

    private void ensureCapacity(int bodies) {
        if (leaves.length < bodies) leaves = new SweptAabb.Box[bodies];
        int nodes = Math.max(1, 2 * bodies - 1);
        if (nodeMinX.length >= nodes) return;
        nodeMinX = new double[nodes];
        nodeMaxX = new double[nodes];
        nodeMinY = new double[nodes];
        nodeMaxY = new double[nodes];
        nodeLeft = new int[nodes];
        nodeRight = new int[nodes];
        nodeLeaf = new int[nodes];
    }

    private int build(int from, int to, int depth, SimulationStats stats) {
        int node = nextNode++;
        stats.bvhNodesBuilt++;
        stats.bvhMaxDepth = Math.max(stats.bvhMaxDepth, depth);

        if (to - from == 1) {
            SweptAabb.Box box = leaves[from];
            setBounds(node, box.minX(), box.maxX(), box.minY(), box.maxY());
            nodeLeft[node] = -1;
            nodeRight[node] = -1;
            nodeLeaf[node] = from;
            return node;
        }

        double minCx = Double.POSITIVE_INFINITY;
        double maxCx = Double.NEGATIVE_INFINITY;
        double minCy = Double.POSITIVE_INFINITY;
        double maxCy = Double.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            SweptAabb.Box box = leaves[i];
            minCx = Math.min(minCx, box.centroidX());
            maxCx = Math.max(maxCx, box.centroidX());
            minCy = Math.min(minCy, box.centroidY());
            maxCy = Math.max(maxCy, box.centroidY());
        }

        boolean splitX = maxCx - minCx >= maxCy - minCy;
        double split = splitX ? 0.5 * (minCx + maxCx) : 0.5 * (minCy + maxCy);
        int middle = partition(from, to, splitX, split);
        int size = to - from;
        int minimumSide = Math.max(1, size / 8);
        if (middle - from < minimumSide || to - middle < minimumSide) {
            Arrays.sort(leaves, from, to, splitX ? X_ORDER : Y_ORDER);
            middle = from + size / 2;
        }

        int left = build(from, middle, depth + 1, stats);
        int right = build(middle, to, depth + 1, stats);
        nodeLeft[node] = left;
        nodeRight[node] = right;
        nodeLeaf[node] = -1;
        setBounds(
                node,
                Math.min(nodeMinX[left], nodeMinX[right]),
                Math.max(nodeMaxX[left], nodeMaxX[right]),
                Math.min(nodeMinY[left], nodeMinY[right]),
                Math.max(nodeMaxY[left], nodeMaxY[right]));
        return node;
    }

    private int partition(int from, int to, boolean xAxis, double split) {
        int left = from;
        int right = to - 1;
        while (left <= right) {
            while (left <= right && coordinate(leaves[left], xAxis) < split) left++;
            while (left <= right && coordinate(leaves[right], xAxis) >= split) right--;
            if (left < right) {
                SweptAabb.Box temporary = leaves[left];
                leaves[left] = leaves[right];
                leaves[right] = temporary;
                left++;
                right--;
            }
        }
        return left;
    }

    private static double coordinate(SweptAabb.Box box, boolean xAxis) {
        return xAxis ? box.centroidX() : box.centroidY();
    }

    private void setBounds(int node, double minX, double maxX, double minY, double maxY) {
        nodeMinX[node] = minX;
        nodeMaxX[node] = maxX;
        nodeMinY[node] = minY;
        nodeMaxY[node] = maxY;
    }

    private void collect(
            SweptAabb.Box query,
            int node,
            NumericalPolicy policy,
            SimulationStats stats) {
        stats.bvhNodeVisits++;
        if (query.maxX() < nodeMinX[node]
                || nodeMaxX[node] < query.minX()
                || query.maxY() < nodeMinY[node]
                || nodeMaxY[node] < query.minY()) {
            return;
        }

        int leafIndex = nodeLeaf[node];
        if (leafIndex >= 0) {
            SweptAabb.Box other = leaves[leafIndex];
            if (other.ball().id <= query.ball().id) return;
            stats.bvhExactPairCandidates++;
            EventPredictions.addPair(query.ball(), other.ball(), policy, stats, queue, now);
            return;
        }

        collect(query, nodeLeft[node], policy, stats);
        collect(query, nodeRight[node], policy, stats);
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
