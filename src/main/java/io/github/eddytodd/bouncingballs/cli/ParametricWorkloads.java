package io.github.eddytodd.bouncingballs.cli;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Deterministic continuous workload space for scheduler-selection research.
 *
 * <p>The fixed 1000x1000 domain deliberately decouples geometric fill from wall horizon. Fill changes body radius,
 * clustering changes only the spatial distribution, and motion parameters vary independently. This prevents a model
 * from learning accidental generator couplings instead of scheduler-relevant physics.</p>
 */
public final class ParametricWorkloads {
    private static final Bounds BOUNDS = new Bounds(0, 0, 1000, 1000);
    private static final int MAX_PLACEMENT_ATTEMPTS = 50_000;

    public record Parameters(
            double fillFraction,
            double clusterStrength,
            double speedScale,
            double wallBias,
            double sharedAcceleration,
            double differentialAcceleration) {
        public Parameters {
            if (!(fillFraction > 0 && fillFraction <= 0.10)) throw new IllegalArgumentException("fillFraction");
            if (clusterStrength < 0 || clusterStrength > 1) throw new IllegalArgumentException("clusterStrength");
            if (!(speedScale > 0)) throw new IllegalArgumentException("speedScale");
            if (wallBias < 0 || wallBias > 1) throw new IllegalArgumentException("wallBias");
            if (sharedAcceleration < 0 || differentialAcceleration < 0) throw new IllegalArgumentException("acceleration");
        }
    }

    public record Setup(List<Ball> balls, Bounds bounds, Parameters parameters, long seed) {}

    private ParametricWorkloads() {}

    /** Six-dimensional low-discrepancy design point. Index is one-based. */
    public static Parameters halton(int index) {
        if (index <= 0) throw new IllegalArgumentException("index must be positive");
        return new Parameters(
                logLerp(0.001, 0.08, radicalInverse(index, 2)),
                0.95 * radicalInverse(index, 3),
                logLerp(0.5, 300.0, radicalInverse(index, 5)),
                radicalInverse(index, 7),
                12.0 * radicalInverse(index, 11),
                12.0 * radicalInverse(index, 13));
    }

    public static Setup create(int requestedBodies, long seed, Parameters parameters) {
        if (requestedBodies <= 0) throw new IllegalArgumentException("requestedBodies");
        Objects.requireNonNull(parameters, "parameters");

        double radius = Math.sqrt(
                parameters.fillFraction()
                        * (BOUNDS.maxX() - BOUNDS.minX())
                        * (BOUNDS.maxY() - BOUNDS.minY())
                        / (requestedBodies * Math.PI));
        Random random = new Random(mixSeed(seed, requestedBodies, parameters));
        List<Ball> balls = new ArrayList<>(requestedBodies);

        for (int id = 0; id < requestedBodies; id++) {
            Vec2 position = place(random, balls, radius, parameters.clusterStrength(), id);
            Vec2 velocity = velocity(random, position, parameters.speedScale(), parameters.wallBias());
            Vec2 acceleration = acceleration(
                    random,
                    parameters.sharedAcceleration(),
                    parameters.differentialAcceleration());
            balls.add(new Ball(id, radius, 1.0, 1.0, position, velocity, acceleration));
        }

        return new Setup(List.copyOf(balls), BOUNDS, parameters, seed);
    }

    public static void validateInitialState(Setup setup) {
        Bounds bounds = setup.bounds();
        Set<Integer> ids = new HashSet<>();
        for (Ball ball : setup.balls()) {
            if (!ids.add(ball.id)) throw new IllegalArgumentException("duplicate id " + ball.id);
            if (!finite(ball.position.x) || !finite(ball.position.y)
                    || !finite(ball.velocity.x) || !finite(ball.velocity.y)
                    || !finite(ball.acceleration.x) || !finite(ball.acceleration.y)) {
                throw new IllegalArgumentException("non-finite state for body " + ball.id);
            }
            if (ball.position.x - ball.radius < bounds.minX()
                    || ball.position.x + ball.radius > bounds.maxX()
                    || ball.position.y - ball.radius < bounds.minY()
                    || ball.position.y + ball.radius > bounds.maxY()) {
                throw new IllegalArgumentException("out-of-bounds body " + ball.id);
            }
        }
        for (int i = 0; i < setup.balls().size(); i++) {
            Ball a = setup.balls().get(i);
            for (int j = i + 1; j < setup.balls().size(); j++) {
                Ball b = setup.balls().get(j);
                double dx = a.position.x - b.position.x;
                double dy = a.position.y - b.position.y;
                double radius = a.radius + b.radius;
                double slack = 16 * NumericalPolicy.DEFAULT.tolerance(radius);
                if (dx * dx + dy * dy < (radius + slack) * (radius + slack)) {
                    throw new IllegalArgumentException("initial overlap " + a.id + "/" + b.id);
                }
            }
        }
    }

