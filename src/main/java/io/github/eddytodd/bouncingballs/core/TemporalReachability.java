package io.github.eddytodd.bouncingballs.core;

/**
 * Conservative broad-phase reachability bounds for circular bodies under constant acceleration.
 *
 * <p>If two circles begin with center separation {@code d} and combined radius {@code R}, then contact within a
 * horizon {@code t} requires the relative trajectory to cover at least {@code d - R}. The relative displacement is
 * bounded by {@code |v| t + 0.5 |a| t^2}. The historical radial bound deliberately uses L1 norms for velocity and
 * acceleration, which are cheap upper bounds on the Euclidean norms.</p>
 *
 * <p>An additional axis-separable proof is strictly complementary: at contact, both coordinate separations must have
 * magnitude at most {@code R}. If either current coordinate gap exceeds {@code R} plus the maximum possible motion
 * along that same axis through the horizon, contact is impossible regardless of motion along the other axis. This
 * catches geometries that the looser radial L1 bound can admit without requiring a spatial data structure.</p>
 *
 * <p>Both proofs inflate their reachable limits by the shared numerical policy and fail open on non-finite or
 * overflowed inputs. A {@code false} result is therefore a conservative proof of no contact by the supplied horizon;
 * {@code true} means only "possibly reachable" and still requires later filtering or an exact TOI solve.</p>
 *
 * <p>For causal research A/B runs the axis proof can be disabled at JVM startup with
 * {@code -Dbouncingballs.cadqAxisTemporalPruning=false}; the accepted radial proof remains active.</p>
 */
public final class TemporalReachability {
    private static final double SLACK_MULTIPLIER = 8.0;
    private static final boolean AXIS_PRUNING_ENABLED = Boolean.parseBoolean(
            System.getProperty("bouncingballs.cadqAxisTemporalPruning", "true"));

    private TemporalReachability() {}

    /** Apply the stronger axis proof first when enabled, then the historical radial proof. */
    public static boolean couldContactWithin(Ball a, Ball b, double horizon, NumericalPolicy policy) {
        if (AXIS_PRUNING_ENABLED && !couldContactWithinAxes(a, b, horizon, policy)) return false;
        return couldContactWithinRadial(a, b, horizon, policy);
    }

    /**
     * Conservative per-axis reachability proof. Contact requires both coordinate gaps to be closable by the horizon.
     */
    public static boolean couldContactWithinAxes(Ball a, Ball b, double horizon, NumericalPolicy policy) {
        if (Double.isNaN(horizon) || horizon < 0 || Double.isInfinite(horizon)) return true;

        double dx = a.position.x - b.position.x;
        double dy = a.position.y - b.position.y;
        double contactDistance = a.radius + b.radius;
        double dvx = a.velocity.x - b.velocity.x;
        double dvy = a.velocity.y - b.velocity.y;
        double dax = a.acceleration.x - b.acceleration.x;
        double day = a.acceleration.y - b.acceleration.y;

        if (!Double.isFinite(dx)
                || !Double.isFinite(dy)
                || !Double.isFinite(contactDistance)
                || !Double.isFinite(dvx)
                || !Double.isFinite(dvy)
                || !Double.isFinite(dax)
                || !Double.isFinite(day)) {
            return true;
        }

        double h2 = horizon * horizon;
        if (!Double.isFinite(h2)) return true;

        double displacementX = Math.abs(dvx) * horizon + 0.5 * Math.abs(dax) * h2;
        double displacementY = Math.abs(dvy) * horizon + 0.5 * Math.abs(day) * h2;
        if (!Double.isFinite(displacementX) || !Double.isFinite(displacementY)) return true;

        double scale = Math.max(
                Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(contactDistance, Math.max(displacementX, displacementY)));
        double slack = SLACK_MULTIPLIER * policy.tolerance(scale);
        double reachableX = contactDistance + displacementX + slack;
        double reachableY = contactDistance + displacementY + slack;
        if (!Double.isFinite(reachableX) || !Double.isFinite(reachableY)) return true;

        return Math.abs(dx) <= reachableX && Math.abs(dy) <= reachableY;
    }

    /** Historical scalar/radial reachability proof retained independently for causal A/B research. */
    public static boolean couldContactWithinRadial(Ball a, Ball b, double horizon, NumericalPolicy policy) {
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
