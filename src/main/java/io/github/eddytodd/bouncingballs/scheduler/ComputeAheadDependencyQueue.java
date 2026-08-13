package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Compute-ahead dependency queue (CADQ).
 *
 * <p>Bodies are assigned dense simulation-local slots once during rebuild, sorted by stable unique body id.
 * Canonical ball-ball ownership is therefore simply {@code ownerSlot < otherSlot}. Hot-path retained-event and
 * reverse-dependency bookkeeping uses arrays and {@link BitSet}s rather than hash maps/sets.</p>
 *
 * <p>Each owner retains the complete earliest-time tie set among its canonical pairs and walls. Reverse dependency
 * bitsets identify owners whose retained event set currently references a body. A trajectory change fully
 * recomputes changed owners and retained dependents; all other owners test only canonically owned changed bodies for
 * newly-earlier or equal-time events.</p>
 */
public final class ComputeAheadDependencyQueue implements EventScheduler {
    private record RetainedEvent(CollisionEvent event, int targetSlot) {}

    private record QueueEntry(int ownerSlot, RetainedEvent retained) implements Comparable<QueueEntry> {
        @Override
        public int compareTo(QueueEntry other) {
            int comparison = retained.event().compareTo(other.retained.event());
            return comparison != 0 ? comparison : Integer.compare(ownerSlot, other.ownerSlot);
        }
    }

    private final PriorityQueue<QueueEntry> queue = new PriorityQueue<>();
    private final IdentityHashMap<Ball, Integer> slotByBall = new IdentityHashMap<>();
    private Ball[] bodies = new Ball[0];
    private RetainedEvent[][] outbound = new RetainedEvent[0][];
    private BitSet[] inbound = new BitSet[0];
    private double now;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        bodies = balls.toArray(Ball[]::new);
        Arrays.sort(bodies, Comparator.comparingInt(ball -> ball.id));

        slotByBall.clear();
        for (int slot = 0; slot < bodies.length; slot++) slotByBall.put(bodies[slot], slot);

        queue.clear();
        outbound = new RetainedEvent[bodies.length][];
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
        removeOutbound(ownerSlot);
        Ball owner = bodies[ownerSlot];
        List<RetainedEvent> best = new ArrayList<>();

