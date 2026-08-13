package io.github.eddytodd.bouncingballs.research;

import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;

/**
 * Construction-only mechanism gate for CADQ lower-bound candidate probing.
 *
 * <p>This intentionally does not modify or instantiate the production CADQ scheduler. It replays only the accepted
 * full-owner selection mechanism on deterministic initial workload states, then asks whether a small top-k probe set
 * ranked by {@link EarliestReachabilityLowerBound} materially reduces exact pair TOI solves before any timing campaign
 * is justified.</p>
 */
public final class CadqLowerBoundProbeCli {
    private static final double TIME_SLACK_MULTIPLIER = 4.0;
    private static final NumericalPolicy POLICY = NumericalPolicy.DEFAULT;
    private static final List<Workloads.Kind> DEFAULT_WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM,
            Workloads.Kind.DENSE_UNIFORM,
            Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY,
            Workloads.Kind.WALL_DOMINATED,
            Workloads.Kind.ACCELERATED,
            Workloads.Kind.DIFFERENTIAL_ACCELERATION,
            Workloads.Kind.ADVERSARIAL_INVALIDATION);

    private CadqLowerBoundProbeCli() {}

    public static void main(String[] args) {
        Map<String, String> raw = parse(args);
        List<Integer> ballCounts = ints(raw.getOrDefault("balls", "100,300"));
        List<Integer> probeCounts = ints(raw.getOrDefault("ks", "1,2,4,8"));
        long seedStart = Long.parseLong(raw.getOrDefault("seed-start", "1"));
        int seeds = Integer.parseInt(raw.getOrDefault("seeds", "3"));
        double restitution = Double.parseDouble(raw.getOrDefault("restitution", "1"));

        System.out.println(String.join(",",
                "workload",
                "requestedBalls",
                "actualBalls",
                "seed",
                "k",
                "pairQueries",
                "quadraticPairQueries",
                "quarticPairQueries",
                "temporalPrunes",
                "lowerBoundEvaluations",
                "probeSelections",
                "probeExactQueries",
                "probeFiniteHits",
                "probeHorizonTightens",
                "queryFactor",
                "queryReductionPercent"));

        for (Workloads.Kind workload : DEFAULT_WORKLOADS) {
            for (int requestedBalls : ballCounts) {
                for (int seedOffset = 0; seedOffset < seeds; seedOffset++) {
                    long seed = seedStart + seedOffset;
                    Workloads.Setup setup = Workloads.create(workload, requestedBalls, seed, restitution);
                    Ball[] bodies = setup.balls().toArray(Ball[]::new);
                    Arrays.sort(bodies, Comparator.comparingInt(ball -> ball.id));

                    Aggregate baseline = evaluate(bodies, setup.bounds(), 0);
                    print(workload, requestedBalls, bodies.length, seed, 0, baseline, baseline.metrics.pairQueries);

                    for (int k : probeCounts) {
                        if (k < 1 || k > 64) throw new IllegalArgumentException("probe count must be in [1,64]");
                        Aggregate candidate = evaluate(bodies, setup.bounds(), k);
                        verifyEarliestTimes(baseline.bestTimes, candidate.bestTimes, workload, requestedBalls, seed, k);
                        print(workload, requestedBalls, bodies.length, seed, k, candidate, baseline.metrics.pairQueries);
                    }
                }
            }
        }
    }

    private static Aggregate evaluate(Ball[] bodies, Bounds bounds, int k) {
        Metrics metrics = new Metrics();
        double[] bestTimes = new double[bodies.length];
        for (int ownerSlot = 0; ownerSlot < bodies.length; ownerSlot++) {
            bestTimes[ownerSlot] = selectOwner(bodies, ownerSlot, bounds, k, metrics);
        }
        return new Aggregate(metrics, bestTimes);
    }

    private static double selectOwner(Ball[] bodies, int ownerSlot, Bounds bounds, int k, Metrics metrics) {
        Ball owner = bodies[ownerSlot];
        double best = Double.POSITIVE_INFINITY;
        for (int wall = 0; wall < 4; wall++) {
            double t = TimeOfImpact.wall(owner, bounds, wall, POLICY);
            if (Double.isFinite(t) && earlier(t, best)) best = t;
        }

        if (k == 0) {
            for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
                Ball other = bodies[otherSlot];
                if (shouldPrune(owner, other, best)) {
                    metrics.temporalPrunes++;
                    continue;
                }
                double t = exactPair(owner, other, metrics);
                if (Double.isFinite(t) && earlier(t, best)) best = t;
            }
            return best;
        }

        int[] probeSlots = new int[k];
        double[] probeBounds = new double[k];
        int probeSize = 0;
        for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
            double lowerBound = EarliestReachabilityLowerBound.pair(owner, bodies[otherSlot], POLICY);
            metrics.lowerBoundEvaluations++;
            if (!Double.isFinite(lowerBound)) continue;
            probeSize = insertProbe(probeSlots, probeBounds, probeSize, otherSlot, lowerBound);
        }
        metrics.probeSelections += probeSize;

        for (int i = 0; i < probeSize; i++) {
            int otherSlot = probeSlots[i];
            Ball other = bodies[otherSlot];
            if (shouldPrune(owner, other, best)) {
                metrics.temporalPrunes++;
                continue;
            }
            double previousBest = best;
            double t = exactPair(owner, other, metrics);
            metrics.probeExactQueries++;
            if (Double.isFinite(t)) {
                metrics.probeFiniteHits++;
                if (earlier(t, previousBest)) metrics.probeHorizonTightens++;
                if (earlier(t, best)) best = t;
            }
        }

        for (int otherSlot = ownerSlot + 1; otherSlot < bodies.length; otherSlot++) {
            if (contains(probeSlots, probeSize, otherSlot)) continue;
            Ball other = bodies[otherSlot];
            if (shouldPrune(owner, other, best)) {
                metrics.temporalPrunes++;
                continue;
            }
            double t = exactPair(owner, other, metrics);
            if (Double.isFinite(t) && earlier(t, best)) best = t;
        }
        return best;
    }

    private static int insertProbe(
            int[] slots,
            double[] bounds,
            int size,
            int slot,
            double lowerBound) {
        int limit = slots.length;
        int position = 0;
        while (position < size
                && (bounds[position] < lowerBound
                        || (bounds[position] == lowerBound && slots[position] < slot))) {
            position++;
        }
        if (position >= limit) return size;

        int newSize = Math.min(limit, size + 1);
        for (int i = newSize - 1; i > position; i--) {
            slots[i] = slots[i - 1];
            bounds[i] = bounds[i - 1];
        }
        slots[position] = slot;
        bounds[position] = lowerBound;
        return newSize;
    }

    private static boolean contains(int[] slots, int size, int slot) {
        for (int i = 0; i < size; i++) if (slots[i] == slot) return true;
        return false;
    }

    private static boolean shouldPrune(Ball owner, Ball other, double best) {
        if (!Double.isFinite(best)) return false;
        double tieSlack = TIME_SLACK_MULTIPLIER * POLICY.tolerance(Math.abs(best));
        double horizon = Math.max(0.0, best) + tieSlack;
        return !TemporalReachability.couldContactWithin(owner, other, horizon, POLICY);
    }

    private static double exactPair(Ball a, Ball b, Metrics metrics) {
        metrics.pairQueries++;
        if (a.acceleration.x != b.acceleration.x || a.acceleration.y != b.acceleration.y) {
            metrics.quarticPairQueries++;
        } else {
            metrics.quadraticPairQueries++;
        }
        return TimeOfImpact.ballBall(a, b, POLICY);
    }

    private static boolean earlier(double a, double b) {
        return a < b && !POLICY.sameTime(a, b);
    }

    private static void verifyEarliestTimes(
            double[] baseline,
            double[] candidate,
            Workloads.Kind workload,
            int balls,
            long seed,
            int k) {
        for (int i = 0; i < baseline.length; i++) {
            double a = baseline[i];
            double b = candidate[i];
            if (Double.isInfinite(a) && Double.isInfinite(b)) continue;
            if (!Double.isFinite(a) || !Double.isFinite(b) || !POLICY.sameTime(a, b)) {
                throw new IllegalStateException(
                        "earliest owner time changed: workload=" + workload
                                + " balls=" + balls
                                + " seed=" + seed
                                + " k=" + k
                                + " ownerSlot=" + i
                                + " baseline=" + a
                                + " candidate=" + b);
            }
        }
    }

    private static void print(
            Workloads.Kind workload,
            int requestedBalls,
            int actualBalls,
            long seed,
            int k,
            Aggregate aggregate,
            long baselinePairQueries) {
        Metrics m = aggregate.metrics;
        double factor = baselinePairQueries == 0 ? 1.0 : (double) m.pairQueries / baselinePairQueries;
        double reduction = 100.0 * (1.0 - factor);
        System.out.printf(Locale.ROOT,
                "%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.9f,%.6f%n",
                workload,
                requestedBalls,
                actualBalls,
                seed,
                k,
                m.pairQueries,
                m.quadraticPairQueries,
                m.quarticPairQueries,
                m.temporalPrunes,
                m.lowerBoundEvaluations,
                m.probeSelections,
                m.probeExactQueries,
                m.probeFiniteHits,
                m.probeHorizonTightens,
                factor,
                reduction);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) throw new IllegalArgumentException("expected --option, got " + arg);
            String key = arg.substring(2);
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + arg);
            values.put(key, args[++i]);
        }
        return values;
    }

    private static List<Integer> ints(String csv) {
        List<Integer> values = new ArrayList<>();
        for (String token : csv.split(",")) values.add(Integer.parseInt(token.trim()));
        return List.copyOf(values);
    }

    private record Aggregate(Metrics metrics, double[] bestTimes) {}

    private static final class Metrics {
        long pairQueries;
        long quadraticPairQueries;
        long quarticPairQueries;
        long temporalPrunes;
        long lowerBoundEvaluations;
        long probeSelections;
        long probeExactQueries;
        long probeFiniteHits;
        long probeHorizonTightens;
    }
}
