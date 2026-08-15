package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.StateSnapshot;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Temporary hosted-runner scaling campaign; removed before merge. */
class SweptBvhVsSapResearchTest {
    private static final List<Workloads.Kind> WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM,
            Workloads.Kind.DENSE_UNIFORM,
            Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY,
            Workloads.Kind.WALL_DOMINATED,
            Workloads.Kind.ACCELERATED,
            Workloads.Kind.ADVERSARIAL_INVALIDATION);
    private static final int WARMUPS = 1;
    private static final int MEASURED = 5;
    private static final double DURATION = 0.25;
    private static final double STATE_TOLERANCE_MULTIPLIER = 10_000;

    @Test
    void compareSweptBvhAgainstSapAtOneThousandBodies() {
        List<Double> allRatios = new ArrayList<>();
        Map<Workloads.Kind, List<Double>> ratiosByWorkload = new LinkedHashMap<>();

        int balls = 1000;
        for (Workloads.Kind workload : WORKLOADS) {
            List<Double> workloadRatios = ratiosByWorkload.computeIfAbsent(workload, ignored -> new ArrayList<>());
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

                    assertEquivalent(workload, seed, sap, bvh);
                    assertEquals(sap.stats.sapExactPairCandidates, bvh.stats.bvhExactPairCandidates,
                            workload + " seed=" + seed + " exact candidates");
                    assertEquals(sap.stats.pairToiQueries, bvh.stats.pairToiQueries,
                            workload + " seed=" + seed + " pair TOIs");

                    if (repetition >= 0) {
                        sapTimes.add(sap.nanos);
                        bvhTimes.add(bvh.nanos);
                    }
                }

                long sapMedian = median(sapTimes);
                long bvhMedian = median(bvhTimes);
                double ratio = (double) bvhMedian / sapMedian;
                workloadRatios.add(ratio);
                allRatios.add(ratio);
                System.out.printf(Locale.ROOT,
                        "BVH_SAP_SCALE workload=%s balls=1000 seed=%d factor=%.6f sapNs=%d bvhNs=%d%n",
                        workload, seed, ratio, sapMedian, bvhMedian);
            }
        }

        for (Map.Entry<Workloads.Kind, List<Double>> entry : ratiosByWorkload.entrySet()) {
            System.out.printf(Locale.ROOT,
                    "BVH_SAP_SCALE_WORKLOAD workload=%s geoFactor=%.6f wins=%d%n",
                    entry.getKey(), geometricMean(entry.getValue()), countWins(entry.getValue()));
        }
        System.out.printf(Locale.ROOT,
                "BVH_SAP_SCALE_TOTAL balls=1000 scenarios=%d geoFactor=%.6f wins=%d%n",
                allRatios.size(), geometricMean(allRatios), countWins(allRatios));
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

    private static void assertEquivalent(Workloads.Kind workload, long seed, Run sap, Run bvh) {
        StateSnapshot.Difference difference = bvh.snapshot.compareTo(
                sap.snapshot,
                NumericalPolicy.DEFAULT,
                STATE_TOLERANCE_MULTIPLIER);
        assertTrue(difference.equivalent(), workload + " seed=" + seed + " reason=" + difference.reason());
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
