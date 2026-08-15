package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Rebuild-on-change CCD scheduler using a parameter-free uniform grid over conservative swept AABBs.
 *
 * <p>The trajectory envelope is exactly the same {@link SweptAabb} proof used by sweep-and-prune and the swept BVH.
 * Grid resolution has no workload-tuned constant: each axis is capped by both the global density scale
 * {@code sqrt(worldArea / bodyCount)} and the mean swept-envelope extent on that axis. This prevents a locally dense
 * cluster of small envelopes from inheriting cells sized for empty world area, while large swept envelopes naturally
 * keep coarser cells. Every swept box is inserted into every grid cell it touches.</p>
 *
 * <p>Cell memberships are stored as primitive packed longs and sorted by cell id; unordered body pairs encountered
 * in multiple cells are deduplicated by a reusable primitive long set. A shared-cell pair reaches exact TOI only
 * after the original swept boxes overlap on both axes, so finite-horizon exact candidate semantics match SAP/BVH.</p>
 *
 * <p>If no finite conservative horizon exists, grid dimensions are not representable, or the membership array would
 * exceed Java array indexing, the scheduler fails open to canonical all-pairs CCD for that rebuild.</p>
 */
public final class SweptUniformGridCcdScheduler implements EventScheduler {
    private static final long MAX_PACKED_CELL_ID = 0xffff_ffffL;

    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private final LongPairSet seenPairs = new LongPairSet();
    private SweptAabb.Box[] boxes = new SweptAabb.Box[0];
    private long[] memberships = new long[0];
    private double now;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        queue.clear();
        stats.gridRebuilds++;

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
        stats.gridCanonicalPairs += canonicalPairs;

        if (canonicalPairs != 0) {
            double horizon = SweptAabb.conservativeHorizon(earliestWall, policy);
            if (!Double.isFinite(horizon) || !addGridCandidates(balls, bounds, horizon, policy, stats)) {
                stats.gridAllPairsFallbackRebuilds++;
                stats.gridExactPairCandidates += canonicalPairs;
                addAllPairs(balls, policy, stats);
            }
        }

        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private boolean addGridCandidates(
            List<Ball> balls,
            Bounds bounds,
            double horizon,
            NumericalPolicy policy,
            SimulationStats stats) {
        int bodyCount = balls.size();
        double width = bounds.maxX() - bounds.minX();
        double height = bounds.maxY() - bounds.minY();
        double area = width * height;
        if (!(width > 0.0) || !(height > 0.0) || !Double.isFinite(area) || !(area > 0.0)) return false;

        ensureBoxCapacity(bodyCount);
        double summedWidth = 0.0;
        double summedHeight = 0.0;
        for (int i = 0; i < bodyCount; i++) {
            SweptAabb.Box box = SweptAabb.forBall(balls.get(i), horizon, policy);
            if (!box.finite()) return false;
            boxes[i] = box;
            summedWidth += box.maxX() - box.minX();
            summedHeight += box.maxY() - box.minY();
        }
        if (!Double.isFinite(summedWidth) || !Double.isFinite(summedHeight)) return false;

        double densityScale = Math.sqrt(area / bodyCount);
        double meanWidth = summedWidth / bodyCount;
        double meanHeight = summedHeight / bodyCount;
        double cellWidth = Math.min(densityScale, meanWidth);
        double cellHeight = Math.min(densityScale, meanHeight);
        if (!(cellWidth > 0.0) || !(cellHeight > 0.0)
                || !Double.isFinite(cellWidth) || !Double.isFinite(cellHeight)) {
            return false;
        }

        double cellsXDouble = Math.ceil(width / cellWidth);
        double cellsYDouble = Math.ceil(height / cellHeight);
        if (!(cellsXDouble >= 1.0) || !(cellsYDouble >= 1.0)
                || cellsXDouble > Integer.MAX_VALUE || cellsYDouble > Integer.MAX_VALUE) {
            return false;
        }
        int cellsX = Math.max(1, (int) cellsXDouble);
        int cellsY = Math.max(1, (int) cellsYDouble);
        long totalCells = (long) cellsX * cellsY;
        if (totalCells <= 0 || totalCells > MAX_PACKED_CELL_ID) return false;

        long membershipCount = 0;
        for (int i = 0; i < bodyCount; i++) {
            CellRange range = range(boxes[i], bounds, cellWidth, cellHeight, cellsX, cellsY);
            long bodyMemberships = (long) (range.maxX - range.minX + 1)
                    * (range.maxY - range.minY + 1);
            membershipCount += bodyMemberships;
            if (membershipCount > Integer.MAX_VALUE) return false;
        }

        int membershipSize = (int) membershipCount;
        ensureMembershipCapacity(membershipSize);
        int write = 0;
        for (int bodyIndex = 0; bodyIndex < bodyCount; bodyIndex++) {
            CellRange range = range(boxes[bodyIndex], bounds, cellWidth, cellHeight, cellsX, cellsY);
            for (int y = range.minY; y <= range.maxY; y++) {
                long row = (long) y * cellsX;
                for (int x = range.minX; x <= range.maxX; x++) {
                    long cellId = row + x;
                    memberships[write++] = (cellId << 32) | Integer.toUnsignedLong(bodyIndex);
                }
            }
        }
        if (write != membershipSize) throw new IllegalStateException("grid membership count changed during rebuild");

        stats.gridCellMemberships += membershipSize;
        Arrays.sort(memberships, 0, membershipSize);
        seenPairs.clear();

        int start = 0;
        while (start < membershipSize) {
            long cellId = memberships[start] >>> 32;
            int end = start + 1;
            while (end < membershipSize && (memberships[end] >>> 32) == cellId) end++;

            int bucketSize = end - start;
            stats.gridOccupiedCells++;
            stats.gridMaxBucketOccupancy = Math.max(stats.gridMaxBucketOccupancy, bucketSize);

            for (int a = start; a < end; a++) {
                int first = (int) memberships[a];
                for (int b = a + 1; b < end; b++) {
                    int second = (int) memberships[b];
                    stats.gridBucketPairAttempts++;

                    int low = Math.min(first, second);
                    int high = Math.max(first, second);
                    long pairKey = ((long) low << 32) | Integer.toUnsignedLong(high);
                    if (!seenPairs.add(pairKey)) {
                        stats.gridDuplicatePairAttempts++;
                        continue;
                    }

                    stats.gridUniqueCellPairs++;
                    if (!boxes[low].overlaps(boxes[high])) {
                        stats.gridAabbRejects++;
                        continue;
                    }

                    stats.gridExactPairCandidates++;
                    EventPredictions.addPair(
                            boxes[low].ball(),
                            boxes[high].ball(),
                            policy,
                            stats,
                            queue,
                            now);
                }
            }
            start = end;
        }
        return true;
    }

