# Empirical campaign protocol

This directory documents how scheduler claims are tested. Raw result files belong in `benchmarks/results/` and are intentionally ignored by Git so machine-local measurements are not silently promoted to repository facts.

## Research questions

The current campaign keeps five questions separate:

1. **Physical correctness:** does a scheduler reproduce the same deduplicated physical collision history as the all-pairs reference?
2. **Numerical reproducibility:** how far can final coordinates drift when schedulers follow the same physical collision history through different floating-point TOI/rebuild paths?
3. **Mechanism:** how many TOI queries, queue operations, stale entries, CADQ reselections, and local refreshes occur?
4. **Initialization cost:** how much time is spent constructing the simulation and initial scheduler state?
5. **Advance cost:** how much wall-clock time is spent advancing the requested interval?

These questions must not be collapsed into one number. A faster run is not evidence of correctness, operation counts are not wall-clock measurements, and bitwise-like coordinate agreement is not the only meaningful definition of collision-scheduler equivalence.

## Correctness reference

`CampaignCli` creates a fresh deterministic workload for every scheduler invocation. `ALL_PAIRS_CCD` supplies the reference trajectory.

Each run records a physical contact-history fingerprint. A measured scheduler must match the reference on:

- resolved-contact count;
- deduplicated physical-contact count;
- non-empty simultaneous-contact batch count;
- order-sensitive contact-history fingerprint, with contact ordering inside one simultaneous batch canonicalized.

The final state is also compared in two bands:

- `stateToleranceMultiplier`: the strict state-equivalence diagnostic;
- `driftToleranceMultiplier`: a larger, explicit ceiling for scheduler-dependent floating-point path drift.

A run passes only when its physical history is identical and its final state remains inside the drift ceiling. If the physical history matches but the strict state comparison fails, the trial emits `numericalDriftWarning=true`. Warnings remain evidence; they are not converted into successes by hiding the final-state error.

The distinction was motivated by an actual campaign result: high-speed 100-ball runs from both GLOBAL and CADQ produced the same physical contact sequence as all-pairs but accumulated coordinate differences around `1e-8` to `1e-7`. A targeted regression reproduces those cases.

## Workload validity

Randomized workloads are rejection-sampled so bodies begin finite, inside the domain, with unique ids and without penetration. Constructed workloads such as `NEWTON_CRADLE` may begin exactly touching because that topology is intentional. The cradle domain expands with requested ball count.

Changing workload generation changes the experiment population. Historical measurements from older generators must not be mixed with current campaign data without labeling that provenance change.

## Timing definition

`CampaignCli` creates the deterministic workload before engine timing starts. Each measured campaign run records:

- `constructionNanos`: `Simulation` construction including initial scheduler prediction/rebuild work;
- `advanceNanos`: `Simulation.advance(...)` only;
- `totalEngineNanos`: `constructionNanos + advanceNanos`.

`LabCli` additionally records workload generation time, but workload generation remains excluded from scheduler comparisons.

Campaigns perform configurable warmups and rotate scheduler execution order across repetitions to reduce fixed-order/JIT bias. These are whole-program JVM timings, not JMH microbenchmarks. Do not interpret small timing differences as universal results.

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

The Maven exec entry point is deliberately property-backed in `pom.xml`; `-Dexec.mainClass=...` must select the requested runner rather than silently launching `LabCli`.

The output begins with an `environment` record containing JVM/OS/CPU-count/heap metadata and campaign configuration, followed by reference/trial records and a final summary.

## Current bounded campaign result

The first post-optimization hosted campaign used:

- Ubuntu 24.04 GitHub-hosted runner;
- Temurin Java 17;
- seven randomized workload families;
- 20 and 100 balls;
- three seeds;
- one warmup;
- five measured repetitions;
- one simulated second.

It produced 42 scenarios and 630 measured trials. After canonical CADQ pair ownership:

- physical correctness failures: `0`;
- execution failures: `0`;
- strict numerical-drift warnings: `30`;
- campaign passed: `true`.

Representative deterministic 100-ball operation counts:

| Workload | CADQ TOI | Global TOI | CADQ max queue | Global max queue |
|---|---:|---:|---:|---:|
| Accelerated | 6,276 | 6,277 | 103 | 249 |
| Sparse | 6,173 | 6,174 | 103 | 247 |
| Dense | 7,789 | 7,410 | 120 | 328 |
| High velocity | 15,772 | 15,238 | 133 | 372 |
| Wall dominated | 11,841 | 11,530 | 124 | 329 |
| Adversarial invalidation | 7,556 | 7,204 | 117 | 297 |

These counts show that canonical ownership eliminated the earlier near-2x CADQ pair-prediction duplication. The final campaign still found CADQ generally slower than GLOBAL on this runner despite similar TOI work, which shifts the next hypothesis toward bookkeeping/data-structure overhead rather than collision mathematics.

The raw campaign was preserved as a GitHub Actions artifact during the research branch run. It is intentionally not checked into the repository as a permanent benchmark conclusion.

## Interpretation rules

Before making a performance claim:

- require zero physical correctness failures;
- preserve raw JSONL;
- report numerical-drift warnings rather than suppressing them;
- report workload, ball count, seed set, resolver, restitution, duration, event limit, commit, JVM, OS, and hardware context;
- analyze construction, advance, and total engine time separately;
- report operation counters alongside timing;
- include adversarial workloads even when they make the proposed optimization lose;
- treat `maxQueueSize` as a structural memory proxy only, not measured heap allocation;
- repeat important conclusions on another machine/JVM before describing them as general.

Allocation/heap profiling, statistically rigorous aggregation, cross-machine campaign orchestration, JMH/JFR integration, and hardware counters belong to later milestones or the shared benchmark infrastructure. This repository should preserve the collision-specific hypotheses, correctness semantics, and mechanism counters even if generic benchmarking machinery moves elsewhere.