    private static Vec2 place(
            Random random,
            List<Ball> existing,
            double radius,
            double clusterStrength,
            int id) {
        double minX = BOUNDS.minX() + radius;
        double maxX = BOUNDS.maxX() - radius;
        double minY = BOUNDS.minY() + radius;
        double maxY = BOUNDS.maxY() - radius;
        double sigma = Math.max(4.0 * radius, 120.0 - 90.0 * clusterStrength);

        for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
            double x;
            double y;
            if (random.nextDouble() < clusterStrength) {
                Vec2 center = clusterCenter(id % 6);
                x = clamp(center.x + gaussian(random) * sigma, minX, maxX);
                y = clamp(center.y + gaussian(random) * sigma, minY, maxY);
            } else {
                x = minX + random.nextDouble() * (maxX - minX);
                y = minY + random.nextDouble() * (maxY - minY);
            }
            if (!overlaps(existing, x, y, radius)) return new Vec2(x, y);
        }
        throw new IllegalStateException(
                "could not place nonpenetrating body; fill=" + radius + " cluster=" + clusterStrength);
    }

    private static Vec2 clusterCenter(int index) {
        return new Vec2(200 + 300 * (index % 3), 300 + 400 * (index / 3));
    }

    private static boolean overlaps(List<Ball> existing, double x, double y, double radius) {
        for (Ball ball : existing) {
            double required = radius + ball.radius + 16 * NumericalPolicy.DEFAULT.tolerance(radius + ball.radius);
            double dx = x - ball.position.x;
            double dy = y - ball.position.y;
            if (dx * dx + dy * dy <= required * required) return true;
        }
        return false;
    }

    private static Vec2 velocity(
            Random random,
            Vec2 position,
            double speedScale,
            double wallBias) {
        double angle = 2.0 * Math.PI * random.nextDouble();
        double randomX = Math.cos(angle);
        double randomY = Math.sin(angle);
        Vec2 wall = nearestWallDirection(position);
        double x = (1.0 - wallBias) * randomX + wallBias * wall.x;
        double y = (1.0 - wallBias) * randomY + wallBias * wall.y;
        double length = Math.hypot(x, y);
        if (length < 1e-12) {
            x = randomX;
            y = randomY;
            length = 1.0;
        }
        double speed = speedScale * (0.5 + random.nextDouble());
        return new Vec2(speed * x / length, speed * y / length);
    }

    private static Vec2 nearestWallDirection(Vec2 position) {
        double left = position.x - BOUNDS.minX();
        double right = BOUNDS.maxX() - position.x;
        double bottom = position.y - BOUNDS.minY();
        double top = BOUNDS.maxY() - position.y;
        double best = left;
        double x = -1;
        double y = 0;
        if (right < best) { best = right; x = 1; y = 0; }
        if (bottom < best) { best = bottom; x = 0; y = -1; }
        if (top < best) { x = 0; y = 1; }
        return new Vec2(x, y);
    }

    private static Vec2 acceleration(Random random, double shared, double differential) {
        double angle = 2.0 * Math.PI * random.nextDouble();
        double magnitude = differential * random.nextDouble();
        return new Vec2(
                magnitude * Math.cos(angle),
                -shared + magnitude * Math.sin(angle));
    }

    private static double gaussian(Random random) {
        double u1 = Math.max(1e-15, random.nextDouble());
        double u2 = random.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    private static long mixSeed(long seed, int bodies, Parameters p) {
        long x = seed ^ (0x9e3779b97f4a7c15L * bodies);
        x = Long.rotateLeft(x ^ Double.doubleToLongBits(p.fillFraction()), 11);
        x = Long.rotateLeft(x ^ Double.doubleToLongBits(p.clusterStrength()), 13);
        x = Long.rotateLeft(x ^ Double.doubleToLongBits(p.speedScale()), 17);
        x = Long.rotateLeft(x ^ Double.doubleToLongBits(p.wallBias()), 19);
        x = Long.rotateLeft(x ^ Double.doubleToLongBits(p.sharedAcceleration()), 23);
        return x ^ Double.doubleToLongBits(p.differentialAcceleration());
    }

    private static double radicalInverse(int index, int base) {
        double factor = 1.0 / base;
        double result = 0;
        int value = index;
        while (value > 0) {
            result += factor * (value % base);
            value /= base;
            factor /= base;
        }
        return result;
    }

    private static double logLerp(double low, double high, double t) {
        return Math.exp(Math.log(low) + t * (Math.log(high) - Math.log(low)));
    }

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
