package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Compute-ahead dependency queue (CADQ).
 *
 * <p>Each body owns its complete earliest-time tie set. Reverse links record which owners currently depend on
 * another body. When trajectories change, owners whose retained events became invalid are fully recomputed, while
 * otherwise-unaffected owners only test the changed bodies for newly-earlier or equal-earliest pair events. This
 * preserves the global earliest-event and simultaneous-contact invariants without the previous all-owner/full-
 * reselection safeguard.</p>
 */
public final class ComputeAheadDependencyQueue implements EventScheduler {
    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private final Map<Ball, List<CollisionEvent>> outbound = new HashMap<>();
    private final Map<Ball, Set<Ball>> inbound = new HashMap<>();
    private double now;

    @Override
    public void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        queue.clear();
        outbound.clear();
        inbound.clear();
        for (Ball owner : balls) recompute(owner, balls, bounds, policy, stats);
        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    private void recompute(Ball owner, List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats) {
        removeOutbound(owner);
        List<CollisionEvent> best = new ArrayList<>();
        for (Ball other : balls) {
            if (other == owner) continue;
            consider(best, EventPredictions.pair(owner, other, policy, stats, now), policy);
        }
        for (int wall = 0; wall < 4; wall++) {
            consider(best, EventPredictions.wall(owner, bounds, wall, policy, stats, now), policy);
        }
        install(owner, best, stats);
        stats.predictionRecomputations++;
        stats.cadqFullReselections++;
    }

    private void refreshAgainstChanged(Ball owner, Set<Ball> changed, NumericalPolicy policy, SimulationStats stats) {
        List<CollisionEvent> current = outbound.get(owner);
        List<CollisionEvent> best = current == null ? new ArrayList<>() : new ArrayList<>(current);
        boolean modified = false;
        for (Ball other : changed) {
            if (other == owner) continue;
            CollisionEvent candidate = EventPredictions.pair(owner, other, policy, stats, now);
            stats.cadqLocalPairRefreshes++;
            if (candidate == null) continue;
            if (best.isEmpty() || earlier(candidate.time(), best.get(0).time(), policy)) {
                best.clear();
                best.add(candidate);
                modified = true;
            } else if (policy.sameTime(candidate.time(), best.get(0).time())) {
                best.add(candidate);
                modified = true;
            }
        }
        if (modified) {
            removeOutbound(owner);
            install(owner, best, stats);
        }
    }

    private static void consider(List<CollisionEvent> best, CollisionEvent candidate, NumericalPolicy policy) {
        if (candidate == null) return;
        if (best.isEmpty() || earlier(candidate.time(), best.get(0).time(), policy)) {
            best.clear();
            best.add(candidate);
        } else if (policy.sameTime(candidate.time(), best.get(0).time())) {
            best.add(candidate);
        }
    }

    private static boolean earlier(double a, double b, NumericalPolicy policy) {
        return a < b && !policy.sameTime(a, b);
    }

    private void removeOutbound(Ball owner) {
        List<CollisionEvent> old = outbound.remove(owner);
        if (old == null) return;
        for (CollisionEvent event : old) {
            if (event.b() == null) continue;
            Set<Ball> dependents = inbound.get(event.b());
            if (dependents == null) continue;
            dependents.remove(owner);
            if (dependents.isEmpty()) inbound.remove(event.b());
        }
    }

    private void install(Ball owner, List<CollisionEvent> events, SimulationStats stats) {
        if (events == null || events.isEmpty()) return;
        List<CollisionEvent> retained = List.copyOf(events);
        outbound.put(owner, retained);
        for (CollisionEvent event : retained) {
            queue.add(event);
            stats.queuePushes++;
            if (event.b() != null) inbound.computeIfAbsent(event.b(), ignored -> new HashSet<>()).add(owner);
        }
    }

    @Override
    public List<CollisionEvent> nextBatch(NumericalPolicy policy, SimulationStats stats) {
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
    }

    private CollisionEvent takeValid(SimulationStats stats) {
        while (!queue.isEmpty()) {
            CollisionEvent event = queue.poll();
            stats.queuePops++;
            if (isCurrent(event)) {
                stats.validEvents++;
                return event;
            }
            stats.staleEvents++;
        }
        return null;
    }

    private CollisionEvent peekValid(SimulationStats stats) {
        while (!queue.isEmpty() && !isCurrent(queue.peek())) {
            queue.poll();
            stats.queuePops++;
            stats.staleEvents++;
        }
        return queue.peek();
    }

    private boolean isCurrent(CollisionEvent event) {
        List<CollisionEvent> retained = outbound.get(event.a());
        if (retained == null || !EventPredictions.valid(event)) return false;
        for (CollisionEvent current : retained) if (current == event) return true;
        return false;
    }

    @Override
    public void trajectoriesChanged(Set<Ball> changed, List<Ball> balls, Bounds bounds,
                                    NumericalPolicy policy, SimulationStats stats) {
        Set<Ball> full = new HashSet<>(changed);
        for (Ball body : changed) full.addAll(inbound.getOrDefault(body, Set.of()));
        stats.dependencyInvalidations += full.size();

        // Snapshot before mutations: recompute() rewrites reverse links.
        for (Ball owner : full) recompute(owner, balls, bounds, policy, stats);

        // For an owner whose previous earliest tie set did not depend on a changed body, every old prediction
        // against unchanged bodies remains valid. Only a changed body can introduce a newly-earlier/equal event.
        for (Ball owner : balls) {
            if (!full.contains(owner)) refreshAgainstChanged(owner, changed, policy, stats);
        }
        stats.maxQueueSize = Math.max(stats.maxQueueSize, queue.size());
    }

    @Override
    public void timeAdvanced(double dt) {
        now += dt;
    }
}
