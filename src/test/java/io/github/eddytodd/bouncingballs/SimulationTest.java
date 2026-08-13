package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.*;
import io.github.eddytodd.bouncingballs.scheduler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
    private static Ball ball(int id, double x, double vx) {
        return new Ball(id, 1, 1, 1, new Vec2(x, 0), new Vec2(vx, 0), new Vec2(0, 0));
    }

    @Test
    void velocityToiIsExact() {
        assertEquals(4, TimeOfImpact.ballBall(ball(0, 0, 1), ball(1, 10, -1), NumericalPolicy.DEFAULT), 1e-10);
    }

    @Test
    void accelerationToiUsesQuarticModel() {
        Ball a = new Ball(0, 1, 1, 1, new Vec2(0, 0), new Vec2(0, 0), new Vec2(2, 0));
        Ball b = ball(1, 10, 0);
        assertEquals(Math.sqrt(8), TimeOfImpact.ballBall(a, b, NumericalPolicy.DEFAULT), 1e-9);
    }

    @ParameterizedTest
    @EnumSource(ResolverKind.class)
    void equalMassHeadOnConservesAndExchanges(ResolverKind resolver) {
        Ball a = ball(0, 10, 3), b = ball(1, 20, -1);
        Simulation simulation = new Simulation(
                List.of(a, b),
                new Bounds(0, -10, 100, 10),
                new SimulationConfig(SchedulerKind.GLOBAL_EVENT_QUEUE, resolver, NumericalPolicy.DEFAULT, .001));
        simulation.advance(3, 100);
        assertEquals(-1, a.velocity.x, 1e-8);
        assertEquals(3, b.velocity.x, 1e-8);
    }

    @Test
    void schedulersAgreeOnSmallHeadOnCase() {
        for (SchedulerKind scheduler : List.of(
                SchedulerKind.ALL_PAIRS_CCD,
                SchedulerKind.GLOBAL_EVENT_QUEUE,
                SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE)) {
            Ball a = ball(0, 10, 3), b = ball(1, 20, -1);
            Simulation simulation = new Simulation(
                    List.of(a, b),
                    new Bounds(0, -10, 100, 10),
                    new SimulationConfig(scheduler, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, .001));
            simulation.advance(3, 100);
            assertEquals(-1, a.velocity.x, 1e-8, scheduler.toString());
            assertEquals(3, b.velocity.x, 1e-8, scheduler.toString());
        }
    }

    @Test
    void simulationRejectsDuplicateBodyIds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new Simulation(
                        List.of(ball(0, 10, 1), ball(0, 20, -1)),
                        new Bounds(0, -10, 100, 10),
                        SimulationConfig.DEFAULT));
        assertTrue(error.getMessage().contains("unique"));
    }

    @Test
    void staleQueueEntriesAreObserved() {
        Ball a = ball(0, 10, 3), b = ball(1, 20, -1), c = ball(2, 40, -2);
        Simulation simulation = new Simulation(
                List.of(a, b, c), new Bounds(0, -10, 100, 10), SimulationConfig.DEFAULT);
        simulation.advance(5, 100);
        assertTrue(simulation.stats().staleEvents > 0);
    }

    @Test
    void cadqBatchesCompleteEqualTimePhysicalContactSetWithoutDuplicatePairOwnership() {
        Ball left = ball(0, 0, 1), middle = ball(1, 10, 0), right = ball(2, 20, -1);
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(
                List.of(left, middle, right),
                new Bounds(-100, -100, 100, 100),
                NumericalPolicy.DEFAULT,
                stats);

        List<CollisionEvent> batch = queue.nextBatch(NumericalPolicy.DEFAULT, stats);
        assertEquals(2, batch.size(), "each simultaneous physical pair should be represented exactly once");
        assertTrue(batch.stream().allMatch(
                event -> NumericalPolicy.DEFAULT.sameTime(batch.get(0).time(), event.time())));

        Set<String> physical = new HashSet<>();
        for (CollisionEvent event : batch) {
            if (event.b() != null) {
                physical.add(Math.min(event.a().id, event.b().id) + ":" + Math.max(event.a().id, event.b().id));
                assertTrue(event.a().id < event.b().id, "CADQ ball pairs must use canonical lower-id ownership");
            }
        }
        assertEquals(Set.of("0:1", "1:2"), physical);
    }

    @Test
    void cadqDenseSlotsDoNotRequireDenseOrInputOrderedIds() {
        Ball high = ball(1_000_000, 0, 1);
        Ball low = ball(-20, 10, 0);
        Ball middle = ball(7, 20, -1);
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();

        queue.rebuild(
                List.of(high, middle, low),
                new Bounds(-100, -100, 100, 100),
                NumericalPolicy.DEFAULT,
                stats);

        List<CollisionEvent> batch = queue.nextBatch(NumericalPolicy.DEFAULT, stats);
        assertEquals(2, batch.size());
        assertTrue(batch.stream().allMatch(event -> event.b() != null && event.a().id < event.b().id));
        assertEquals(3, stats.candidateChecks, "three unordered pairs must still be queried exactly once");
    }

    @Test
    void cadqInitialSelectionQueriesEachUnorderedPairOnlyOnce() {
        List<Ball> balls = List.of(
                ball(0, 10, 0), ball(1, 30, 0), ball(2, 50, 0), ball(3, 70, 0), ball(4, 90, 0));
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(balls, new Bounds(0, -10, 100, 10), NumericalPolicy.DEFAULT, stats);

        long expectedPairs = balls.size() * (balls.size() - 1L) / 2;
        long expectedWalls = 4L * balls.size();
        assertEquals(expectedPairs, stats.candidateChecks);
        assertEquals(expectedPairs + expectedWalls, stats.toiQueries);
    }

    @Test
    void cadqMaterializesOnlySelectedPredictions() {
        List<Ball> cadqBalls = List.of(
                ball(0, 0, 4), ball(1, 10, 3), ball(2, 20, 2), ball(3, 30, 1));
        List<Ball> globalBalls = List.of(
                ball(0, 0, 4), ball(1, 10, 3), ball(2, 20, 2), ball(3, 30, 1));
        Bounds bounds = new Bounds(-100, -10, 100, 10);

        SimulationStats cadqStats = new SimulationStats();
        ComputeAheadDependencyQueue cadq = new ComputeAheadDependencyQueue();
        cadq.rebuild(cadqBalls, bounds, NumericalPolicy.DEFAULT, cadqStats);

        SimulationStats globalStats = new SimulationStats();
        GlobalEventQueueScheduler global = new GlobalEventQueueScheduler();
        global.rebuild(globalBalls, bounds, NumericalPolicy.DEFAULT, globalStats);

        assertEquals(6, cadqStats.candidateChecks);
        assertEquals(cadqStats.candidateChecks, globalStats.candidateChecks);
        assertEquals(4, cadqStats.predictedEventMaterializations,
                "CADQ should materialize one retained earliest prediction for each owner in this setup");
        assertEquals(10, globalStats.predictedEventMaterializations,
                "GLOBAL must materialize all six finite pair predictions and four finite wall predictions");
        assertTrue(cadqStats.predictedEventMaterializations < globalStats.predictedEventMaterializations);
    }

    @Test
    void cadqLowIdChangesNeedNoUnaffectedPairRefreshes() {
        List<Ball> balls = new ArrayList<>();
        balls.add(ball(0, 10, 1));
        balls.add(ball(1, 14, 0));
        for (int i = 2; i < 8; i++) balls.add(ball(i, 300 + i * 70, 10));

        Bounds bounds = new Bounds(0, -10, 1000, 10);
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(balls, bounds, NumericalPolicy.DEFAULT, stats);
        long initialFull = stats.cadqFullReselections;
        long initialLocal = stats.cadqLocalPairRefreshes;

        balls.get(0).generation++;
        balls.get(1).generation++;
        queue.trajectoriesChanged(
                Set.of(balls.get(0), balls.get(1)), balls, bounds, NumericalPolicy.DEFAULT, stats);

        long updateFull = stats.cadqFullReselections - initialFull;
        long updateLocal = stats.cadqLocalPairRefreshes - initialLocal;
        assertTrue(updateFull < balls.size(), "a local two-body change must not trigger all-owner full reselection");
        assertEquals(0, updateLocal,
                "changed lower-id owners already recompute every pair they canonically own");
    }

    @Test
    void cadqHighIdChangeRefreshesOnlyLowerIdCanonicalOwners() {
        List<Ball> balls = new ArrayList<>();
        for (int i = 0; i < 8; i++) balls.add(ball(i, 50 + i * 100, 0));

        Bounds bounds = new Bounds(0, -10, 1000, 10);
        ComputeAheadDependencyQueue queue = new ComputeAheadDependencyQueue();
        SimulationStats stats = new SimulationStats();
        queue.rebuild(balls, bounds, NumericalPolicy.DEFAULT, stats);
        long initialLocal = stats.cadqLocalPairRefreshes;

        Ball changed = balls.get(7);
        changed.generation++;
        queue.trajectoriesChanged(Set.of(changed), balls, bounds, NumericalPolicy.DEFAULT, stats);

        assertEquals(7, stats.cadqLocalPairRefreshes - initialLocal,
                "each lower-id owner should test the changed high-id body exactly once");
    }

    @Test
    void eventBudgetCountsPhysicalContactsIndependentlyOfSchedulerRepresentation() {
        double referenceTime = runTwoTransfersWithBudget(SchedulerKind.ALL_PAIRS_CCD);
        double cadqTime = runTwoTransfersWithBudget(SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE);
        assertEquals(8.0, referenceTime, 1e-10);
        assertEquals(referenceTime, cadqTime, 1e-10);
    }

    private double runTwoTransfersWithBudget(SchedulerKind scheduler) {
        Ball a = ball(0, 10, 2), b = ball(1, 20, 0), c = ball(2, 30, 0);
        Simulation simulation = new Simulation(
                List.of(a, b, c),
                new Bounds(0, -10, 100, 10),
                new SimulationConfig(scheduler, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, .001));
        simulation.advance(10, 2);
        assertEquals(0, a.velocity.x, 1e-8);
        assertEquals(0, b.velocity.x, 1e-8);
        assertEquals(2, c.velocity.x, 1e-8);
        return simulation.time();
    }
}
