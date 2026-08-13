package io.github.eddytodd.bouncingballs.cli;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/** Deterministic workload factory used by both the CLI and differential research campaigns. */
public final class Workloads {
    public enum Kind {
        SPARSE_UNIFORM,
        DENSE_UNIFORM,
        CLUSTERED,
        HIGH_VELOCITY,
        NEWTON_CRADLE,
        SYMMETRIC_IMPACT,
        WALL_DOMINATED,
        ACCELERATED,
        ADVERSARIAL_INVALIDATION
    }

    public record Setup(List<Ball> balls, Bounds bounds) {}

    private static final Bounds DEFAULT_BOUNDS = new Bounds(0, 0, 1000, 1000);
    private static final int MAX_PLACEMENT_ATTEMPTS = 20_000;

    private Workloads() {}

    public static Setup create(Kind kind, int count, long seed, double restitution) {
        Objects.requireNonNull(kind, "kind");
        if (count < 0) throw new IllegalArgumentException("negative ball count");

        if (kind == Kind.NEWTON_CRADLE) return newtonCradle(count, restitution);
        if (kind == Kind.SYMMETRIC_IMPACT) return symmetricImpact(restitution);

        Random random = new Random(seed);
        List<Ball> balls = new ArrayList<>();
        double radius = kind == Kind.DENSE_UNIFORM ? 8 : 3;

        for (int id = 0; id < count; id++) {
            Vec2 position = place(kind, id, radius, random, balls, DEFAULT_BOUNDS);
            double speed = kind == Kind.HIGH_VELOCITY ? 300 : kind == Kind.WALL_DOMINATED ? 180 : 30;
            double angle = random.nextDouble() * Math.PI * 2;
            Vec2 acceleration = kind == Kind.ACCELERATED ? new Vec2(0, -9.81) : new Vec2(0, 0);
            balls.add(new Ball(
                    id,
                    radius,
                    1,
                    restitution,
                    position,
                    new Vec2(Math.cos(angle) * speed, Math.sin(angle) * speed),
                    acceleration));
        }

        Setup setup = new Setup(List.copyOf(balls), DEFAULT_BOUNDS);
        validateInitialState(setup);
        return setup;
    }

    /**
     * Validates the precondition for continuous-collision experiments: every body starts finite, inside the box,
     * and without penetration. Exact touching is allowed for deliberately constructed contact workloads.
     */
    public static void validateInitialState(Setup setup) {
        Objects.requireNonNull(setup, "setup");
        Bounds bounds = setup.bounds();
        Set<Integer> ids = new HashSet<>();
        List<Ball> balls = setup.balls();

        for (Ball ball : balls) {
            if (!ids.add(ball.id)) throw new IllegalArgumentException("duplicate ball id " + ball.id);
            requireFinite(ball.position.x, "position.x", ball.id);
            requireFinite(ball.position.y, "position.y", ball.id);
            requireFinite(ball.velocity.x, "velocity.x", ball.id);
            requireFinite(ball.velocity.y, "velocity.y", ball.id);
            requireFinite(ball.acceleration.x, "acceleration.x", ball.id);
            requireFinite(ball.acceleration.y, "acceleration.y", ball.id);
            if (ball.position.x - ball.radius < bounds.minX()
                    || ball.position.x + ball.radius > bounds.maxX()
                    || ball.position.y - ball.radius < bounds.minY()
                    || ball.position.y + ball.radius > bounds.maxY()) {
                throw new IllegalArgumentException("ball " + ball.id + " starts outside bounds");
            }
        }

        for (int i = 0; i < balls.size(); i++) {
            for (int j = i + 1; j < balls.size(); j++) {
                Ball a = balls.get(i), b = balls.get(j);
                double dx = a.position.x - b.position.x, dy = a.position.y - b.position.y;
                double r = a.radius + b.radius;
                double tolerance = NumericalPolicy.DEFAULT.tolerance(r * r);
                if (dx * dx + dy * dy < r * r - tolerance) {
                    throw new IllegalArgumentException("balls " + a.id + " and " + b.id + " start overlapped");
                }
            }
        }
    }

    private static Setup newtonCradle(int requestedCount, double restitution) {
        int count = Math.max(2, requestedCount);
        double startX = 300;
        double spacing = 20;
        double maxX = startX + (count - 1) * spacing + 300;
        Bounds bounds = new Bounds(0, 0, Math.max(1000, maxX), 1000);
        List<Ball> balls = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            balls.add(new Ball(
                    i,
                    10,
                    1,
                    restitution,
                    new Vec2(startX + i * spacing, 500),
                    new Vec2(i == 0 ? 200 : 0, 0),
                    new Vec2(0, 0)));
        }
        Setup setup = new Setup(List.copyOf(balls), bounds);
        validateInitialState(setup);
        return setup;
    }

    private static Setup symmetricImpact(double restitution) {
        List<Ball> balls = List.of(
                new Ball(0, 10, 1, restitution, new Vec2(450, 500), new Vec2(100, 0), new Vec2(0, 0)),
                new Ball(1, 10, 1, restitution, new Vec2(550, 500), new Vec2(-100, 0), new Vec2(0, 0)),
                new Ball(2, 10, 1, restitution, new Vec2(500, 450), new Vec2(0, 100), new Vec2(0, 0)));
        Setup setup = new Setup(balls, DEFAULT_BOUNDS);
        validateInitialState(setup);
        return setup;
    }

    private static Vec2 place(Kind kind, int id, double radius, Random random, List<Ball> existing, Bounds bounds) {
        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            double x, y;
            if (kind == Kind.CLUSTERED || kind == Kind.ADVERSARIAL_INVALIDATION) {
                double centerX = id % 3 * 300 + 200;
                double centerY = id % 2 * 300 + 300;
                x = centerX + random.nextGaussian() * 50;
                y = centerY + random.nextGaussian() * 50;
            } else {
                x = radius + random.nextDouble() * (bounds.maxX() - bounds.minX() - 2 * radius);
                y = radius + random.nextDouble() * (bounds.maxY() - bounds.minY() - 2 * radius);
            }

            if (!inside(x, y, radius, bounds) || overlaps(x, y, radius, existing)) continue;
            return new Vec2(x, y);
        }
        throw new IllegalArgumentException(
                "could not place ball " + id + " without overlap for workload " + kind
                        + "; reduce density/count or enlarge the domain");
    }

    private static boolean inside(double x, double y, double radius, Bounds bounds) {
        return x - radius >= bounds.minX() && x + radius <= bounds.maxX()
                && y - radius >= bounds.minY() && y + radius <= bounds.maxY();
    }

    private static boolean overlaps(double x, double y, double radius, List<Ball> existing) {
        for (Ball ball : existing) {
            double dx = x - ball.position.x, dy = y - ball.position.y;
            double required = radius + ball.radius;
            double margin = 16 * NumericalPolicy.DEFAULT.tolerance(required);
            required += margin;
            if (dx * dx + dy * dy <= required * required) return true;
        }
        return false;
    }

    private static void requireFinite(double value, String field, int id) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("ball " + id + " has non-finite " + field);
    }
}
