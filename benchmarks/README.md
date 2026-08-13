# Empirical campaign protocol

This directory documents how scheduler claims are tested. Raw result files belong in `benchmarks/results/` and are intentionally ignored by Git so that machine-local measurements are not silently promoted to repository facts.

## Research questions

The current campaign is designed to answer four separate questions.

1. **Correctness:** do `GLOBAL_EVENT_QUEUE` and `COMPUTE_AHEAD_DEPENDENCY_QUEUE` end in the same physical state as `ALL_PAIRS_CCD` for the same deterministic initial state?
2. **Mechanism:** how many TOI queries, queue operations, stale events, CADQ full reselections, and CADQ local pair refreshes does each strategy perform?
3. **Initialization cost:** how much time is spent constructing the simulation and its initial scheduler state?
4. **Advance cost:** after initialization, how much wall-clock time is spent advancing the requested simulation interval?

These questions must not be collapsed into one number. Operation counts can demonstrate that an optimization mechanism is behaving as intended, but they are not a substitute for repeated timing measurements. Likewise, a faster run is not evidence of correctness.

## Correctness oracle

`CampaignCli` creates a fresh workload for every run using the same workload kind, requested ball count, seed, restitution, resolver, duration, and event limit. `ALL_PAIRS_CCD` is executed once as the reference for each scenario. Every measured scheduler run is compared against that reference using canonical body ordering and scale-aware tolerances derived from `NumericalPolicy`.

A campaign exits unsuccessfully if any measured run throws or if its final simulation time, position, or velocity state differs from the reference beyond the configured tolerance. Differential correctness therefore gates any performance interpretation.

The normal Maven test suite also contains a smaller multi-workload, multi-seed differential matrix. That test is a regression gate; the campaign is the larger evidence generator.

## Workload validity

Randomized workloads are rejection-sampled so bodies begin finite, inside the domain, and without penetration. This matters because an accidental initial overlap can turn a scheduler comparison into a test of zero-time recovery behavior rather than the intended collision-search mechanism.

Constructed workloads such as `NEWTON_CRADLE` may begin exactly touching because that contact topology is intentional. The cradle domain expands with requested ball count so large campaigns do not silently place bodies outside the box.

Changing workload generation changes the experiment population. Historical measurements produced before this sanitation pass remain historical baselines and must not be mixed with new campaign results without labeling the workload version difference.

## Timing definition

`CampaignCli` creates the deterministic workload before engine timing starts. Each measured campaign run records:

- `constructionNanos`: `Simulation` construction, including the scheduler's initial prediction/rebuild work;
- `advanceNanos`: `Simulation.advance(...)` only;
- `totalEngineNanos`: `constructionNanos + advanceNanos`.

The single-run `LabCli` additionally records `workloadGenerationNanos` so scenario-generation cost can be inspected, but that value is deliberately excluded from scheduler comparisons.

The older single-run CLI timed only `advance()`. That field was insufficient for fair comparisons because algorithms can move meaningful work into initialization. New evidence must use the split timing fields.

`CampaignCli` performs configurable warmups and then interleaves scheduler order across repetitions to reduce fixed-order/JIT bias. These are still whole-program JVM measurements, not JMH microbenchmarks. Claims about small timing differences should therefore wait for a later dedicated benchmarking layer.

## Running a campaign

From a clean checkout with Java 17+ and Maven 3.9+:

```bash
mvn test
mvn exec:java \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

For provenance, supply the exact commit when possible:

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

The output begins with an `environment` record containing JVM/OS/CPU-count/heap metadata and the campaign configuration, followed by reference and measured trial records and a final summary record.

## Interpretation rules

Do not publish a scheduler as faster because of one seed, one repetition, or one machine. Before making a performance claim:

- require zero differential correctness failures;
- preserve raw JSONL;
- report workload, ball count, seed set, resolver, restitution, duration, event limit, commit, JVM, OS, and hardware context;
- analyze construction, advance, and total engine time separately;
- report operation counters alongside timing so the mechanism can be inspected;
- include adversarial workloads even when they make the proposed optimization lose;
- treat `maxQueueSize` as a structural memory proxy only, not measured heap allocation;
- repeat important conclusions on another machine/JVM before describing them as general.

Actual allocation/heap profiling, statistically rigorous aggregation, cross-machine campaign orchestration, and JMH/JFR integration belong to later milestones or the shared benchmark infrastructure. This repository should preserve the collision-specific hypotheses, correctness oracle, and raw mechanism counters even if generic benchmarking machinery is later moved elsewhere.
