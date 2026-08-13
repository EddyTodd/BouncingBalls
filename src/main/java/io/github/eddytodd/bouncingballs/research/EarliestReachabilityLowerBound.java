package io.github.eddytodd.bouncingballs.research;

import io.github.eddytodd.bouncingballs.core.Ball;
import io.github.eddytodd.bouncingballs.core.NumericalPolicy;

/**
 * Conservative lower bound on ball-ball contact time under constant acceleration.
 *
 * <p>At contact the center separation on each coordinate axis must have magnitude at most the combined radius.
 * For one axis, if the current excess gap is {@code g}, then any contact by time {@code t} requires
 * {@code g <= |dv| t + 0.5 |da| t^2}. Solving that monotone envelope gives an earliest possible closure time for
 * each axis; contact cannot occur before the maximum of the two axis bounds.</p>
 *
 * <p>The bound is intentionally optimistic: it subtracts shared numerical slack from each required gap and returns
 * zero on non-finite arithmetic. It is therefore suitable for candidate ordering, never as an exact collision test.
 * Positive infinity is returned only when a finite positive gap exists on an axis with exactly zero relative velocity
 * and acceleration, which proves that axis can never enter the contact corridor under the current trajectory.</p>
 */
public final class EarliestReachabilityLowerBound {
    private static final double SLACK_MULTIPLIER = 8.0;

    private EarliestReachabilityLowerBound() {}

    public static double pair(Ball a, Ball b, NumericalPolicy policy) {
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
            return 0.0;
        }

        double rawGapX = Math.max(0.0, Math.abs(dx) - contactDistance);
        double rawGapY = Math.max(0.0, Math.abs(dy) - contactDistance);
        double scale = Math.max(
                Math.max(Math.abs(dx), Math.abs(dy)),
                Math.max(contactDistance, Math.max(rawGapX, rawGapY)));
        double slack = SLACK_MULTIPLIER * policy.tolerance(scale);
        if (!Double.isFinite(slack)) return 0.0;

        double gapX = Math.max(0.0, rawGapX - slack);
        double gapY = Math.max(0.0, rawGapY - slack);
        double x = axis(gapX, Math.abs(dvx), Math.abs(dax));
        double y = axis(gapY, Math.abs(dvy), Math.abs(day));
        double lowerBound = Math.max(x, y);
        if (Double.isNaN(lowerBound) || lowerBound < 0.0) return 0.0;
        if (lowerBound == 0.0 || Double.isInfinite(lowerBound)) return lowerBound;

        // Bias one representable value toward zero so floating-point rounding cannot make the ordering score
        // accidentally stricter than the analytical envelope it approximates.
        return Math.max(0.0, Math.nextDown(lowerBound));
    }

    private static double axis(double gap, double speedUpper, double accelerationUpper) {
        if (!(gap > 0.0)) return 0.0;
        if (!Double.isFinite(gap)
                || !Double.isFinite(speedUpper)
                || !Double.isFinite(accelerationUpper)) {
            return 0.0;
        }
        if (accelerationUpper == 0.0) {
            return speedUpper == 0.0 ? Double.POSITIVE_INFINITY : gap / speedUpper;
        }

        double discriminant = speedUpper * speedUpper + 2.0 * accelerationUpper * gap;
        if (!Double.isFinite(discriminant) || discriminant < 0.0) return 0.0;
        double denominator = speedUpper + Math.sqrt(discriminant);
        if (!(denominator > 0.0) || !Double.isFinite(denominator)) return 0.0;

        // Stable form of (-v + sqrt(v^2 + 2ag)) / a.
        double t = 2.0 * gap / denominator;
        return Double.isFinite(t) && t >= 0.0 ? t : 0.0;
    }
}