    private static CellRange range(
            SweptAabb.Box box,
            Bounds bounds,
            double cellWidth,
            double cellHeight,
            int cellsX,
            int cellsY) {
        int minX = clampCell((box.minX() - bounds.minX()) / cellWidth, cellsX);
        int maxX = clampCell((box.maxX() - bounds.minX()) / cellWidth, cellsX);
        int minY = clampCell((box.minY() - bounds.minY()) / cellHeight, cellsY);
        int maxY = clampCell((box.maxY() - bounds.minY()) / cellHeight, cellsY);
        return new CellRange(
                Math.min(minX, maxX),
                Math.max(minX, maxX),
                Math.min(minY, maxY),
                Math.max(minY, maxY));
    }

    private static int clampCell(double coordinate, int cells) {
        if (Double.isNaN(coordinate)) return 0;
        if (coordinate <= 0.0) return 0;
        if (coordinate >= cells) return cells - 1;
        int cell = (int) Math.floor(coordinate);
        return Math.max(0, Math.min(cells - 1, cell));
    }

    private void ensureBoxCapacity(int needed) {
        if (boxes.length < needed) boxes = new SweptAabb.Box[needed];
    }

    private void ensureMembershipCapacity(int needed) {
        if (memberships.length >= needed) return;
        long doubled = Math.max(16L, (long) memberships.length * 2L);
        int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(needed, doubled));
        memberships = new long[capacity];
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

    private record CellRange(int minX, int maxX, int minY, int maxY) {}

    /** Reusable primitive set specialized for nonzero packed unordered body-index pairs. */
    private static final class LongPairSet {
        private long[] keys = new long[16];
        private int[] stamps = new int[16];
        private int epoch = 1;
        private int size;

        void clear() {
            size = 0;
            if (epoch == Integer.MAX_VALUE) {
                Arrays.fill(stamps, 0);
                epoch = 1;
            } else {
                epoch++;
            }
        }

        boolean add(long key) {
            if (key == 0) throw new IllegalArgumentException("zero is not a valid distinct-body pair key");
            if ((size + 1L) * 10L >= keys.length * 7L) grow();
            return insert(key);
        }

        private boolean insert(long key) {
            int mask = keys.length - 1;
            int slot = mix(key) & mask;
            while (stamps[slot] == epoch) {
                if (keys[slot] == key) return false;
                slot = (slot + 1) & mask;
            }
            stamps[slot] = epoch;
            keys[slot] = key;
            size++;
            return true;
        }

        private void grow() {
            long[] oldKeys = keys;
            int[] oldStamps = stamps;
            int oldEpoch = epoch;
            keys = new long[oldKeys.length << 1];
            stamps = new int[keys.length];
            epoch = 1;
            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldStamps[i] == oldEpoch) insert(oldKeys[i]);
            }
            if (size != oldSize) throw new IllegalStateException("pair-set rehash lost entries");
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}
