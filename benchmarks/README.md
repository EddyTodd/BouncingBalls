# Empirical campaign protocol

This directory documents how scheduler claims are tested. Raw result files belong in `benchmarks/results/` and are intentionally ignored by Git so machine-local measurements are not silently promoted to repository facts.

## Research questions

The current campaign keeps five questions separate:

1. **Physical correctness:** does a scheduler reproduce the same deduplicated physical collision history as the all-pairs reference?
2. **Numerical reproducibility:** how far can final coordinates drift when schedulers follow the same physical collision history through different floating-point TOI/rebuild paths?
3. **Mechanism:** how many TOI queries, event materializations, temporal prunes, queue operations, stale entries, CADQ reselections, and local refreshes occur?
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

Campaign schema 3 records whether CADQ temporal pruning is enabled plus `cadqTemporalBoundChecks`, `cadqTemporalPrunes`, `cadqTemporalPrunePercent`, and `predictedEventMaterializations` alongside the existing timing/mechanism evidence. To reproduce the pre-pruning CADQ path:

```bash
-Dbouncingballs.cadqTemporalPruning=false
```

The output begins with an `environment` record containing JVM/OS/CPU-count/heap metadata and campaign configuration, followed by reference/trial records and a final summary.

## Comparing two campaigns

`compare_campaigns.py` provides the repository's reproducible comparison used for optimization decisions:

```bash
python3 benchmarks/compare_campaigns.py \
  benchmarks/results/baseline.jsonl \
  benchmarks/results/candidate.jsonl \
  --balls 100 \
  --bootstrap 20000 \
  --seed 42
```

For every `(workload, requestedBalls, seed, repetition)` shared by the files, the script first calculates the within-campaign `CADQ / GLOBAL` ratio. It then compares candidate and baseline ratios on the exact matched keys. The reported candidate-vs-baseline factor is the geometric mean of the log-ratio changes, with a deterministic non-parametric bootstrap interval.

This normalization is intentional: comparing raw nanoseconds from two different hosted runners is weaker because machine placement, JVM state, and ambient load can differ. Pairing against GLOBAL inside each campaign removes a large shared component of that noise. It does not remove all noise, so important claims still need replication.

A factor below `1` favors the candidate. The script refuses to analyze a campaign whose final correctness summary did not pass.

## CADQ phase profiling

`CadqProfileCli` is deliberately separate from `CampaignCli`:

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CadqProfileCli \
  -Dexec.args="--balls 100 --seeds 3 --warmups 1 --repetitions 5 --duration 1 --out benchmarks/results/cadq-profile.jsonl --overwrite"
