package io.github.eddytodd.bouncingballs.cli;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.*;
import io.github.eddytodd.bouncingballs.scheduler.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Single-run experiment CLI. Use {@link CampaignCli} for differential/repeated research campaigns. */
public final class LabCli {
    private LabCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (args.length == 0 || options.containsKey("help")) {
            help();
            return;
        }
        if (options.containsKey("list")) {
            System.out.println("algorithms=" + Arrays.toString(SchedulerKind.values())
                    + "\nresolvers=" + Arrays.toString(ResolverKind.values())
                    + "\nworkloads=" + Arrays.toString(Workloads.Kind.values()));
            return;
        }

        SchedulerKind scheduler = SchedulerKind.valueOf(
                options.getOrDefault("algorithm", "GLOBAL_EVENT_QUEUE").toUpperCase(Locale.ROOT));
        ResolverKind resolver = ResolverKind.valueOf(
                options.getOrDefault("resolver", "ITERATIVE").toUpperCase(Locale.ROOT));
        Workloads.Kind workload = Workloads.Kind.valueOf(
                options.getOrDefault("workload", "SPARSE_UNIFORM").toUpperCase(Locale.ROOT));
        int requestedBalls = Integer.parseInt(options.getOrDefault("balls", "100"));
        long seed = Long.parseLong(options.getOrDefault("seed", "1"));
        double restitution = Double.parseDouble(options.getOrDefault("restitution", "1"));
        double duration = Double.parseDouble(options.getOrDefault("duration", "1"));
        long events = Long.parseLong(options.getOrDefault("events", "100000"));
        double step = Double.parseDouble(options.getOrDefault("step", "0.001"));

        long workloadStart = System.nanoTime();
        Workloads.Setup setup = Workloads.create(workload, requestedBalls, seed, restitution);
        long workloadGenerationNanos = System.nanoTime() - workloadStart;

        long constructionStart = System.nanoTime();
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(scheduler, resolver, NumericalPolicy.DEFAULT, step));
        long constructionNanos = System.nanoTime() - constructionStart;

        long advanceStart = System.nanoTime();
        simulation.advance(duration, events);
        long advanceNanos = System.nanoTime() - advanceStart;

        String result = json(
                scheduler,
                resolver,
                workload,
                requestedBalls,
                setup.balls().size(),
                seed,
                restitution,
                duration,
                simulation,
                workloadGenerationNanos,
                constructionNanos,
                advanceNanos);
        System.out.println(result);

        String out = options.get("out");
        if (out != null) {
            Path path = Path.of(out).toAbsolutePath();
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    result + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> parsed = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + args[i]);
            String key = args[i].substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            parsed.put(key, value);
        }
        return parsed;
    }

    private static String json(
            SchedulerKind scheduler,
            ResolverKind resolver,
            Workloads.Kind workload,
            int requestedBalls,
            int actualBalls,
            long seed,
            double restitution,
            double duration,
            Simulation simulation,
            long workloadGenerationNanos,
            long constructionNanos,
            long advanceNanos) {
        SimulationStats stats = simulation.stats();
        long totalEngineNanos = constructionNanos + advanceNanos;
        return String.format(
                Locale.ROOT,
                "{\"timestamp\":\"%s\",\"algorithm\":\"%s\",\"resolver\":\"%s\",\"workload\":\"%s\","
                        + "\"requestedBalls\":%d,\"actualBalls\":%d,\"seed\":%d,\"restitution\":%.6f,"
                        + "\"requestedSeconds\":%.6f,\"simulatedSeconds\":%.12f,"
                        + "\"workloadGenerationNanos\":%d,\"constructionNanos\":%d,\"advanceNanos\":%d,"
                        + "\"totalEngineNanos\":%d,\"resolvedContacts\":%d,\"toiQueries\":%d,"
                        + "\"pairToiQueries\":%d,\"quadraticPairToiQueries\":%d,\"quarticPairToiQueries\":%d,"
                        + "\"wallToiQueries\":%d,\"candidateChecks\":%d,\"predictedEventMaterializations\":%d,"
                        + "\"queuePushes\":%d,\"queuePops\":%d,"
                        + "\"validEvents\":%d,\"staleEvents\":%d,\"stalePercent\":%.4f,"
                        + "\"predictionRecomputations\":%d,\"dependencyInvalidations\":%d,"
                        + "\"cadqFullReselections\":%d,\"cadqLocalPairRefreshes\":%d,"
                        + "\"cadqTemporalBoundChecks\":%d,\"cadqTemporalPrunes\":%d,"
                        + "\"cadqTemporalPrunePercent\":%.4f,\"maxQueueSize\":%d}",
                Instant.now(),
                scheduler,
                resolver,
                workload,
                requestedBalls,
                actualBalls,
                seed,
                restitution,
                duration,
                simulation.time(),
                workloadGenerationNanos,
                constructionNanos,
                advanceNanos,
                totalEngineNanos,
                stats.resolvedContacts,
                stats.toiQueries,
                stats.pairToiQueries,
                stats.quadraticPairToiQueries,
                stats.quarticPairToiQueries,
                stats.wallToiQueries,
                stats.candidateChecks,
                stats.predictedEventMaterializations,
                stats.queuePushes,
                stats.queuePops,
                stats.validEvents,
                stats.staleEvents,
                stats.stalePercent(),
                stats.predictionRecomputations,
                stats.dependencyInvalidations,
                stats.cadqFullReselections,
                stats.cadqLocalPairRefreshes,
                stats.cadqTemporalBoundChecks,
                stats.cadqTemporalPrunes,
                stats.cadqTemporalPrunePercent(),
                stats.maxQueueSize);
    }

    private static void help() {
        System.out.println("Usage: LabCli --algorithm GLOBAL_EVENT_QUEUE --resolver ITERATIVE "
                + "--workload SPARSE_UNIFORM --balls 100 --duration 1 --events 100000 --seed 1 "
                + "--restitution 1 --out result.jsonl\nUse --list to enumerate modes. "
                + "Use CampaignCli for repeated differential campaigns.");
    }
}
