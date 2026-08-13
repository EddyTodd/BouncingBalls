package io.github.eddytodd.bouncingballs.core;

/**
 * Conservative broad-phase reachability bound for circular bodies under constant acceleration.
 *
 * <p>If two circles begin with center separation {@code d} and combined radius {@code R}, then contact within a
 * horizon {@code t} requires the relative trajectory to cover at least {@code d - R}. The relative displacement is
 * bounded by {@code |v| t + 0.5 |a| t^2}. This implementation deliberately uses L1 norms for velocity and
 * acceleration, which are cheap upper bounds on the Euclidean norms, and inflates the reachable radius by the shared
 * numerical policy. A {@code false} result is therefore a conservative proof that contact cannot occur by the supplied
 * horizon; a {@code true} result is only "possibly reachable" and still requires an exact TOI solve.</p>
 */
public final class TemporalReachability {
    private static final double SLACK_MULTIPLIER = 8.0;

    private TemporalReachability() {}

    public static boolean couldContactWithin(Ball a, Ball b, double horizon, NumericalPolicy policy) {
        if (Double.isNaN(horizon) || horizon < 0 || Double.isInfinite(horizon)) return true;

        double dx = a.position.x - b.position.x;
        double dy = a.position.y - b.position.y;
        double contactDistance = a.radius + b.radius;

        double relativeSpeedUpper = Math.abs(a.velocity.x - b.velocity.x)
                + Math.abs(a.velocity.y - b.velocity.y);
        double relativeAccelerationUpper = Math.abs(a.acceleration.x - b.acceleration.x)
                + Math.abs(a.acceleration.y - b.acceleration.y);

        if (!Double.isFinite(dx)
                || !Double.isFinite(dy)
                || !Double.isFinite(contactDistance)
                || !Double.isFinite(relativeSpeedUpper)
                || !Double.isFinite(relativeAccelerationUpper)) {
            return true;
        }

        double h2 = horizon * horizon;
        double displacementUpper = relativeSpeedUpper * horizon + 0.5 * relativeAccelerationUpper * h2;
        if (!Double.isFinite(displacementUpper)) return true;

        double scale = Math.max(
                Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(contactDistance, displacementUpper));
        double slack = SLACK_MULTIPLIER * policy.tolerance(scale);
        double reachableCenterDistance = contactDistance + displacementUpper + slack;
        if (!Double.isFinite(reachableCenterDistance)) return true;

        // Compare squared distances to avoid an extra sqrt/hypot in the broad phase. If the right-hand side
        // overflows, the comparison fails open. If only the center-distance square overflows, the bodies are farther
        // apart than any finite reachable radius and pruning remains conservative.
        double distanceSquared = dx * dx + dy * dy;
        double reachableSquared = reachableCenterDistance * reachableCenterDistance;
        if (Double.isInfinite(reachableSquared)) return true;
        return distanceSquared <= reachableSquared;
    }
}
