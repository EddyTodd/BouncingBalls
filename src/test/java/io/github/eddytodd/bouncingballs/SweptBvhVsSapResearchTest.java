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
class SweptBvhVsSapResearchTest {
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
    void compareSweptBvhAgainstSapWithIdenticalCandidateSemantics() {
        Map<Integer, List<Double>> ratiosByCount = new LinkedHashMap<>();
        Map<Workloads.Kind, List<Double>> ratiosByWorkload = new LinkedHashMap<>();
        int totalWins = 0;
        int totalScenarios = 0;

        for (int balls : List.of(100, 300)) {
            ratiosByCount.put(balls, new ArrayList<>());
            for (Workloads.Kind workload : WORKLOADS) {
                ratiosByWorkload.computeIfAbsent(workload, ignored -> new ArrayList<>());
                for (long seed = 1; seed <= 3; seed++) {
                    List<Long> sapTimes = new ArrayList<>();
                    List<Long> bvhTimes = new ArrayList<>();

                    for (int repetition = -WARMUPS; repetition < MEASURED; repetition++) {
                        boolean bvhFirst = (repetition & 1) == 0;
                        Run first = run(workload, balls, seed,
                                bvhFirst ? SchedulerKind.SWEPT_BVH_CCD : SchedulerKind.SWEEP_AND_PRUNE_CCD);
                        Run second = run(workload, balls, seed,
                                bvhFirst ? SchedulerKind.SWEEP_AND_PRUNE_CCD : SchedulerKind.SWEPT_BVH_CCD);
                        Run bvh = bvhFirst ? first : second;
                        Run sap = bvhFirst ? second : first;

                        assertEquivalent(workload, balls, seed, sap, bvh);
                        assertEquals(sap.stats.sapExactPairCandidates, bvh.stats.bvhExactPairCandidates,
                                workload + " balls=" + balls + " seed=" + seed + " exact candidates");
                        assertEquals(sap.stats.pairToiQueries, bvh.stats.pairToiQueries,
                                workload + " balls=" + balls + " seed=" + seed + " pair TOIs");

                        if (repetition >= 0) {
                            sapTimes.add(sap.nanos);
                            bvhTimes.add(bvh.nanos);
                        }
                    }

                    long sapMedian = median(sapTimes);
                    long bvhMedian = median(bvhTimes);
                    double ratio = (double) bvhMedian / sapMedian;
                    ratiosByCount.get(balls).add(ratio);
                    ratiosByWorkload.get(workload).add(ratio);
                    if (ratio < 1.0) totalWins++;
                    totalScenarios++;
                    System.out.printf(Locale.ROOT,
                            "BVH_SAP_SCENARIO workload=%s balls=%d seed=%d factor=%.6f sapNs=%d bvhNs=%d%n",
                            workload, balls, seed, ratio, sapMedian, bvhMedian);
                }
            }
        }

        for (Map.Entry<Integer, List<Double>> entry : ratiosByCount.entrySet()) {
            List<Double> ratios = entry.getValue();
            System.out.printf(Locale.ROOT,
                    "BVH_SAP_COUNT balls=%d scenarios=%d geoFactor=%.6f wins=%d%n",
                    entry.getKey(), ratios.size(), geometricMean(ratios), countWins(ratios));
        }
        for (Map.Entry<Workloads.Kind, List<Double>> entry : ratiosByWorkload.entrySet()) {
            List<Double> ratios = entry.getValue();
            System.out.printf(Locale.ROOT,
                    "BVH_SAP_WORKLOAD workload=%s scenarios=%d geoFactor=%.6f wins=%d%n",
                    entry.getKey(), ratios.size(), geometricMean(ratios), countWins(ratios));
        }
        System.out.printf(Locale.ROOT,
                "BVH_SAP_TOTAL scenarios=%d geoFactor=%.6f wins=%d%n",
                totalScenarios,
                geometricMean(ratiosByCount.values().stream().flatMap(Collection::stream).toList()),
                totalWins);
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
            Run bvh) {
        StateSnapshot.Difference difference = bvh.snapshot.compareTo(
                sap.snapshot,
                NumericalPolicy.DEFAULT,
                STATE_TOLERANCE_MULTIPLIER);
        assertTrue(difference.equivalent(),
                workload + " balls=" + balls + " seed=" + seed + " reason=" + difference.reason());
        assertEquals(sap.stats.physicalContactBatches, bvh.stats.physicalContactBatches);
        assertEquals(sap.stats.physicalContactsObserved, bvh.stats.physicalContactsObserved);
        assertEquals(sap.stats.physicalContactHash, bvh.stats.physicalContactHash);
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
