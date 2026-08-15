package io.github.eddytodd.bouncingballs.scheduler;

import io.github.eddytodd.bouncingballs.core.Ball;
import io.github.eddytodd.bouncingballs.core.NumericalPolicy;

/** Shared conservative trajectory bounds for rebuild-on-change broad phases. */
final class SweptAabb {
    private static final double SLACK_MULTIPLIER = 8.0;

    private SweptAabb() {}

    static double conservativeHorizon(double exactWallHorizon, NumericalPolicy policy) {
        if (!Double.isFinite(exactWallHorizon)) return Double.POSITIVE_INFINITY;
        double slack = SLACK_MULTIPLIER * policy.tolerance(exactWallHorizon);
        double expanded = exactWallHorizon + slack;
        return Double.isFinite(expanded) ? Math.nextUp(expanded) : Double.POSITIVE_INFINITY;
    }

    static Box forBall(Ball ball, double horizon, NumericalPolicy policy) {
        AxisBounds x = sweptAxis(
                ball.position.x,
                ball.velocity.x,
                ball.acceleration.x,
                ball.radius,
                horizon,
                policy);
        AxisBounds y = sweptAxis(
                ball.position.y,
                ball.velocity.y,
                ball.acceleration.y,
                ball.radius,
                horizon,
                policy);
        return new Box(ball, x.min(), x.max(), y.min(), y.max());
    }

    private static AxisBounds sweptAxis(
            double position,
            double velocity,
            double acceleration,
            double radius,
            double horizon,
            NumericalPolicy policy) {
        if (!Double.isFinite(position)
                || !Double.isFinite(velocity)
                || !Double.isFinite(acceleration)
                || !Double.isFinite(radius)
                || !Double.isFinite(horizon)) {
            return AxisBounds.UNBOUNDED;
        }

        double end = positionAt(position, velocity, acceleration, horizon);
        if (!Double.isFinite(end)) return AxisBounds.UNBOUNDED;

        double min = Math.min(position, end);
        double max = Math.max(position, end);

        if (acceleration != 0.0) {
            double vertex = -velocity / acceleration;
            double timeSlack = SLACK_MULTIPLIER * policy.tolerance(horizon);
            if (Double.isFinite(vertex) && vertex >= -timeSlack && vertex <= horizon + timeSlack) {
                double t = Math.max(0.0, Math.min(horizon, vertex));
                double value = positionAt(position, velocity, acceleration, t);
                if (!Double.isFinite(value)) return AxisBounds.UNBOUNDED;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }

        double scale = Math.max(1.0, Math.max(Math.abs(min), Math.max(Math.abs(max), Math.abs(radius))));
        double padding = radius + SLACK_MULTIPLIER * policy.tolerance(scale);
        if (!Double.isFinite(padding)) return AxisBounds.UNBOUNDED;

        double lower = min - padding;
        double upper = max + padding;
        if (!Double.isFinite(lower) || !Double.isFinite(upper)) return AxisBounds.UNBOUNDED;
        return new AxisBounds(Math.nextDown(lower), Math.nextUp(upper));
    }

    private static double positionAt(double position, double velocity, double acceleration, double t) {
        return Math.fma(0.5 * acceleration * t, t, Math.fma(velocity, t, position));
    }

    private record AxisBounds(double min, double max) {
        private static final AxisBounds UNBOUNDED =
                new AxisBounds(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    record Box(Ball ball, double minX, double maxX, double minY, double maxY) {
        boolean overlaps(Box other) {
            return maxX >= other.minX
                    && other.maxX >= minX
                    && maxY >= other.minY
                    && other.maxY >= minY;
        }

        boolean finite() {
            return Double.isFinite(minX)
                    && Double.isFinite(maxX)
                    && Double.isFinite(minY)
                    && Double.isFinite(maxY);
        }

        double centroidX() { return 0.5 * (minX + maxX); }
        double centroidY() { return 0.5 * (minY + maxY); }

        static Box union(Box a, Box b) {
            return new Box(
                    null,
                    Math.min(a.minX, b.minX),
                    Math.max(a.maxX, b.maxX),
                    Math.min(a.minY, b.minY),
                    Math.max(a.maxY, b.maxY));
        }
    }
}
