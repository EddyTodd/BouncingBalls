package io.github.eddytodd.bouncingballs.core;

import io.github.eddytodd.bouncingballs.resolver.*;
import io.github.eddytodd.bouncingballs.scheduler.*;
import java.util.*;

/** Rendering-free deterministic event simulation. Calls to advance are explicit simulation-time operations. */
public final class Simulation {
    private final List<Ball> balls;
    private final Bounds bounds;
    private final SimulationConfig config;
    private final SimulationStats stats = new SimulationStats();
    private final EventScheduler scheduler;
    private final ContactResolver resolver;
    private double time;

    public Simulation(List<Ball> balls, Bounds bounds, SimulationConfig config) {
        this.balls = List.copyOf(balls);
        validateBodies(this.balls);
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = switch (config.scheduler()) {
            case ALL_PAIRS_CCD -> new AllPairsCcdScheduler();
            case GLOBAL_EVENT_QUEUE -> new GlobalEventQueueScheduler();
            case COMPUTE_AHEAD_DEPENDENCY_QUEUE -> new ComputeAheadDependencyQueue();
            case DISCRETE_BASELINE -> null;
        };
        this.resolver = switch (config.resolver()) {
            case SEQUENTIAL -> new SequentialResolver();
            case ITERATIVE -> new IterativeIslandResolver(96, config.numericalPolicy().tolerance(1));
            case DIRECT -> new DirectIslandResolver();
        };
        if (scheduler != null) scheduler.rebuild(this.balls, bounds, config.numericalPolicy(), stats);
    }

    private static void validateBodies(List<Ball> balls) {
        Set<Integer> ids = new HashSet<>();
        for (Ball ball : balls) {
            Objects.requireNonNull(ball, "ball");
            if (!ids.add(ball.id)) {
                throw new IllegalArgumentException("ball ids must be unique; duplicate id " + ball.id);
            }
        }
    }

    public List<Ball> balls() { return balls; }
    public double time() { return time; }
    public SimulationStats stats() { return stats; }

    public void advance(double duration, long maxEvents) {
        if (duration < 0 || maxEvents < 0) throw new IllegalArgumentException("negative limit");
        if (config.scheduler() == SchedulerKind.DISCRETE_BASELINE) {
            discrete(duration, maxEvents);
            return;
        }

        double remaining = duration;
        long events = 0;
        int zeros = 0;
        while (remaining > config.numericalPolicy().tolerance(remaining) && events < maxEvents) {
            List<CollisionEvent> batch = scheduler.nextBatch(config.numericalPolicy(), stats);
            if (batch.isEmpty()) {
                advanceBodies(remaining);
                scheduler.timeAdvanced(remaining);
                time += remaining;
                return;
            }

            double dt = batch.get(0).time() - time;
            if (dt > remaining) {
                advanceBodies(remaining);
                scheduler.timeAdvanced(remaining);
                time += remaining;
                return;
            }
            if (dt < 0) dt = 0;

            advanceBodies(dt);
            scheduler.timeAdvanced(dt);
            time += dt;
            remaining -= dt;

            List<Contact> contacts = contacts(batch);
            stats.observePhysicalBatch(contacts);

            Set<Ball> changed = new HashSet<>();
            for (CollisionEvent event : batch) {
                changed.add(event.a());
                if (event.b() != null) changed.add(event.b());
            }

            resolveIslands(contacts);
            for (Ball ball : changed) ball.generation++;
            scheduler.trajectoriesChanged(changed, balls, bounds, config.numericalPolicy(), stats);
            events += uniquePhysicalEvents(batch);

            if (dt <= config.numericalPolicy().tolerance(time)) {
                if (++zeros > config.numericalPolicy().maxZeroTimeBatches()) {
                    throw new IllegalStateException("zero-time contact loop; see SimulationStats.zeroTimeBatches");
                }
                stats.zeroTimeBatches++;
            } else {
                zeros = 0;
            }
        }
    }

    private void discrete(double duration, long maxEvents) {
        double left = duration;
        long count = 0;
        while (left > 0 && count < maxEvents) {
            double dt = Math.min(left, config.discreteStep());
            advanceBodies(dt);
            time += dt;
            left -= dt;

            List<Contact> contacts = new ArrayList<>();
            for (int i = 0; i < balls.size(); i++) {
                for (int j = i + 1; j < balls.size(); j++) {
                    Ball a = balls.get(i), b = balls.get(j);
                    double x = a.position.x - b.position.x;
                    double y = a.position.y - b.position.y;
                    double radius = a.radius + b.radius;
                    if (x * x + y * y <= radius * radius) contacts.add(ballContact(a, b));
                }
            }
            stats.observePhysicalBatch(contacts);
            resolveIslands(contacts);
            count += contacts.size();
        }
    }

    private void advanceBodies(double dt) {
        for (Ball ball : balls) ball.advance(dt);
    }

    private List<Contact> contacts(List<CollisionEvent> events) {
        Map<String, Contact> unique = new TreeMap<>();
        for (CollisionEvent event : events) {
            Contact contact = event.isWall()
                    ? wallContact(event.a(), event.wall())
                    : ballContact(event.a(), event.b());
            if (contact.normalVelocity() <= config.numericalPolicy().tolerance(1)) {
                unique.putIfAbsent(eventKey(event), contact);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private int uniquePhysicalEvents(List<CollisionEvent> events) {
        Set<String> unique = new HashSet<>();
        for (CollisionEvent event : events) unique.add(eventKey(event));
        return unique.size();
    }

    private String eventKey(CollisionEvent event) {
        return event.isWall()
                ? "w" + event.a().id + ":" + event.wall()
                : "b" + Math.min(event.a().id, event.b().id) + ":" + Math.max(event.a().id, event.b().id);
    }

    private Contact ballContact(Ball a, Ball b) {
        double x = a.position.x - b.position.x;
        double y = a.position.y - b.position.y;
        double distance = Math.hypot(x, y);
        if (distance == 0) {
            x = a.id < b.id ? 1 : -1;
            y = 0;
            distance = 1;
        }
        return new Contact(
                a,
                b,
                x / distance,
                y / distance,
                Math.min(a.restitution, b.restitution),
                Math.min(a.id, b.id) * 1_000_000 + Math.max(a.id, b.id));
    }

    private Contact wallContact(Ball a, int wall) {
        double nx = wall == CollisionEvent.LEFT ? 1 : wall == CollisionEvent.RIGHT ? -1 : 0;
        double ny = wall == CollisionEvent.BOTTOM ? 1 : wall == CollisionEvent.TOP ? -1 : 0;
        return new Contact(a, null, nx, ny, a.restitution, 10_000_000 + a.id * 4 + wall);
    }

    private void resolveIslands(List<Contact> contacts) {
        Map<Ball, List<Contact>> byBall = new HashMap<>();
        for (Contact contact : contacts) {
            byBall.computeIfAbsent(contact.a(), ignored -> new ArrayList<>()).add(contact);
            if (contact.b() != null) byBall.computeIfAbsent(contact.b(), ignored -> new ArrayList<>()).add(contact);
        }

        Set<Contact> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Contact start : contacts) {
            if (!seen.add(start)) continue;
            List<Contact> island = new ArrayList<>();
            Deque<Contact> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                Contact contact = queue.remove();
                island.add(contact);
                for (Ball ball : List.of(contact.a(), contact.b() == null ? contact.a() : contact.b())) {
                    for (Contact next : byBall.getOrDefault(ball, List.of())) {
                        if (seen.add(next)) queue.add(next);
                    }
                }
            }
            resolver.resolve(island, stats);
        }
    }
}
