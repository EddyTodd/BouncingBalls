package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.StateSnapshot;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DifferentialSchedulerTest {
    private static final double STATE_TOLERANCE_MULTIPLIER = 10_000;

    @Test
    void continuousSchedulersMatchAllPairsAcrossDeterministicMatrix() {
        List<SchedulerKind> candidates = List.of(
                SchedulerKind.GLOBAL_EVENT_QUEUE,
                SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE);

        for (Workloads.Kind workload : Workloads.Kind.values()) {
            int count = workload == Workloads.Kind.SYMMETRIC_IMPACT ? 3
                    : workload == Workloads.Kind.NEWTON_CRADLE ? 8 : 24;
            for (long seed = 1; seed <= 3; seed++) {
                StateSnapshot reference = run(workload, count, seed, SchedulerKind.ALL_PAIRS_CCD);
                for (SchedulerKind scheduler : candidates) {
                    StateSnapshot actual = run(workload, count, seed, scheduler);
                    StateSnapshot.Difference difference = actual.compareTo(
                            reference,
                            NumericalPolicy.DEFAULT,
                            STATE_TOLERANCE_MULTIPLIER);
                    assertTrue(
                            difference.equivalent(),
                            () -> workload + " seed=" + seed + " scheduler=" + scheduler
                                    + " reason=" + difference.reason()
                                    + " maxPositionError=" + difference.maxPositionError()
                                    + " maxVelocityError=" + difference.maxVelocityError());
                }
            }
        }
    }

    @Test
    void randomizedWorkloadsAreDeterministicAndInitiallyValid() {
        for (Workloads.Kind workload : List.of(
                Workloads.Kind.SPARSE_UNIFORM,
                Workloads.Kind.DENSE_UNIFORM,
                Workloads.Kind.CLUSTERED,
                Workloads.Kind.HIGH_VELOCITY,
                Workloads.Kind.WALL_DOMINATED,
                Workloads.Kind.ACCELERATED,
                Workloads.Kind.ADVERSARIAL_INVALIDATION)) {
            Workloads.Setup first = Workloads.create(workload, 100, 12345, 1);
            Workloads.Setup second = Workloads.create(workload, 100, 12345, 1);
            assertDoesNotThrow(() -> Workloads.validateInitialState(first));
            assertEquals(first.balls().size(), second.balls().size());
            for (int i = 0; i < first.balls().size(); i++) {
                Ball a = first.balls().get(i), b = second.balls().get(i);
                assertEquals(a.id, b.id);
                assertEquals(a.position.x, b.position.x, 0);
                assertEquals(a.position.y, b.position.y, 0);
                assertEquals(a.velocity.x, b.velocity.x, 0);
                assertEquals(a.velocity.y, b.velocity.y, 0);
            }
        }
    }

    @Test
    void largeNewtonCradleGetsBoundsThatContainEveryBody() {
        Workloads.Setup setup = Workloads.create(Workloads.Kind.NEWTON_CRADLE, 100, 1, 1);
        assertEquals(100, setup.balls().size());
        assertDoesNotThrow(() -> Workloads.validateInitialState(setup));
        Ball last = setup.balls().get(setup.balls().size() - 1);
        assertTrue(last.position.x + last.radius <= setup.bounds().maxX());
    }

    private static StateSnapshot run(
            Workloads.Kind workload,
            int count,
            long seed,
            SchedulerKind scheduler) {
        Workloads.Setup setup = Workloads.create(workload, count, seed, 1);
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(
                        scheduler,
                        ResolverKind.ITERATIVE,
                        NumericalPolicy.DEFAULT,
                        0.001));
        simulation.advance(2.0, 100_000);
        return StateSnapshot.capture(simulation);
    }
}
