package io.github.eddytodd.bouncingballs.research;

import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/** Canonical final-state representation used to compare scheduler implementations against the all-pairs oracle. */
public record StateSnapshot(double simulationTime, List<BodyState> bodies) {
    public record BodyState(int id, double px, double py, double vx, double vy) {}

    public record Difference(
            boolean equivalent,
            double maxPositionError,
            double maxVelocityError,
            double timeError,
            String reason) {}

    public StateSnapshot {
        bodies = List.copyOf(bodies);
    }

    public static StateSnapshot capture(Simulation simulation) {
        List<BodyState> state = new ArrayList<>(simulation.balls().size());
        for (Ball ball : simulation.balls()) {
            state.add(new BodyState(
                    ball.id,
                    ball.position.x,
                    ball.position.y,
                    ball.velocity.x,
                    ball.velocity.y));
        }
        state.sort(Comparator.comparingInt(BodyState::id));
        return new StateSnapshot(simulation.time(), state);
    }

    public Difference compareTo(StateSnapshot reference, NumericalPolicy policy, double toleranceMultiplier) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(policy, "policy");
        if (!(toleranceMultiplier > 0) || !Double.isFinite(toleranceMultiplier)) {
            throw new IllegalArgumentException("tolerance multiplier must be finite and positive");
        }
        if (bodies.size() != reference.bodies.size()) {
            return new Difference(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, "body count differs");
        }

        double maxPosition = 0;
        double maxVelocity = 0;
        double timeError = Math.abs(simulationTime - reference.simulationTime);
        boolean equivalent = finite(simulationTime) && finite(reference.simulationTime)
                && within(simulationTime, reference.simulationTime, policy, toleranceMultiplier);
        String reason = equivalent ? "" : "simulation time differs";

        for (int i = 0; i < bodies.size(); i++) {
            BodyState actual = bodies.get(i), expected = reference.bodies.get(i);
            if (actual.id != expected.id) {
                return new Difference(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        timeError, "body ids differ at canonical index " + i);
            }

            if (!finite(actual.px) || !finite(actual.py) || !finite(actual.vx) || !finite(actual.vy)
                    || !finite(expected.px) || !finite(expected.py) || !finite(expected.vx) || !finite(expected.vy)) {
                return new Difference(false, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        timeError, "non-finite state for body " + actual.id);
            }

            double px = Math.abs(actual.px - expected.px), py = Math.abs(actual.py - expected.py);
            double vx = Math.abs(actual.vx - expected.vx), vy = Math.abs(actual.vy - expected.vy);
            maxPosition = Math.max(maxPosition, Math.max(px, py));
            maxVelocity = Math.max(maxVelocity, Math.max(vx, vy));

            if (equivalent && (!within(actual.px, expected.px, policy, toleranceMultiplier)
                    || !within(actual.py, expected.py, policy, toleranceMultiplier))) {
                equivalent = false;
                reason = "position differs for body " + actual.id;
            }
            if (equivalent && (!within(actual.vx, expected.vx, policy, toleranceMultiplier)
                    || !within(actual.vy, expected.vy, policy, toleranceMultiplier))) {
                equivalent = false;
                reason = "velocity differs for body " + actual.id;
            }
        }

        return new Difference(equivalent, maxPosition, maxVelocity, timeError, reason);
    }

    private static boolean within(double a, double b, NumericalPolicy policy, double multiplier) {
        double scale = Math.max(Math.abs(a), Math.abs(b));
        return Math.abs(a - b) <= multiplier * policy.tolerance(scale);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
