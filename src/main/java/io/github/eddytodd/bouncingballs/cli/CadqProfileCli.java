package io.github.eddytodd.bouncingballs.cli;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Collision-specific profiler for the CADQ scheduler.
 *
 * <p>This runner is intentionally separate from {@link CampaignCli}: phase timing inserts nanoTime probes and is
 * diagnostic evidence, not a replacement for the uninstrumented performance campaign. It keeps one JVM alive across
 * warmups and measured repetitions so JIT behavior is comparable within the profile run.</p>
 */
public final class CadqProfileCli {
    private static final List<Workloads.Kind> DEFAULT_WORKLOADS = List.of(
            Workloads.Kind.SPARSE_UNIFORM,
            Workloads.Kind.DENSE_UNIFORM,
            Workloads.Kind.CLUSTERED,
            Workloads.Kind.HIGH_VELOCITY,
            Workloads.Kind.WALL_DOMINATED,
            Workloads.Kind.ACCELERATED,
            Workloads.Kind.ADVERSARIAL_INVALIDATION);

    private record Options(
            List<Workloads.Kind> workloads,
            List<Integer> ballCounts,
            long seedStart,
            int seedCount,
            int warmups,
            int repetitions,
            double duration,
            long maxEvents,
            double restitution,
            ResolverKind resolver,
            Path out,
            boolean overwrite) {}

    private record Execution(Simulation simulation, int actualBalls, long constructionNanos, long advanceNanos) {}

    private CadqProfileCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> raw = parse(args);
        if (raw.containsKey("help")) {
            help();
            return;
        }
        Options options = options(raw);
        System.setProperty("bouncingballs.cadqProfile", "true");