```

The profiler enables coarse `System.nanoTime()` probes inside CADQ. It measures queue work, dependency discovery, full reselection, and local changed-pair refresh while also emitting mechanism counts. This is useful for **attribution**, but the probes perturb the code path. A profiler result may motivate a candidate; it cannot by itself establish that the candidate is faster.

Performance acceptance therefore returns to uninstrumented differential campaigns and, where process-order artifacts remain, a tighter interleaved A/B that directly compares the same scheduler mechanism.

## Campaign sequence and current evidence

The original bounded research matrix uses:

- Ubuntu 24.04 GitHub-hosted runner;
- Temurin Java 17;
- seven randomized workload families;
- 20 and 100 balls;
- three seeds;
- one warmup;
- five measured repetitions;
- one simulated second.

Each execution produces 42 scenarios and 630 measured trials. Accepted implementations in that population produced:

- physical correctness failures: `0`;
- execution failures: `0`;
- strict numerical-drift warnings: `30`;
- campaign passed: `true`.

Larger replications state their own populations and warning counts rather than being silently merged with this baseline.

### Canonical pair-ownership mechanism result

Representative deterministic 100-ball operation counts after canonical pair ownership:

| Workload | CADQ TOI | Global TOI | CADQ max queue | Global max queue |
|---|---:|---:|---:|---:|
| Accelerated | 6,276 | 6,277 | 103 | 249 |
| Sparse | 6,173 | 6,174 | 103 | 247 |
| Dense | 7,789 | 7,410 | 120 | 328 |
| High velocity | 15,772 | 15,238 | 133 | 372 |
| Wall dominated | 11,841 | 11,530 | 124 | 329 |
| Adversarial invalidation | 7,556 | 7,204 | 117 | 297 |

These counts show that canonical ownership eliminated the earlier near-2x CADQ pair-prediction duplication. Because CADQ was still slower than GLOBAL despite similar TOI work, the next hypothesis became bookkeeping/data-structure overhead.

### Dense-bookkeeping experiment

The accepted dense version replaces hot object-keyed bookkeeping with a sorted body array, dense owner slots, array-backed retained tie sets, `BitSet` reverse dependencies, direct heap events, and a primitive id-to-slot table. Public ids themselves need not be dense.

Using `compare_campaigns.py` semantics on the 105 matched 100-ball observations gives:

| Metric | Canonical CADQ/GLOBAL | Dense CADQ/GLOBAL | Candidate/baseline factor | Relative change |
|---|---:|---:|---:|---:|
| `totalEngineNanos` | 1.1477 | 1.0588 | 0.9225 | **-7.7%** |
| `constructionNanos` | 1.0328 | 0.9903 | 0.9589 | -4.1% |
| `advanceNanos` | 1.3539 | 1.2228 | 0.9032 | **-9.7%** |

With 20,000 deterministic bootstrap resamples, the 100-ball candidate/baseline intervals were approximately:

- total engine: `0.869–0.978`;
- construction: `0.884–1.037`;
- advance: `0.855–0.954`.

Construction therefore should be described as roughly parity on this evidence, not a proven win. Total and advance ratios support the dense representation for this campaign population.

### Advance-phase profile and falsification result

The first 105-trial 100-ball diagnostic profile placed approximately 48% of the profiled CADQ scheduler time in full reselection and 38% in local refresh. Queue work was about 5% and dependency discovery about 4%.

That profile motivated three complete candidate experiments. All three retained physical correctness but failed the uninstrumented target-metric acceptance test:

| Experimental change | 100-ball total factor (95% bootstrap) | 100-ball advance factor (95% bootstrap) | Decision |
|---|---:|---:|---|
| reuse retained owner buffers | 1.038 (`0.974–1.107`) | 1.000 (`0.931–1.070`) | reverted |
| bound local-owner traversal by canonical ownership | 1.042 (`0.982–1.108`) | 1.001 (`0.942–1.067`) | reverted |
| defer `CollisionEvent` materialization until selection | 1.033 (`0.969–1.102`) | 0.979 (`0.905–1.051`) | reverted |

Every interval spans `1`, so none supports a speed claim. Mechanism improvements alone are not enough to retain extra complexity. Detailed phase counts and interpretation are in [`../docs/CADQ_ADVANCE_PROFILE.md`](../docs/CADQ_ADVANCE_PROFILE.md).

### Conservative temporal-pruning experiment

The next hypothesis directly reduced exact candidate work. Once an owner has an exact earliest event horizon, a pair is sent through a conservative displacement bound before exact TOI. A pair can be rejected only when its current center separation exceeds the combined radius plus an upper bound on relative displacement through that horizon. L1 speed/acceleration norms, numerical slack, tie-time slack, and fail-open handling preserve the earliest-event invariant.

The larger process-level replication used seven workloads, 20/100 balls, five seeds, two warmups, and ten measured repetitions. Enabled and disabled campaigns each produced **2,100 measured scheduler trials**, with zero physical correctness failures and zero execution failures.

At 100 balls:

- median exact TOI queries: `7,556 -> 6,754` (**-10.6%**);
- normalized total-engine factor: **0.963** (`0.935–0.991`);
- normalized advance factor: **0.748** (`0.724–0.772`).

At 20 balls, exact TOI work fell only about 2%, while normalized advance still improved about 5.6% (`0.898–0.996`). Separate process campaigns disagreed on construction even though pruning is inactive during initial construction. That non-causal shift triggered a same-JVM interleaved A/B rather than being rationalized as a mechanism effect.

The interleaved experiment alternated enabled/disabled order every repetition and required physics equivalence for every adjacent pair. It produced **700 paired measurements per ball count**:

| Ball count | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

Construction is therefore consistent with parity, while the causal advance path and total engine time improve at both tested sizes. At 100 balls the advance factors were approximately `0.737` when pruning ran first and `0.730` when it ran second.

A 105-trial enabled profile observed a median 100-ball temporal-prune rate of about **49.6%**. Accelerated workloads reached about **78.5%**, which is especially valuable because those avoided candidates skip quartic root isolation.

Full proof and evidence are in [`../docs/CADQ_TEMPORAL_PRUNING.md`](../docs/CADQ_TEMPORAL_PRUNING.md).

The raw JSONL and temporary A/B data from research runs are preserved as GitHub Actions artifacts during branch experiments and are intentionally not checked into the repository as permanent machine-independent benchmark conclusions.

## Interpretation rules

Before making a performance claim:

- require zero physical correctness failures;
- preserve raw JSONL/evidence artifacts;
- report numerical-drift warnings rather than suppressing them;
- report workload, ball count, seed set, resolver, restitution, duration, event limit, commit, JVM, OS, and hardware context;
- analyze construction, advance, and total engine time separately;
- compare campaigns on matched workload/seed/repetition observations when possible;
- when a supposedly unchanged phase moves materially, treat it as a noise diagnostic and tighten the experiment rather than attributing it to the candidate;
- report operation counters alongside timing;
- include adversarial workloads even when they make the proposed optimization lose;
- treat `maxQueueSize` as a structural memory proxy only, not measured heap allocation;
- treat opt-in phase timings as diagnostic attribution rather than benchmark results;
- repeat important conclusions on another machine/JVM before describing them as general.

Allocation/heap profiling, cross-machine campaign orchestration, JMH/JFR integration, and hardware counters belong to later milestones or the shared benchmark infrastructure. This repository should preserve the collision-specific hypotheses, correctness semantics, mechanism counters, and falsification results even if generic benchmarking machinery moves elsewhere.
