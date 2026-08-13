package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Compute-ahead dependency queue (CADQ).
 *
 * <p>Bodies receive dense simulation-local slots once during rebuild, sorted by stable unique body id. Canonical
 * ball-ball ownership is therefore {@code ownerSlot < otherSlot}. Retained-event and reverse-dependency bookkeeping
 * uses arrays and {@link BitSet}s instead of object-keyed hash maps/sets.</p>
 *
 * <p>A small primitive id-to-slot table avoids boxing on hot queue validation and trajectory invalidation. The heap
 * continues to store {@link CollisionEvent} directly; parallel target-slot arrays retain the metadata needed to
 * maintain reverse dependencies without allocating wrapper objects per event.</p>
 *
 * <p>Full owner selections, including initial construction, seed their current-best horizon with the four cheap wall
 * TOIs. A conservative swept spatial grid can first omit canonical pairs whose current centers cannot enter the same
 * contact envelope during that horizon. Remaining pairs then pass through the existing temporal reachability bound
 * before exact TOI. Local refreshes retain the temporal bound because their changed-pair sets are already small.</p>
 *
 * <p>Set {@code -Dbouncingballs.cadqSpatialPruning=false} or
 * {@code -Dbouncingballs.cadqTemporalPruning=false} to preserve earlier research paths for matched A/B runs.</p>
 *
 * <p>Coarse phase timing is opt-in through {@code -Dbouncingballs.cadqProfile=true}. Normal benchmark runs therefore
 * avoid the repeated {@link System#nanoTime()} calls used by the diagnostic profiler.</p>
 */
public final class ComputeAheadDependencyQueue implements EventScheduler {
    private static final String TEMPORAL_PRUNING_PROPERTY = "bouncingballs.cadqTemporalPruning";
    private static final String SPATIAL_PRUNING_PROPERTY = "bouncingballs.cadqSpatialPruning";
    private static final double TIME_SLACK_MULTIPLIER = 4.0;

    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private final SelectionBuilder selection = new SelectionBuilder();
    private final SweptSpatialGrid spatialGrid = new SweptSpatialGrid();
    private Ball[] bodies = new Ball[0];
    private IntSlotMap slotById = new IntSlotMap(0);
    private CollisionEvent[][] outbound = new CollisionEvent[0][];
    private int[][] outboundTargets = new int[0][];
    private BitSet[] inbound = new BitSet[0];
    private int[] spatialCandidates = new int[0];
    private double now;
    private boolean profile;
    private boolean temporalPruning;
    private boolean spatialPruning;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        profile = Boolean.getBoolean("bouncingballs.cadqProfile");
        temporalPruning = Boolean.parseBoolean(System.getProperty(TEMPORAL_PRUNING_PROPERTY, "true"));
        spatialPruning = Boolean.parseBoolean(System.getProperty(SPATIAL_PRUNING_PROPERTY, "true"));
        bodies = balls.toArray(Ball[]::new);
        Arrays.sort(bodies, Comparator.comparingInt(ball -> ball.id));

        slotById = new IntSlotMap(bodies.length);
        for (int slot = 0; slot < bodies.length; slot++) slotById.put(bodies[slot].id, slot);

        queue.clear();
        outbound = new CollisionEvent[bodies.length][];
        outboundTargets = new int[bodies.length][];
        inbound = new BitSet[bodies.length];
        spatialCandidates = new int[bodies.length];
        now = 0;

        if (spatialPruning) spatialGrid.rebuild(bodies, bounds, stats);

        for (int ownerSlot = 0; ownerSlot < bodies.length; ownerSlot++) {
            recompute(ownerSlot, bounds, policy, stats, true);
        }
        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private void recompute(
            int ownerSlot,
            Bounds bounds,
            NumericalPolicy policy,
            SimulationStats stats,
            boolean allowHorizonPruning) {
        removeOutbound(ownerSlot, stats);
        selection.reset();
        Ball owner = bodies[ownerSlot];
        boolean horizonBased = allowHorizonPruning && (temporalPruning || spatialPruning);

        if (horizonBased) {
            // Four wall queries are cheap and usually establish a finite upper bound before pair selection.
            for (int wall = 0; wall < 4; wall++) {
                consider(EventPredictions.wall(owner, bounds, wall, policy, stats, now), -1, policy);
            }

            if (spatialPruning) {
                double horizon = currentPruningHorizon(policy);
                int count = spatialGrid.queryCanonicalCandidates(
                        ownerSlot, horizon, policy, stats, spatialCandidates);
                for (int index = 0; index < count; index++) {
                    int otherSlot = spatialCandidates[index];
                    if (temporalPruning && shouldPrunePair(owner, bodies[otherSlot], policy, stats)) continue;
                    consider(
                            EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now),
                            otherSlot,
                            policy);
                }
            } else {
                for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
                    if (temporalPruning && shouldPrunePair(owner, bodies[otherSlot], policy, stats)) continue;
                    consider(
                            EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now),
                            otherSlot,
                            policy);
                }
            }
        } else {
            for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
                consider(
                        EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now),
                        otherSlot,
                        policy);
            }
            for (int wall = 0; wall < 4; wall++) {
                consider(EventPredictions.wall(owner, bounds, wall, policy, stats, now), -1, policy);
            }
        }

        install(ownerSlot, stats);
        stats.predictionRecomputations++;
        stats.cadqFullReselections++;
    }

    private void refreshAgainstChanged(
            int ownerSlot,
            BitSet changedSlots,
            NumericalPolicy policy,
            SimulationStats stats) {
        stats.cadqLocalOwnersVisited++;
        selection.copyFrom(outbound[ownerSlot], outboundTargets[ownerSlot]);
        boolean modified = false;
        Ball owner = bodies[ownerSlot];

        for (int otherSlot = changedSlots.nextSetBit(ownerSlot + 1);
             otherSlot >= 0;
             otherSlot = changedSlots.nextSetBit(otherSlot + 1)) {
            if (temporalPruning && shouldPrunePair(owner, bodies[otherSlot], policy, stats)) continue;

            CollisionEvent candidate = EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now);
            stats.cadqLocalPairRefreshes++;
            if (candidate == null) continue;

            if (selection.size == 0 || earlier(candidate.time(), selection.events[0].time(), policy)) {
                selection.replace(candidate, otherSlot);
                modified = true;
            } else if (policy.sameTime(candidate.time(), selection.events[0].time())) {
                selection.add(candidate, otherSlot);
                modified = true;
            }
        }

        if (modified) {
            stats.cadqLocalOwnersModified++;
            removeOutbound(ownerSlot, stats);
            install(ownerSlot, stats);
        }
    }

    private boolean shouldPrunePair(
            Ball owner,
            Ball other,
            NumericalPolicy policy,
            SimulationStats stats) {
        double horizon = currentPruningHorizon(policy);
        if (!Double.isFinite(horizon)) return false;

        stats.cadqTemporalBoundChecks++;
        if (TemporalReachability.couldContactWithin(owner, other, horizon, policy)) return false;

        stats.cadqTemporalPrunes++;
        return true;
    }

    private double currentPruningHorizon(NumericalPolicy policy) {
        if (selection.size == 0) return Double.NaN;

        double bestTime = selection.events[0].time();
        double relativeHorizon = bestTime - now;
        if (!Double.isFinite(relativeHorizon)) return Double.NaN;

        double timeScale = Math.max(Math.abs(bestTime), Math.abs(now));
        double tieSlack = TIME_SLACK_MULTIPLIER * policy.tolerance(timeScale);
        double horizon = Math.max(0.0, relativeHorizon) + tieSlack;
        return Double.isFinite(horizon) ? horizon : Double.NaN;
    }

    private void consider(CollisionEvent candidate, int targetSlot, NumericalPolicy policy) {
        if (candidate == null) return;
        if (selection.size == 0 || earlier(candidate.time(), selection.events[0].time(), policy)) {
            selection.replace(candidate, targetSlot);
        } else if (policy.sameTime(candidate.time(), selection.events[0].time())) {
            selection.add(candidate, targetSlot);
        }
    }

    private static boolean earlier(double a, double b, NumericalPolicy policy) {
        return a < b && !policy.sameTime(a, b);
    }

    private void removeOutbound(int ownerSlot, SimulationStats stats) {
        int[] targets = outboundTargets[ownerSlot];
        if (outbound[ownerSlot] != null) stats.cadqRetainedRemovals++;
        outbound[ownerSlot] = null;
        outboundTargets[ownerSlot] = null;
        if (targets == null) return;

        for (int targetSlot : targets) {
            if (targetSlot < 0) continue;
            BitSet dependents = inbound[targetSlot];
            if (dependents != null) {
                dependents.clear(ownerSlot);
                stats.cadqInboundClears++;
            }
        }
    }

    private void install(int ownerSlot, SimulationStats stats) {
        if (selection.size == 0) return;

        CollisionEvent[] events = Arrays.copyOf(selection.events, selection.size);
        int[] targets = Arrays.copyOf(selection.targets, selection.size);
        outbound[ownerSlot] = events;
        outboundTargets[ownerSlot] = targets;
        stats.cadqRetainedInstalls++;

        for (int i = 0; i < events.length; i++) {
            queue.add(events[i]);
            stats.queuePushes++;

            int targetSlot = targets[i];
            if (targetSlot >= 0) {
                BitSet dependents = inbound[targetSlot];
                if (dependents == null) inbound[targetSlot] = dependents = new BitSet(bodies.length);
                dependents.set(ownerSlot);
                stats.cadqInboundSets++;
            }
        }
    }

    @Override
    public List<CollisionEvent> nextBatch(NumericalPolicy policy, SimulationStats stats) {
        long started = profile ? System.nanoTime() : 0;
        try {
            CollisionEvent first = takeValid(stats);
            if (first == null) return List.of();

            List<CollisionEvent> batch = new ArrayList<>();
            batch.add(first);
            while (true) {
                CollisionEvent next = peekValid(stats);
                if (next == null || !policy.sameTime(first.time(), next.time())) break;
                queue.poll();
                stats.queuePops++;
                stats.validEvents++;
                batch.add(next);
            }
            return batch;
        } finally {
            if (profile) stats.cadqQueueNanos += System.nanoTime() - started;
        }
    }

    private CollisionEvent takeValid(SimulationStats stats) {
        while (!queue.isEmpty()) {
            CollisionEvent event = queue.poll();
            stats.queuePops++;
            if (isCurrent(event, stats)) {
                stats.validEvents++;
                return event;
            }
            stats.staleEvents++;
        }
        return null;
    }

    private CollisionEvent peekValid(SimulationStats stats) {
        while (!queue.isEmpty() && !isCurrent(queue.peek(), stats)) {
            queue.poll();
            stats.queuePops++;
            stats.staleEvents++;
        }
        return queue.peek();
    }

    private boolean isCurrent(CollisionEvent event, SimulationStats stats) {
        stats.cadqQueueValidationChecks++;
        if (!EventPredictions.valid(event)) return false;
        int ownerSlot = slotById.get(event.a().id);
        if (ownerSlot < 0 || bodies[ownerSlot] != event.a()) return false;

        CollisionEvent[] retained = outbound[ownerSlot];
        if (retained == null) return false;
        for (CollisionEvent current : retained) {
            if (current == event) return true;
        }
        return false;
    }

    @Override
    public void trajectoriesChanged(
            Set<Ball> changed,
            List<Ball> balls,
            Bounds bounds,
            NumericalPolicy policy,
            SimulationStats stats) {
        long dependencyStart = profile ? System.nanoTime() : 0;
        BitSet changedSlots = new BitSet(bodies.length);
        for (Ball body : changed) {
            int slot = slotById.get(body.id);
            if (slot < 0 || bodies[slot] != body) {
                throw new IllegalStateException("trajectory change contains an unknown body");
            }
            changedSlots.set(slot);
        }

        BitSet full = (BitSet) changedSlots.clone();
        for (int changedSlot = changedSlots.nextSetBit(0);
             changedSlot >= 0;
             changedSlot = changedSlots.nextSetBit(changedSlot + 1)) {
            BitSet dependents = inbound[changedSlot];
            if (dependents != null) full.or(dependents);
        }
        int fullOwners = full.cardinality();
        stats.dependencyInvalidations += fullOwners;
        stats.cadqDependencyBatches++;
        stats.cadqFullOwnersVisited += fullOwners;
        if (profile) stats.cadqDependencyDiscoveryNanos += System.nanoTime() - dependencyStart;

        long spatialStart = profile ? System.nanoTime() : 0;
        if (spatialPruning) spatialGrid.rebuild(bodies, bounds, stats);
        if (profile) stats.cadqSpatialRebuildNanos += System.nanoTime() - spatialStart;

        // Snapshot the full-reselection set before recompute mutates reverse dependencies.
        long fullStart = profile ? System.nanoTime() : 0;
        for (int ownerSlot = full.nextSetBit(0);
             ownerSlot >= 0;
             ownerSlot = full.nextSetBit(ownerSlot + 1)) {
            recompute(ownerSlot, bounds, policy, stats, true);
        }
        if (profile) stats.cadqFullReselectionNanos += System.nanoTime() - fullStart;

        long localStart = profile ? System.nanoTime() : 0;
        for (int ownerSlot = 0; ownerSlot < bodies.length; ownerSlot++) {
            if (!full.get(ownerSlot)) refreshAgainstChanged(ownerSlot, changedSlots, policy, stats);
        }
        if (profile) stats.cadqLocalRefreshNanos += System.nanoTime() - localStart;

        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    @Override
    public void timeAdvanced(double dt) {
        now += dt;
    }

    /** Reused tie-set builder: candidate evaluation does not allocate per-candidate metadata wrappers. */
    private static final class SelectionBuilder {
        private CollisionEvent[] events = new CollisionEvent[4];
        private int[] targets = new int[4];
        private int size;

        void reset() {
            size = 0;
        }

        void copyFrom(CollisionEvent[] sourceEvents, int[] sourceTargets) {
            if (sourceEvents == null) {
                size = 0;
                return;
            }
            ensure(sourceEvents.length);
            System.arraycopy(sourceEvents, 0, events, 0, sourceEvents.length);
            System.arraycopy(sourceTargets, 0, targets, 0, sourceTargets.length);
            size = sourceEvents.length;
        }

        void replace(CollisionEvent event, int targetSlot) {
            ensure(1);
            events[0] = event;
            targets[0] = targetSlot;
            size = 1;
        }

        void add(CollisionEvent event, int targetSlot) {
            ensure(size + 1);
            events[size] = event;
            targets[size] = targetSlot;
            size++;
        }

        private void ensure(int required) {
            if (required <= events.length) return;
            int capacity = Math.max(required, events.length * 2);
            events = Arrays.copyOf(events, capacity);
            targets = Arrays.copyOf(targets, capacity);
        }
    }

    /** Primitive open-addressed id-to-slot map built once per scheduler rebuild. */
    private static final class IntSlotMap {
        private final int[] keys;
        private final int[] values;
        private final boolean[] used;
        private final int mask;

        IntSlotMap(int expectedSize) {
            int capacity = 1;
            int target = Math.max(2, expectedSize * 2);
            while (capacity < target) capacity <<= 1;
            keys = new int[capacity];
            values = new int[capacity];
            used = new boolean[capacity];
            mask = capacity - 1;
        }

        void put(int key, int value) {
            int index = mix(key) & mask;
            while (used[index]) {
                if (keys[index] == key) {
                    values[index] = value;
                    return;
                }
                index = (index + 1) & mask;
            }
            used[index] = true;
            keys[index] = key;
            values[index] = value;
        }

        int get(int key) {
            int index = mix(key) & mask;
            while (used[index]) {
                if (keys[index] == key) return values[index];
                index = (index + 1) & mask;
            }
            return -1;
        }

        private static int mix(int value) {
            int x = value;
            x ^= x >>> 16;
            x *= 0x7feb352d;
            x ^= x >>> 15;
            x *= 0x846ca68b;
            x ^= x >>> 16;
            return x;
        }
    }
}
