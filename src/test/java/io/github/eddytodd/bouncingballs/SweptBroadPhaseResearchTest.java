package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.StateSnapshot;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary hosted-runner research campaign; removed before merge. */
class SweptBroadPhaseResearchTest {
    private static final List<Workloads.Kind> WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM,
            Workloads.Kind.DENSE_UNIFORM,
            Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY,
            Workloads.Kind.WALL_DOMINATED,
            Workloads.Kind.ACCELERATED,
            Workloads.Kind.ADVERSARIAL_INVALIDATION);
    private static final int WARMUPS = 2;
    private static final int MEASURED = 7;
    private static final double DURATION = 0.25;
    private static final double STATE_TOLERANCE_MULTIPLIER = 10_000;

    @Test
    void compareGridSapAndBvhWithIdenticalSweptCandidateSemantics() {
        Map<Integer, List<Double>> gridSapByCount = new LinkedHashMap<>();
        Map<Integer, List<Double>> gridBvhByCount = new LinkedHashMap<>();
        Map<Workloads.Kind, List<Double>> gridSapByWorkload = new LinkedHashMap<>();
        Map<Workloads.Kind, List<Double>> gridBvhByWorkload = new LinkedHashMap<>();

        for (int balls : List.of(100, 300)) {
            gridSapByCount.put(balls, new ArrayList<>());
            gridBvhByCount.put(balls, new ArrayList<>());
            for (Workloads.Kind workload : WORKLOADS) {
                gridSapByWorkload.computeIfAbsent(workload, ignored -> new ArrayList<>());
                gridBvhByWorkload.computeIfAbsent(workload, ignored -> new ArrayList<>());
                for (long seed = 1; seed <= 3; seed++) {
                    List<Long> sapTimes = new ArrayList<>();
                    List<Long> bvhTimes = new ArrayList<>();
                    List<Long> gridTimes = new ArrayList<>();
                    SimulationStats representativeGridStats = null;

                    for (int repetition = -WARMUPS; repetition < MEASURED; repetition++) {
                        SchedulerKind[] order = rotatedOrder(Math.floorMod(repetition, 3));
                        Map<SchedulerKind, Run> runs = new EnumMap<>(SchedulerKind.class);
                        for (SchedulerKind scheduler : order) {
                            runs.put(scheduler, run(workload, balls, seed, scheduler));
                        }

                        Run sap = runs.get(SchedulerKind.SWEEP_AND_PRUNE_CCD);
                        Run bvh = runs.get(SchedulerKind.SWEPT_BVH_CCD);
                        Run grid = runs.get(SchedulerKind.SWEPT_UNIFORM_GRID_CCD);
                        assertEquivalent(workload, balls, seed, sap, bvh, grid);

                        long exact = sap.stats.sapExactPairCandidates;
                        assertEquals(exact, bvh.stats.bvhExactPairCandidates,
                                workload + " balls=" + balls + " seed=" + seed + " BVH candidates");
                        assertEquals(exact, grid.stats.gridExactPairCandidates,
                                workload + " balls=" + balls + " seed=" + seed + " grid candidates");
                        assertEquals(sap.stats.pairToiQueries, bvh.stats.pairToiQueries);
                        assertEquals(sap.stats.pairToiQueries, grid.stats.pairToiQueries);

                        if (repetition >= 0) {
                            sapTimes.add(sap.nanos);
                            bvhTimes.add(bvh.nanos);
                            gridTimes.add(grid.nanos);
                            representativeGridStats = grid.stats;
                        }
                    }

                    long sapMedian = median(sapTimes);
                    long bvhMedian = median(bvhTimes);
                    long gridMedian = median(gridTimes);
                    double gridSap = (double) gridMedian / sapMedian;
                    double gridBvh = (double) gridMedian / bvhMedian;
                    gridSapByCount.get(balls).add(gridSap);
                    gridBvhByCount.get(balls).add(gridBvh);
                    gridSapByWorkload.get(workload).add(gridSap);
                    gridBvhByWorkload.get(workload).add(gridBvh);

                    System.out.printf(Locale.ROOT,
                            "GRID_SWEEP_SCENARIO workload=%s balls=%d seed=%d gridSap=%.6f gridBvh=%.6f sapNs=%d bvhNs=%d gridNs=%d memberships=%d bucketPairs=%d duplicates=%d uniqueCellPairs=%d aabbRejects=%d exact=%d maxBucket=%d%n",
                            workload,
                            balls,
                            seed,
                            gridSap,
                            gridBvh,
                            sapMedian,
                            bvhMedian,
                            gridMedian,
                            representativeGridStats.gridCellMemberships,
                            representativeGridStats.gridBucketPairAttempts,
                            representativeGridStats.gridDuplicatePairAttempts,
                            representativeGridStats.gridUniqueCellPairs,
                            representativeGridStats.gridAabbRejects,
                            representativeGridStats.gridExactPairCandidates,
                            representativeGridStats.gridMaxBucketOccupancy);
                }
            }
        }

        for (int balls : List.of(100, 300)) {
            List<Double> vsSap = gridSapByCount.get(balls);
            List<Double> vsBvh = gridBvhByCount.get(balls);
            System.out.printf(Locale.ROOT,
                    "GRID_SWEEP_COUNT balls=%d scenarios=%d gridSapGeo=%.6f gridSapWins=%d gridBvhGeo=%.6f gridBvhWins=%d%n",
                    balls, vsSap.size(), geometricMean(vsSap), countWins(vsSap), geometricMean(vsBvh), countWins(vsBvh));
        }
        for (Workloads.Kind workload : WORKLOADS) {
            List<Double> vsSap = gridSapByWorkload.get(workload);
            List<Double> vsBvh = gridBvhByWorkload.get(workload);
            System.out.printf(Locale.ROOT,
                    "GRID_SWEEP_WORKLOAD workload=%s scenarios=%d gridSapGeo=%.6f gridSapWins=%d gridBvhGeo=%.6f gridBvhWins=%d%n",
                    workload, vsSap.size(), geometricMean(vsSap), countWins(vsSap), geometricMean(vsBvh), countWins(vsBvh));
        }

        List<Double> allVsSap = gridSapByCount.values().stream().flatMap(Collection::stream).toList();
        List<Double> allVsBvh = gridBvhByCount.values().stream().flatMap(Collection::stream).toList();
        System.out.printf(Locale.ROOT,
                "GRID_SWEEP_TOTAL scenarios=%d gridSapGeo=%.6f gridSapWins=%d gridBvhGeo=%.6f gridBvhWins=%d%n",
                allVsSap.size(), geometricMean(allVsSap), countWins(allVsSap), geometricMean(allVsBvh), countWins(allVsBvh));
    }

    private static SchedulerKind[] rotatedOrder(int rotation) {
        SchedulerKind[] base = {
                SchedulerKind.SWEEP_AND_PRUNE_CCD,
                SchedulerKind.SWEPT_BVH_CCD,
                SchedulerKind.SWEPT_UNIFORM_GRID_CCD
        };
        SchedulerKind[] result = new SchedulerKind[base.length];
        for (int i = 0; i < base.length; i++) result[i] = base[(i + rotation) % base.length];
        return result;
    }

    private static Run run(Workloads.Kind workload, int balls, long seed, SchedulerKind scheduler) {
        Workloads.Setup setup = Workloads.create(workload, balls, seed, 1);
        long start = System.nanoTime();
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(scheduler, ResolverKind.ITERATIVE, NumericalPolicy.DEFAULT, 0.001));
        simulation.advance(DURATION, 100_000);
        long elapsed = System.nanoTime() - start;
        return new Run(elapsed, StateSnapshot.capture(simulation), simulation.stats());
    }

    private static void assertEquivalent(
            Workloads.Kind workload,
            int balls,
            long seed,
            Run sap,
            Run bvh,
            Run grid) {
        for (Run candidate : List.of(bvh, grid)) {
            StateSnapshot.Difference difference = candidate.snapshot.compareTo(
                    sap.snapshot,
                    NumericalPolicy.DEFAULT,
                    STATE_TOLERANCE_MULTIPLIER);
            assertTrue(difference.equivalent(),
                    workload + " balls=" + balls + " seed=" + seed + " reason=" + difference.reason());
            assertEquals(sap.stats.physicalContactBatches, candidate.stats.physicalContactBatches);
            assertEquals(sap.stats.physicalContactsObserved, candidate.stats.physicalContactsObserved);
            assertEquals(sap.stats.physicalContactHash, candidate.stats.physicalContactHash);
        }
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double geometricMean(List<Double> values) {
        return Math.exp(values.stream().mapToDouble(Math::log).average().orElseThrow());
    }

    private static int countWins(List<Double> ratios) {
        return (int) ratios.stream().filter(value -> value < 1.0).count();
    }

    private record Run(long nanos, StateSnapshot snapshot, SimulationStats stats) {}
}
