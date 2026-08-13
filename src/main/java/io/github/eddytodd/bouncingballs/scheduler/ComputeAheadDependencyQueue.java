package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Compute-ahead dependency queue (CADQ).
 *
 * <p>Each body owns exactly one retained earliest event. Reverse links record which owners currently depend on
 * another body. When trajectories change, owners whose retained event became invalid are fully recomputed, while
 * otherwise-unaffected owners only test the changed bodies for a newly-earlier pair event. This preserves the
 * global earliest-event invariant without the previous all-owner/full-reselection safeguard.</p>
 */
public final class ComputeAheadDependencyQueue implements EventScheduler {
    private final PriorityQueue<CollisionEvent> queue = new PriorityQueue<>();
    private final Map<Ball, CollisionEvent> outbound = new HashMap<>();
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
        CollisionEvent best = null;
        for (Ball other : balls) {
            if (other == owner) continue;
            CollisionEvent candidate = EventPredictions.pair(owner, other, policy, stats, now);
            if (candidate != null && (best == null || candidate.compareTo(best) < 0)) best = candidate;
        }
        for (int wall = 0; wall < 4; wall++) {
            CollisionEvent candidate = EventPredictions.wall(owner, bounds, wall, policy, stats, now);
            if (candidate != null && (best == null || candidate.compareTo(best) < 0)) best = candidate;
        }
        install(owner, best, stats);
        stats.predictionRecomputations++;
        stats.cadqFullReselections++;
    }

    private void refreshAgainstChanged(Ball owner, Set<Ball> changed, NumericalPolicy policy, SimulationStats stats) {
        CollisionEvent best = outbound.get(owner);
        for (Ball other : changed) {
            if (other == owner) continue;
            CollisionEvent candidate = EventPredictions.pair(owner, other, policy, stats, now);
            stats.cadqLocalPairRefreshes++;
            if (candidate != null && (best == null || candidate.compareTo(best) < 0)) best = candidate;
        }
        if (best != outbound.get(owner)) {
            removeOutbound(owner);
            install(owner, best, stats);
        }
    }

    private void removeOutbound(Ball owner) {
        CollisionEvent old = outbound.remove(owner);
        if (old == null || old.b() == null) return;
        Set<Ball> dependents = inbound.get(old.b());
        if (dependents == null) return;
        dependents.remove(owner);
        if (dependents.isEmpty()) inbound.remove(old.b());
    }

    private void install(Ball owner, CollisionEvent event, SimulationStats stats) {
        if (event == null) return;
        outbound.put(owner, event);
        queue.add(event);
        stats.queuePushes++;
        if (event.b() != null) inbound.computeIfAbsent(event.b(), ignored -> new HashSet<>()).add(owner);
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
        return outbound.get(event.a()) == event && EventPredictions.valid(event);
    }

    @Override
    public void trajectoriesChanged(Set<Ball> changed, List<Ball> balls, Bounds bounds,
                                    NumericalPolicy policy, SimulationStats stats) {
        Set<Ball> full = new HashSet<>(changed);
        for (Ball body : changed) full.addAll(inbound.getOrDefault(body, Set.of()));
        stats.dependencyInvalidations += full.size();

        // Snapshot before mutations: recompute() rewrites reverse links.
        for (Ball owner : full) recompute(owner, balls, bounds, policy, stats);

        // For an owner whose previous best event did not depend on a changed body, every old prediction against
        // unchanged bodies remains valid. Only a changed body can introduce a newly-earlier event.
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
