package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the large/high-speed scenarios that exposed scheduler-dependent floating-point state drift. */
class ContactHistoryTest {
    private record Scenario(Workloads.Kind workload, long seed) {}

    @Test
    void incrementalSchedulersPreserveAllPairsPhysicalContactHistoryInKnownDriftCases() {
        List<Scenario> scenarios = List.of(
                new Scenario(Workloads.Kind.HIGH_VELOCITY, 1),
                new Scenario(Workloads.Kind.HIGH_VELOCITY, 3),
                new Scenario(Workloads.Kind.WALL_DOMINATED, 1));

        for (Scenario scenario : scenarios) {
            Simulation reference = run(scenario, SchedulerKind.ALL_PAIRS_CCD);
            for (SchedulerKind scheduler : List.of(
                    SchedulerKind.GLOBAL_EVENT_QUEUE,
                    SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE)) {
                Simulation actual = run(scenario, scheduler);
                String label = scenario.workload + " seed=" + scenario.seed + " scheduler=" + scheduler;

                assertEquals(reference.stats().resolvedContacts, actual.stats().resolvedContacts,
                        label + " resolved impulse count");
                assertEquals(reference.stats().physicalContactsObserved, actual.stats().physicalContactsObserved,
                        label + " physical contact count");
                assertEquals(reference.stats().physicalContactBatches, actual.stats().physicalContactBatches,
                        label + " physical batch count");
                assertEquals(reference.stats().physicalContactHash, actual.stats().physicalContactHash,
                        label + " physical contact history fingerprint");
            }
        }
    }

    private static Simulation run(Scenario scenario, SchedulerKind scheduler) {
        Workloads.Setup setup = Workloads.create(scenario.workload, 100, scenario.seed, 1);
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(scheduler, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, 0.001));
        simulation.advance(1.0, 100_000);
        return simulation;
    }
}
