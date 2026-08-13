package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.EarliestReachabilityLowerBound;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarliestReachabilityLowerBoundTest {
    @Test
    void constantVelocityClosureMatchesExactContactInOneDimension() {
        Ball a = ball(0, 0, 0, 0, 0, 0, 0);
        Ball b = ball(1, 10, 0, -2, 0, 0, 0);

        double exact = TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT);
        double lower = EarliestReachabilityLowerBound.pair(a, b, NumericalPolicy.DEFAULT);

        assertEquals(4.0, exact, 1e-10);
        assertTrue(lower <= exact);
        assertEquals(exact, lower, 1e-9);
    }

    @Test
    void acceleratedClosureMatchesExactContactInOneDimension() {
        Ball a = ball(0, 0, 0, 0, 0, 2, 0);
        Ball b = ball(1, 10, 0, 0, 0, 0, 0);

        double exact = TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT);
        double lower = EarliestReachabilityLowerBound.pair(a, b, NumericalPolicy.DEFAULT);

        assertEquals(Math.sqrt(8.0), exact, 1e-9);
        assertTrue(lower <= exact);
        assertEquals(exact, lower, 1e-8);
    }

    @Test
    void permanentlyOpenAxisProducesInfiniteLowerBound() {
        Ball a = ball(0, 0, 0, 100, 0, 0, 0);
        Ball b = ball(1, 5, 100, 0, 0, 0, 0);

        assertEquals(
                Double.POSITIVE_INFINITY,
                EarliestReachabilityLowerBound.pair(a, b, NumericalPolicy.DEFAULT));
        assertEquals(Double.POSITIVE_INFINITY, TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT));
    }

    @Test
    void randomizedFiniteExactContactsNeverPrecedeLowerBound() {
        Random random = new Random(0x10B0A11DL);
        int finiteContacts = 0;
        for (int sample = 0; sample < 5_000; sample++) {
            Ball a = ball(
                    0,
                    random.nextDouble() * 200 - 100,
                    random.nextDouble() * 200 - 100,
                    random.nextDouble() * 160 - 80,
                    random.nextDouble() * 160 - 80,
                    random.nextDouble() * 30 - 15,
                    random.nextDouble() * 30 - 15);
            Ball b = ball(
                    1,
                    random.nextDouble() * 200 - 100,
                    random.nextDouble() * 200 - 100,
                    random.nextDouble() * 160 - 80,
                    random.nextDouble() * 160 - 80,
                    random.nextDouble() * 30 - 15,
                    random.nextDouble() * 30 - 15);

            double exact = TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT);
            if (!Double.isFinite(exact) || exact > 5.0) continue;
            finiteContacts++;

            double lower = EarliestReachabilityLowerBound.pair(a, b, NumericalPolicy.DEFAULT);
            double tolerance = 32.0 * NumericalPolicy.DEFAULT.tolerance(Math.max(1.0, exact));
            assertTrue(
                    lower <= exact + tolerance,
                    "lower bound exceeded exact TOI for sample " + sample
                            + ": lower=" + lower + " exact=" + exact);
        }
        assertTrue(finiteContacts > 20, "randomized safety test should exercise actual finite contacts");
    }

    private static Ball ball(
            int id,
            double x,
            double y,
            double vx,
            double vy,
            double ax,
            double ay) {
        return new Ball(id, 1, 1, 1, new Vec2(x, y), new Vec2(vx, vy), new Vec2(ax, ay));
    }
}