        for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
            CollisionEvent candidate = EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now);
            consider(best, candidate == null ? null : new RetainedEvent(candidate, otherSlot), policy);
        }
        for (int wall = 0; wall < 4; wall++) {
            CollisionEvent candidate = EventPredictions.wall(owner, bounds, wall, policy, stats, now);
            consider(best, candidate == null ? null : new RetainedEvent(candidate, -1), policy);
        }

        install(ownerSlot, best, stats);
        stats.predictionRecomputations++;
        stats.cadqFullReselections++;
    }

    private void refreshAgainstChanged(
            int ownerSlot,
            BitSet changedSlots,
            NumericalPolicy policy,
            SimulationStats stats) {
        RetainedEvent[] current = outbound[ownerSlot];
        List<RetainedEvent> best = new ArrayList<>(current == null ? 0 : current.length + 1);
        if (current != null) Collections.addAll(best, current);

        boolean modified = false;
        Ball owner = bodies[ownerSlot];
        for (int otherSlot = changedSlots.nextSetBit(ownerSlot + 1);
             otherSlot >= 0;
             otherSlot = changedSlots.nextSetBit(otherSlot + 1)) {
            CollisionEvent candidate = EventPredictions.pair(owner, bodies[otherSlot], policy, stats, now);
            stats.cadqLocalPairRefreshes++;
            if (candidate == null) continue;

            RetainedEvent retained = new RetainedEvent(candidate, otherSlot);
            if (best.isEmpty() || earlier(candidate.time(), best.get(0).event().time(), policy)) {
                best.clear();
                best.add(retained);
                modified = true;
            } else if (policy.sameTime(candidate.time(), best.get(0).event().time())) {
                best.add(retained);
                modified = true;
            }
        }

        if (modified) {
            removeOutbound(ownerSlot);
            install(ownerSlot, best, stats);
        }
    }

    private static void consider(
            List<RetainedEvent> best,
            RetainedEvent candidate,
            NumericalPolicy policy) {
        if (candidate == null) return;
        if (best.isEmpty() || earlier(candidate.event().time(), best.get(0).event().time(), policy)) {
            best.clear();
            best.add(candidate);
        } else if (policy.sameTime(candidate.event().time(), best.get(0).event().time())) {
            best.add(candidate);
        }
    }

    private static boolean earlier(double a, double b, NumericalPolicy policy) {
        return a < b && !policy.sameTime(a, b);
    }

    private void removeOutbound(int ownerSlot) {
        RetainedEvent[] old = outbound[ownerSlot];
        outbound[ownerSlot] = null;
        if (old == null) return;

        for (RetainedEvent retained : old) {
            int targetSlot = retained.targetSlot();
            if (targetSlot < 0) continue;
            BitSet dependents = inbound[targetSlot];
            if (dependents != null) dependents.clear(ownerSlot);
        }
    }

    private void install(int ownerSlot, List<RetainedEvent> events, SimulationStats stats) {
        if (events == null || events.isEmpty()) return;

        RetainedEvent[] retained = events.toArray(RetainedEvent[]::new);
        outbound[ownerSlot] = retained;
        for (RetainedEvent event : retained) {
            queue.add(new QueueEntry(ownerSlot, event));
            stats.queuePushes++;

            int targetSlot = event.targetSlot();
            if (targetSlot >= 0) {
                BitSet dependents = inbound[targetSlot];
                if (dependents == null) inbound[targetSlot] = dependents = new BitSet(bodies.length);
                dependents.set(ownerSlot);
            }
        }
    }

    @Override
    public List<CollisionEvent> nextBatch(NumericalPolicy policy, SimulationStats stats) {
        QueueEntry first = takeValid(stats);
        if (first == null) return List.of();

        List<CollisionEvent> batch = new ArrayList<>();
        batch.add(first.retained().event());
        while (true) {
            QueueEntry next = peekValid(stats);
            if (next == null || !policy.sameTime(first.retained().event().time(), next.retained().event().time())) break;
            queue.poll();
            stats.queuePops++;
            stats.validEvents++;
            batch.add(next.retained().event());
        }
        return batch;
    }

    private QueueEntry takeValid(SimulationStats stats) {
        while (!queue.isEmpty()) {
            QueueEntry entry = queue.poll();
            stats.queuePops++;
            if (isCurrent(entry)) {
                stats.validEvents++;
                return entry;
            }
            stats.staleEvents++;
        }
        return null;
    }

    private QueueEntry peekValid(SimulationStats stats) {
        while (!queue.isEmpty() && !isCurrent(queue.peek())) {
            queue.poll();
            stats.queuePops++;
            stats.staleEvents++;
        }
        return queue.peek();
    }

    private boolean isCurrent(QueueEntry entry) {
        CollisionEvent event = entry.retained().event();
        if (!EventPredictions.valid(event)) return false;

        RetainedEvent[] retained = outbound[entry.ownerSlot()];
        if (retained == null) return false;
        for (RetainedEvent current : retained) {
            if (current == entry.retained()) return true;
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
        BitSet changedSlots = new BitSet(bodies.length);
        for (Ball body : changed) {
            Integer slot = slotByBall.get(body);
            if (slot == null) throw new IllegalStateException("trajectory change contains an unknown body");
            changedSlots.set(slot);
        }

        BitSet full = (BitSet) changedSlots.clone();
        for (int changedSlot = changedSlots.nextSetBit(0);
             changedSlot >= 0;
             changedSlot = changedSlots.nextSetBit(changedSlot + 1)) {
            BitSet dependents = inbound[changedSlot];
            if (dependents != null) full.or(dependents);
        }
        stats.dependencyInvalidations += full.cardinality();

        // The complete full-reselection set is snapshotted before recompute mutates reverse dependencies.
        for (int ownerSlot = full.nextSetBit(0);
             ownerSlot >= 0;
             ownerSlot = full.nextSetBit(ownerSlot + 1)) {
            recompute(ownerSlot, bounds, policy, stats);
        }

        for (int ownerSlot = 0; ownerSlot < bodies.length; ownerSlot++) {
            if (!full.get(ownerSlot)) refreshAgainstChanged(ownerSlot, changedSlots, policy, stats);
        }
        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    @Override
    public void timeAdvanced(double dt) {
        now += dt;
    }
}