        UUID profileId = UUID.randomUUID();
        try (LineSink sink = LineSink.open(options.out, options.overwrite)) {
            sink.write(environmentJson(profileId, options));
            long trials = 0;

            for (Workloads.Kind workload : options.workloads) {
                for (int requestedBalls : options.ballCounts) {
                    for (int seedOffset = 0; seedOffset < options.seedCount; seedOffset++) {
                        long seed = options.seedStart + seedOffset;
                        for (int warmup = 0; warmup < options.warmups; warmup++) {
                            execute(workload, requestedBalls, seed, options);
                        }
                        for (int repetition = 0; repetition < options.repetitions; repetition++) {
                            Execution execution = execute(workload, requestedBalls, seed, options);
                            sink.write(profileJson(profileId, workload, requestedBalls, seed, repetition, execution));
                            trials++;
                        }
                    }
                }
            }
            sink.write("{" + field("recordType", "summary") + ","
                    + field("profileId", profileId.toString()) + ","
                    + field("trials", trials) + "}");
        }
    }

    private static Execution execute(
            Workloads.Kind workload,
            int requestedBalls,
            long seed,
            Options options) {
        Workloads.Setup setup = Workloads.create(workload, requestedBalls, seed, options.restitution);
        long constructionStart = System.nanoTime();
        Simulation simulation = new Simulation(
                setup.balls(),
                setup.bounds(),
                new SimulationConfig(
                        SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE,
                        options.resolver,
                        NumericalPolicy.DEFAULT,
                        0.001));
        long constructionNanos = System.nanoTime() - constructionStart;

        long advanceStart = System.nanoTime();
        simulation.advance(options.duration, options.maxEvents);
        long advanceNanos = System.nanoTime() - advanceStart;
        return new Execution(simulation, setup.balls().size(), constructionNanos, advanceNanos);
    }

    private static String profileJson(
            UUID profileId,
            Workloads.Kind workload,
            int requestedBalls,
            long seed,
            int repetition,
            Execution execution) {
        SimulationStats stats = execution.simulation.stats();
        long profiled = stats.cadqProfiledAdvanceNanos();
        return "{"
                + field("recordType", "profile") + ","
                + field("profileId", profileId.toString()) + ","
                + field("workload", workload.name()) + ","
                + field("requestedBalls", requestedBalls) + ","
                + field("actualBalls", execution.actualBalls) + ","
                + field("seed", seed) + ","
                + field("repetition", repetition) + ","
                + field("constructionNanos", execution.constructionNanos) + ","
                + field("advanceNanos", execution.advanceNanos) + ","
                + field("cadqProfiledAdvanceNanos", profiled) + ","
                + field("cadqQueueNanos", stats.cadqQueueNanos) + ","
                + field("cadqDependencyDiscoveryNanos", stats.cadqDependencyDiscoveryNanos) + ","
                + field("cadqFullReselectionNanos", stats.cadqFullReselectionNanos) + ","
                + field("cadqLocalRefreshNanos", stats.cadqLocalRefreshNanos) + ","
                + field("cadqQueueValidationChecks", stats.cadqQueueValidationChecks) + ","
                + field("cadqDependencyBatches", stats.cadqDependencyBatches) + ","
                + field("cadqFullOwnersVisited", stats.cadqFullOwnersVisited) + ","
                + field("cadqLocalOwnersVisited", stats.cadqLocalOwnersVisited) + ","
                + field("cadqLocalOwnersModified", stats.cadqLocalOwnersModified) + ","
                + field("cadqRetainedInstalls", stats.cadqRetainedInstalls) + ","
                + field("cadqRetainedRemovals", stats.cadqRetainedRemovals) + ","
                + field("cadqInboundSets", stats.cadqInboundSets) + ","
                + field("cadqInboundClears", stats.cadqInboundClears) + ","
                + field("cadqTemporalBoundChecks", stats.cadqTemporalBoundChecks) + ","
                + field("cadqTemporalPrunes", stats.cadqTemporalPrunes) + ","
                + field("cadqTemporalPrunePercent", stats.cadqTemporalPrunePercent()) + ","
                + field("toiQueries", stats.toiQueries) + ","
                + field("predictedEventMaterializations", stats.predictedEventMaterializations) + ","
                + field("queuePushes", stats.queuePushes) + ","
                + field("queuePops", stats.queuePops) + ","
                + field("staleEvents", stats.staleEvents) + ","
                + field("maxQueueSize", stats.maxQueueSize)
                + "}";
    }

    private static String environmentJson(UUID profileId, Options options) {
        Runtime runtime = Runtime.getRuntime();
        return "{"
                + field("recordType", "environment") + ","
                + field("profileId", profileId.toString()) + ","
                + field("timestamp", Instant.now().toString()) + ","
                + field("commit", System.getProperty("bouncingballs.commit", "unknown")) + ","
                + field("cadqTemporalPruning", System.getProperty("bouncingballs.cadqTemporalPruning", "true")) + ","
                + field("javaVersion", System.getProperty("java.version")) + ","
                + field("javaVmName", System.getProperty("java.vm.name")) + ","
                + field("osName", System.getProperty("os.name")) + ","
                + field("osArch", System.getProperty("os.arch")) + ","
                + field("availableProcessors", runtime.availableProcessors()) + ","
                + field("maxHeapBytes", runtime.maxMemory()) + ","
                + field("warmups", options.warmups) + ","
                + field("repetitions", options.repetitions) + ","
                + field("seedStart", options.seedStart) + ","
                + field("seedCount", options.seedCount) + ","
                + field("duration", options.duration) + ","
                + field("maxEvents", options.maxEvents)
                + "}";
    }

    private static Options options(Map<String, String> raw) {
        List<Workloads.Kind> workloads = raw.containsKey("workloads")
                ? parseWorkloads(raw.get("workloads"))
                : DEFAULT_WORKLOADS;
        List<Integer> ballCounts = parseInts(raw.getOrDefault("balls", "100"));
        long seedStart = Long.parseLong(raw.getOrDefault("seed-start", "1"));
        int seedCount = positiveInt(raw.getOrDefault("seeds", "3"), "seeds", true);
        int warmups = positiveInt(raw.getOrDefault("warmups", "1"), "warmups", false);
        int repetitions = positiveInt(raw.getOrDefault("repetitions", "5"), "repetitions", true);
        double duration = Double.parseDouble(raw.getOrDefault("duration", "1"));
        if (!(duration > 0) || !Double.isFinite(duration)) throw new IllegalArgumentException("duration must be positive");
        long maxEvents = Long.parseLong(raw.getOrDefault("events", "100000"));
        if (maxEvents <= 0) throw new IllegalArgumentException("events must be positive");
        double restitution = Double.parseDouble(raw.getOrDefault("restitution", "1"));
        if (restitution < 0 || restitution > 1 || !Double.isFinite(restitution)) {
            throw new IllegalArgumentException("restitution must be in [0,1]");
        }
        ResolverKind resolver = ResolverKind.valueOf(
                raw.getOrDefault("resolver", "ITERATIVE").toUpperCase(Locale.ROOT));
        Path out = raw.containsKey("out") ? Path.of(raw.get("out")) : null;
        boolean overwrite = Boolean.parseBoolean(raw.getOrDefault("overwrite", "false"));
        return new Options(
                workloads, ballCounts, seedStart, seedCount, warmups, repetitions,
                duration, maxEvents, restitution, resolver, out, overwrite);
    }

    private static List<Workloads.Kind> parseWorkloads(String value) {
        List<Workloads.Kind> result = new ArrayList<>();
        for (String token : value.split(",")) {
            result.add(Workloads.Kind.valueOf(token.trim().toUpperCase(Locale.ROOT)));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("workloads must not be empty");
        return List.copyOf(result);
    }

    private static List<Integer> parseInts(String value) {
        List<Integer> result = new ArrayList<>();
        for (String token : value.split(",")) {
            int parsed = Integer.parseInt(token.trim());
            if (parsed <= 0) throw new IllegalArgumentException("ball counts must be positive");
            result.add(parsed);
        }
        return List.copyOf(result);
    }

    private static int positiveInt(String value, String name, boolean strictlyPositive) {
        int parsed = Integer.parseInt(value);
        if (strictlyPositive ? parsed <= 0 : parsed < 0) {
            throw new IllegalArgumentException(name + (strictlyPositive ? " must be positive" : " must be non-negative"));
        }
        return parsed;
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

    private static String field(String name, String value) {
        return "\"" + name + "\":\"" + escape(value) + "\"";
    }

    private static String field(String name, long value) {
        return "\"" + name + "\":" + value;
    }

    private static String field(String name, double value) {
        return "\"" + name + "\":" + Double.toString(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void help() {
        System.out.println("Usage: CadqProfileCli --workloads SPARSE_UNIFORM,DENSE_UNIFORM --balls 100 "
                + "--seeds 3 --warmups 1 --repetitions 5 --duration 1 --out target/cadq-profile.jsonl --overwrite");
    }

    private static final class LineSink implements AutoCloseable {
        private final BufferedWriter writer;

        private LineSink(BufferedWriter writer) {
            this.writer = writer;
        }

        static LineSink open(Path path, boolean overwrite) throws IOException {
            if (path == null) return new LineSink(null);
            Path absolute = path.toAbsolutePath();
            if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
            OpenOption[] options = overwrite
                    ? new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE}
                    : new OpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE};
            return new LineSink(Files.newBufferedWriter(absolute, options));
        }

        void write(String line) throws IOException {
            System.out.println(line);
            if (writer != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        }

        @Override
        public void close() throws IOException {
            if (writer != null) writer.close();
        }
    }
}
