package io.github.eddytodd.bouncingballs.cli;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.research.StateSnapshot;
import io.github.eddytodd.bouncingballs.resolver.ResolverKind;
import io.github.eddytodd.bouncingballs.scheduler.SchedulerKind;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Reproducible scheduler-validation campaign.
 *
 * <p>The all-pairs CCD scheduler is the differential oracle. Every measured run starts from a freshly generated
 * copy of the same deterministic workload. Raw JSONL is the primary artifact; aggregation belongs downstream.</p>
 */
public final class CampaignCli {
    private static final List<SchedulerKind> CONTINUOUS_SCHEDULERS = List.of(
            SchedulerKind.ALL_PAIRS_CCD,
            SchedulerKind.GLOBAL_EVENT_QUEUE,
            SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE);

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
            double stateToleranceMultiplier,
            Path out,
            boolean overwrite) {}

    private record Execution(
            Simulation simulation,
            StateSnapshot snapshot,
            int actualBalls,
            long constructionNanos,
            long advanceNanos,
            Throwable error) {
        long totalEngineNanos() { return constructionNanos + advanceNanos; }
        boolean succeeded() { return error == null; }
    }

    private record Scenario(Workloads.Kind workload, int requestedBalls, long seed) {}

    private CampaignCli() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> raw = parse(args);
        if (raw.containsKey("help")) {
            help();
            return;
        }
        Options options = options(raw);
        String campaignId = UUID.randomUUID().toString();
        Instant started = Instant.now();

        try (LineSink sink = LineSink.open(options.out, options.overwrite)) {
            sink.write(environmentJson(campaignId, started, options));

            long scenarios = 0;
            long measuredTrials = 0;
            long correctnessFailures = 0;
            long executionFailures = 0;

            for (Workloads.Kind workload : options.workloads) {
                for (int countIndex = 0; countIndex < options.ballCounts.size(); countIndex++) {
                    if (workload == Workloads.Kind.SYMMETRIC_IMPACT && countIndex > 0) continue;
                    int requestedBalls = options.ballCounts.get(countIndex);
                    for (int seedOffset = 0; seedOffset < options.seedCount; seedOffset++) {
                        Scenario scenario = new Scenario(workload, requestedBalls, options.seedStart + seedOffset);
                        scenarios++;

                        Execution reference = execute(
                                scenario,
                                SchedulerKind.ALL_PAIRS_CCD,
                                options.resolver,
                                options.duration,
                                options.maxEvents,
                                options.restitution);
                        sink.write(referenceJson(campaignId, scenario, reference));
                        if (!reference.succeeded()) {
                            executionFailures++;
                            continue;
                        }

                        for (SchedulerKind scheduler : CONTINUOUS_SCHEDULERS) {
                            for (int warmup = 0; warmup < options.warmups; warmup++) {
                                execute(scenario, scheduler, options.resolver, options.duration,
                                        options.maxEvents, options.restitution);
                            }
                        }

                        for (int repetition = 0; repetition < options.repetitions; repetition++) {
                            List<SchedulerKind> order = new ArrayList<>(CONTINUOUS_SCHEDULERS);
                            int rotation = Math.floorMod(Long.hashCode(scenario.seed) + repetition, order.size());
                            Collections.rotate(order, rotation);

                            for (SchedulerKind scheduler : order) {
                                Execution measured = execute(
                                        scenario,
                                        scheduler,
                                        options.resolver,
                                        options.duration,
                                        options.maxEvents,
                                        options.restitution);
                                measuredTrials++;

                                StateSnapshot.Difference difference = measured.succeeded()
                                        ? measured.snapshot.compareTo(
                                                reference.snapshot,
                                                NumericalPolicy.DEFAULT,
                                                options.stateToleranceMultiplier)
                                        : new StateSnapshot.Difference(
                                                false,
                                                Double.POSITIVE_INFINITY,
                                                Double.POSITIVE_INFINITY,
                                                Double.POSITIVE_INFINITY,
                                                "execution failed");

                                if (!measured.succeeded()) executionFailures++;
                                else if (!difference.equivalent()) correctnessFailures++;

                                sink.write(trialJson(
                                        campaignId,
                                        scenario,
                                        scheduler,
                                        repetition,
                                        measured,
                                        difference,
                                        options));
                            }
                        }
                    }
                }
            }

            Instant finished = Instant.now();
            sink.write(summaryJson(
                    campaignId,
                    started,
                    finished,
                    scenarios,
                    measuredTrials,
                    correctnessFailures,
                    executionFailures));

            if (correctnessFailures != 0 || executionFailures != 0) {
                throw new IllegalStateException(
                        "campaign found " + correctnessFailures + " differential mismatches and "
                                + executionFailures + " execution failures; inspect raw JSONL");
            }
        }
    }

    private static Execution execute(
            Scenario scenario,
            SchedulerKind scheduler,
            ResolverKind resolver,
            double duration,
            long maxEvents,
            double restitution) {
        try {
            Workloads.Setup setup = Workloads.create(
                    scenario.workload,
                    scenario.requestedBalls,
                    scenario.seed,
                    restitution);

            long constructionStart = System.nanoTime();
            Simulation simulation = new Simulation(
                    setup.balls(),
                    setup.bounds(),
                    new SimulationConfig(scheduler, resolver, NumericalPolicy.DEFAULT, 0.001));
            long constructionNanos = System.nanoTime() - constructionStart;

            long advanceStart = System.nanoTime();
            simulation.advance(duration, maxEvents);
            long advanceNanos = System.nanoTime() - advanceStart;

            return new Execution(
                    simulation,
                    StateSnapshot.capture(simulation),
                    setup.balls().size(),
                    constructionNanos,
                    advanceNanos,
                    null);
        } catch (Throwable error) {
            return new Execution(null, null, -1, 0, 0, error);
        }
    }

    private static String environmentJson(String campaignId, Instant started, Options options) {
        Runtime runtime = Runtime.getRuntime();
        return "{"
                + field("recordType", "environment") + ","
                + field("campaignId", campaignId) + ","
                + field("startedAt", started.toString()) + ","
                + field("campaignSchema", 1) + ","
                + field("commit", commitIdentity()) + ","
                + field("javaVersion", System.getProperty("java.version")) + ","
                + field("javaVmName", System.getProperty("java.vm.name")) + ","
                + field("javaVmVersion", System.getProperty("java.vm.version")) + ","
                + field("osName", System.getProperty("os.name")) + ","
                + field("osVersion", System.getProperty("os.version")) + ","
                + field("osArch", System.getProperty("os.arch")) + ","
                + field("availableProcessors", runtime.availableProcessors()) + ","
                + field("maxHeapBytes", runtime.maxMemory()) + ","
                + field("resolver", options.resolver.name()) + ","
                + field("duration", options.duration) + ","
                + field("maxEvents", options.maxEvents) + ","
                + field("warmups", options.warmups) + ","
                + field("repetitions", options.repetitions) + ","
                + field("seedStart", options.seedStart) + ","
                + field("seedCount", options.seedCount) + ","
                + field("restitution", options.restitution) + ","
                + field("stateToleranceMultiplier", options.stateToleranceMultiplier)
                + "}";
    }

    private static String referenceJson(String campaignId, Scenario scenario, Execution execution) {
        StringBuilder json = new StringBuilder("{")
                .append(field("recordType", "reference")).append(',')
                .append(field("campaignId", campaignId)).append(',')
                .append(field("workload", scenario.workload.name())).append(',')
                .append(field("requestedBalls", scenario.requestedBalls)).append(',')
                .append(field("seed", scenario.seed)).append(',')
                .append(field("algorithm", SchedulerKind.ALL_PAIRS_CCD.name())).append(',')
                .append(field("success", execution.succeeded()));
        appendExecution(json, execution);
        return json.append('}').toString();
    }

    private static String trialJson(
            String campaignId,
            Scenario scenario,
            SchedulerKind scheduler,
            int repetition,
            Execution execution,
            StateSnapshot.Difference difference,
            Options options) {
        StringBuilder json = new StringBuilder("{")
                .append(field("recordType", "trial")).append(',')
                .append(field("campaignId", campaignId)).append(',')
                .append(field("workload", scenario.workload.name())).append(',')
                .append(field("requestedBalls", scenario.requestedBalls)).append(',')
                .append(field("seed", scenario.seed)).append(',')
                .append(field("algorithm", scheduler.name())).append(',')
                .append(field("resolver", options.resolver.name())).append(',')
                .append(field("repetition", repetition)).append(',')
                .append(field("success", execution.succeeded())).append(',')
                .append(field("equivalentToAllPairs", difference.equivalent())).append(',')
                .append(field("maxPositionError", difference.maxPositionError())).append(',')
                .append(field("maxVelocityError", difference.maxVelocityError())).append(',')
                .append(field("simulationTimeError", difference.timeError())).append(',')
                .append(field("differenceReason", difference.reason()));
        appendExecution(json, execution);
        return json.append('}').toString();
    }

    private static void appendExecution(StringBuilder json, Execution execution) {
        if (!execution.succeeded()) {
            json.append(',').append(field("errorType", execution.error.getClass().getName()));
            json.append(',').append(field("errorMessage", String.valueOf(execution.error.getMessage())));
            return;
        }

        Simulation simulation = execution.simulation;
        SimulationStats stats = simulation.stats();
        json.append(',').append(field("actualBalls", execution.actualBalls));
        json.append(',').append(field("simulatedSeconds", simulation.time()));
        json.append(',').append(field("constructionNanos", execution.constructionNanos));
        json.append(',').append(field("advanceNanos", execution.advanceNanos));
        json.append(',').append(field("totalEngineNanos", execution.totalEngineNanos()));
        json.append(',').append(field("resolvedContacts", stats.resolvedContacts));
        json.append(',').append(field("toiQueries", stats.toiQueries));
        json.append(',').append(field("candidateChecks", stats.candidateChecks));
        json.append(',').append(field("queuePushes", stats.queuePushes));
        json.append(',').append(field("queuePops", stats.queuePops));
        json.append(',').append(field("validEvents", stats.validEvents));
        json.append(',').append(field("staleEvents", stats.staleEvents));
        json.append(',').append(field("stalePercent", stats.stalePercent()));
        json.append(',').append(field("predictionRecomputations", stats.predictionRecomputations));
        json.append(',').append(field("dependencyInvalidations", stats.dependencyInvalidations));
        json.append(',').append(field("cadqFullReselections", stats.cadqFullReselections));
        json.append(',').append(field("cadqLocalPairRefreshes", stats.cadqLocalPairRefreshes));
        json.append(',').append(field("maxQueueSize", stats.maxQueueSize));
    }

    private static String summaryJson(
            String campaignId,
            Instant started,
            Instant finished,
            long scenarios,
            long measuredTrials,
            long correctnessFailures,
            long executionFailures) {
        return "{"
                + field("recordType", "summary") + ","
                + field("campaignId", campaignId) + ","
                + field("startedAt", started.toString()) + ","
                + field("finishedAt", finished.toString()) + ","
                + field("scenarios", scenarios) + ","
                + field("measuredTrials", measuredTrials) + ","
                + field("correctnessFailures", correctnessFailures) + ","
                + field("executionFailures", executionFailures) + ","
                + field("passed", correctnessFailures == 0 && executionFailures == 0)
                + "}";
    }

    private static Options options(Map<String, String> raw) {
        List<Workloads.Kind> workloads = parseWorkloads(raw.getOrDefault("workloads", "ALL"));
        List<Integer> ballCounts = parseInts(raw.getOrDefault("balls", "10,100"));
        long seedStart = Long.parseLong(raw.getOrDefault("seed-start", "1"));
        int seedCount = positiveInt(raw, "seeds", 3, true);
        int warmups = positiveInt(raw, "warmups", 1, false);
        int repetitions = positiveInt(raw, "repetitions", 3, true);
        double duration = positiveDouble(raw, "duration", 1.0);
        long maxEvents = positiveLong(raw, "events", 100_000, true);
        double restitution = Double.parseDouble(raw.getOrDefault("restitution", "1"));
        if (restitution < 0 || restitution > 1 || !Double.isFinite(restitution)) {
            throw new IllegalArgumentException("restitution must be in [0,1]");
        }
        ResolverKind resolver = ResolverKind.valueOf(raw.getOrDefault("resolver", "ITERATIVE").toUpperCase(Locale.ROOT));
        double toleranceMultiplier = positiveDouble(raw, "state-tolerance-multiplier", 10_000.0);
        String defaultName = "benchmarks/results/campaign-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
                + ".jsonl";
        Path out = Path.of(raw.getOrDefault("out", defaultName));
        boolean overwrite = Boolean.parseBoolean(raw.getOrDefault("overwrite", "false"));
        return new Options(
                workloads,
                ballCounts,
                seedStart,
                seedCount,
                warmups,
                repetitions,
                duration,
                maxEvents,
                restitution,
                resolver,
                toleranceMultiplier,
                out,
                overwrite);
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

    private static List<Workloads.Kind> parseWorkloads(String value) {
        if (value.equalsIgnoreCase("ALL")) return List.of(Workloads.Kind.values());
        List<Workloads.Kind> result = new ArrayList<>();
        for (String token : value.split(",")) {
            result.add(Workloads.Kind.valueOf(token.trim().toUpperCase(Locale.ROOT)));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("no workloads selected");
        return List.copyOf(result);
    }

    private static List<Integer> parseInts(String value) {
        List<Integer> result = new ArrayList<>();
        for (String token : value.split(",")) {
            int parsed = Integer.parseInt(token.trim());
            if (parsed <= 0) throw new IllegalArgumentException("ball counts must be positive");
            result.add(parsed);
        }
        if (result.isEmpty()) throw new IllegalArgumentException("no ball counts selected");
        return List.copyOf(result);
    }

    private static int positiveInt(Map<String, String> raw, String key, int fallback, boolean strictlyPositive) {
        int value = Integer.parseInt(raw.getOrDefault(key, Integer.toString(fallback)));
        if (strictlyPositive ? value <= 0 : value < 0) throw new IllegalArgumentException("invalid --" + key);
        return value;
    }

    private static long positiveLong(Map<String, String> raw, String key, long fallback, boolean strictlyPositive) {
        long value = Long.parseLong(raw.getOrDefault(key, Long.toString(fallback)));
        if (strictlyPositive ? value <= 0 : value < 0) throw new IllegalArgumentException("invalid --" + key);
        return value;
    }

    private static double positiveDouble(Map<String, String> raw, String key, double fallback) {
        double value = Double.parseDouble(raw.getOrDefault(key, Double.toString(fallback)));
        if (!(value > 0) || !Double.isFinite(value)) throw new IllegalArgumentException("invalid --" + key);
        return value;
    }

    private static String commitIdentity() {
        String property = System.getProperty("bouncingballs.commit");
        if (property != null && !property.isBlank()) return property;
        String environment = System.getenv("GIT_COMMIT");
        return environment == null || environment.isBlank() ? "unknown" : environment;
    }

    private static String field(String key, String value) {
        return quote(key) + ":" + quote(value == null ? "" : value);
    }

    private static String field(String key, long value) {
        return quote(key) + ":" + value;
    }

    private static String field(String key, int value) {
        return quote(key) + ":" + value;
    }

    private static String field(String key, double value) {
        return quote(key) + ":" + (Double.isFinite(value) ? Double.toString(value) : quote(Double.toString(value)));
    }

    private static String field(String key, boolean value) {
        return quote(key) + ":" + value;
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static void help() {
        System.out.println("""
                Usage: CampaignCli [options]
                  --workloads ALL|CSV                 default ALL
                  --balls CSV                         default 10,100
                  --seed-start N                      default 1
                  --seeds N                           default 3
                  --warmups N                         default 1
                  --repetitions N                     default 3
                  --duration SECONDS                  default 1
                  --events N                          default 100000
                  --resolver ITERATIVE|DIRECT|SEQUENTIAL
                  --restitution X                     default 1
                  --state-tolerance-multiplier X      default 10000
                  --out PATH                          default timestamped benchmarks/results/*.jsonl
                  --overwrite                         permit replacing an existing output file

                The campaign compares GLOBAL_EVENT_QUEUE and COMPUTE_AHEAD_DEPENDENCY_QUEUE against an
                ALL_PAIRS_CCD final-state oracle for every measured trial. Raw timing and mechanism counters are
                emitted as JSONL. Construction/rebuild and advance timings are recorded separately.
                """);
    }

    private static final class LineSink implements AutoCloseable {
        private final BufferedWriter writer;

        private LineSink(BufferedWriter writer) {
            this.writer = writer;
        }

        static LineSink open(Path path, boolean overwrite) throws IOException {
            Path absolute = path.toAbsolutePath();
            Path parent = absolute.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (Files.exists(absolute) && !overwrite) {
                throw new FileAlreadyExistsException(absolute.toString(), null, "use --overwrite or choose a new output path");
            }
            BufferedWriter writer = Files.newBufferedWriter(
                    absolute,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return new LineSink(writer);
        }

        void write(String line) throws IOException {
            writer.write(line);
            writer.newLine();
            writer.flush();
            System.out.println(line);
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
