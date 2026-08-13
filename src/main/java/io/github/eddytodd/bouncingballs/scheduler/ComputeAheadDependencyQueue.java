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
 * <p>CADQ evaluates candidate TOIs first and materializes {@link CollisionEvent} objects only for predictions that
 * survive the owner's earliest-time selection (including ties). The global scheduler still materializes every finite
 * prediction it stores. This avoids allocating throwaway event objects without changing the TOI mathematics.</p>
 *
 * <p>A small primitive id-to-slot table avoids boxing on hot queue validation and trajectory invalidation. The heap
 * continues to store {@link CollisionEvent} directly; parallel target-slot arrays retain the metadata needed to
 * maintain reverse dependencies without allocating wrapper objects per event.</p>
 *
 * <p>Coarse phase timing is opt-in through {@code -Dbouncingballs.cadqProfile=true}. Normal benchmark runs therefore
 * avoid the repeated {@link System#nanoTime()} calls used by the diagnostic profiler.</p>
 */
public final class ComputeAheadDependencyQueue implements EventScheduler {
    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private final SelectionBuilder selection = new SelectionBuilder();
    private Ball[] bodies = new Ball[0];
    private IntSlotMap slotById = new IntSlotMap(0);
    private CollisionEvent[][] outbound = new CollisionEvent[0][];
    private int[][] outboundTargets = new int[0][];
    private BitSet[] inbound = new BitSet[0];
    private double now;
    private boolean profile;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        profile = Boolean.getBoolean("bouncingballs.cadqProfile");
        bodies = balls.toArray(Ball[]::new);
        Arrays.sort(bodies, Comparator.comparingInt(ball -> ball.id));

        slotById = new IntSlotMap(bodies.length);
        for (int slot = 0; slot < bodies.length; slot++) slotById.put(bodies[slot].id, slot);

        queue.clear();
        outbound = new CollisionEvent[bodies.length][];
        outboundTargets = new int[bodies.length][];
        inbound = new BitSet[bodies.length];
        now = 0;

        for (int ownerSlot = 0; ownerSlot < bodies.length; ownerSlot++) {
            recompute(ownerSlot, bounds, policy, stats);
        }
        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private void recompute(
            int ownerSlot,
            Bounds bounds,
            NumericalPolicy policy,
            SimulationStats stats) {
        removeOutbound(ownerSlot, stats);
        selection.reset();
        Ball owner = bodies[ownerSlot];

        for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
            double relativeTime = EventPredictions.pairTime(owner, bodies[otherSlot], policy, stats);
            considerPrediction(relativeTime, otherSlot, CollisionEvent.NONE, policy);
        }
        for (int wall = 0; wall < 4; wall++) {
            double relativeTime = EventPredictions.wallTime(owner, bounds, wall, policy, stats);
            considerPrediction(relativeTime, -1, wall, policy);
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
            double relativeTime = EventPredictions.pairTime(owner, bodies[otherSlot], policy, stats);
            stats.cadqLocalPairRefreshes++;
            if (!Double.isFinite(relativeTime)) continue;

            double candidateTime = now + relativeTime;
            if (selection.size == 0 || earlier(candidateTime, selection.times[0], policy)) {
                selection.replacePrediction(candidateTime, otherSlot, CollisionEvent.NONE);
                modified = true;
            } else if (policy.sameTime(candidateTime, selection.times[0])) {
                selection.addPrediction(candidateTime, otherSlot, CollisionEvent.NONE);
                modified = true;
            }
        }

        if (modified) {
            stats.cadqLocalOwnersModified++;
            removeOutbound(ownerSlot, stats);
            install(ownerSlot, stats);
        }
    }

    private void considerPrediction(
            double relativeTime,
            int targetSlot,
            int wall,
            NumericalPolicy policy) {
        if (!Double.isFinite(relativeTime)) return;
        double candidateTime = now + relativeTime;
        if (selection.size == 0 || earlier(candidateTime, selection.times[0], policy)) {
            selection.replacePrediction(candidateTime, targetSlot, wall);
        } else if (policy.sameTime(candidateTime, selection.times[0])) {
            selection.addPrediction(candidateTime, targetSlot, wall);
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

        CollisionEvent[] events = new CollisionEvent[selection.size];
        int[] targets = Arrays.copyOf(selection.targets, selection.size);
        Ball owner = bodies[ownerSlot];
        for (int i = 0; i < selection.size; i++) {
            CollisionEvent event = selection.events[i];
            if (event == null) {
                int targetSlot = selection.targets[i];
                event = targetSlot >= 0
                        ? EventPredictions.materializePair(owner, bodies[targetSlot], selection.times[i], stats)
                        : EventPredictions.materializeWall(owner, selection.walls[i], selection.times[i], stats);
            }
            events[i] = event;
        }

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

        // Snapshot the full-reselection set before recompute mutates reverse dependencies.
        long fullStart = profile ? System.nanoTime() : 0;
        for (int ownerSlot = full.nextSetBit(0);
             ownerSlot >= 0;
             ownerSlot = full.nextSetBit(ownerSlot + 1)) {
            recompute(ownerSlot, bounds, policy, stats);
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

    /** Reused tie-set builder for retained events and not-yet-materialized selected predictions. */
    private static final class SelectionBuilder {
        private CollisionEvent[] events = new CollisionEvent[4];
        private int[] targets = new int[4];
        private double[] times = new double[4];
        private int[] walls = new int[4];
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
            for (int i = 0; i < sourceEvents.length; i++) {
                CollisionEvent event = sourceEvents[i];
                events[i] = event;
                targets[i] = sourceTargets[i];
                times[i] = event.time();
                walls[i] = event.wall();
            }
            size = sourceEvents.length;
        }

        void replacePrediction(double time, int targetSlot, int wall) {
            ensure(1);
            events[0] = null;
            targets[0] = targetSlot;
            times[0] = time;
            walls[0] = wall;
            size = 1;
        }

        void addPrediction(double time, int targetSlot, int wall) {
            ensure(size + 1);
            events[size] = null;
            targets[size] = targetSlot;
            times[size] = time;
            walls[size] = wall;
            size++;
        }

        private void ensure(int required) {
            if (required <= events.length) return;
            int capacity = Math.max(required, events.length * 2);
            events = Arrays.copyOf(events, capacity);
            targets = Arrays.copyOf(targets, capacity);
            times = Arrays.copyOf(times, capacity);
            walls = Arrays.copyOf(walls, capacity);
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
