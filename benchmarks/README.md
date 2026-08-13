# Empirical campaign protocol

This directory documents how scheduler claims are tested. Raw result files belong in `benchmarks/results/` and are intentionally ignored by Git so machine-local measurements are not silently promoted to repository facts.

## Research questions

The campaign keeps five questions separate:

1. **Physical correctness:** does a scheduler reproduce the same deduplicated physical collision history as the all-pairs reference?
2. **Numerical reproducibility:** how far can final state drift when schedulers follow the same physical history through different floating-point prediction paths?
3. **Mechanism:** how many exact pair TOI queries are quadratic versus quartic, how many wall TOIs, event materializations, temporal checks/prunes, queue operations, stale entries, reselections, and local refreshes occur?
4. **Initialization cost:** how much time is spent constructing the simulation and initial scheduler state?
5. **Advance cost:** how much wall-clock time is spent advancing the requested interval?

These questions must not be collapsed into one number. A faster run is not evidence of correctness; operation counts are not wall-clock measurements; and a smaller queue is not measured heap usage.

## Correctness reference

`CampaignCli` creates a fresh deterministic workload for every scheduler invocation. `ALL_PAIRS_CCD` supplies the reference trajectory.

Each measured run must match the reference on:

- resolved-contact count;
- deduplicated physical-contact count;
- non-empty simultaneous-contact batch count;
- order-sensitive physical-contact-history fingerprint, with contacts inside one simultaneous batch canonicalized.

Final state is compared in two bands:

- `stateToleranceMultiplier`: strict state-equivalence diagnostic;
- `driftToleranceMultiplier`: larger explicit ceiling for scheduler-dependent floating-point path drift.

A run passes only when physical history is identical and final state remains inside the drift ceiling. Failure of only the tighter comparison emits `numericalDriftWarning=true` rather than being hidden or mislabeled as a missed collision.

## Workload semantics

Randomized workloads are deterministic, finite, in-bounds, uniquely identified, and nonpenetrating at time zero. Deliberately constructed workloads may begin exactly touching.

Acceleration workloads must be interpreted in **relative** coordinates:

- `ACCELERATED` applies shared gravity to every body. Pair-relative acceleration is zero, so ball-ball TOI remains quadratic.
- `DIFFERENTIAL_ACCELERATION` adds a unique bounded horizontal acceleration per body. Every generated pair has nonzero relative acceleration and exercises quartic ball-ball TOI.

Changing workload generation changes the experiment population. Historical results must not be mixed with a new generator without labeling the provenance boundary.

## TOI accounting

Campaign schema **4** records:

- `toiQueries`;
- `pairToiQueries`;
- `quadraticPairToiQueries`;
- `quarticPairToiQueries`;
- `wallToiQueries`;
- temporal-bound checks/prunes and prune percentage;
- event materializations and scheduler mechanism counters.

Required accounting invariants are:

`toiQueries = pairToiQueries + wallToiQueries`

and

`pairToiQueries = quadraticPairToiQueries + quarticPairToiQueries`.

The degree classification is mechanical: equal stored acceleration vectors reduce pair motion to the quadratic branch; any nonzero relative acceleration gives a positive quartic coefficient.

## Timing definition

Workload generation happens before engine timing starts. Each run records:

- `constructionNanos`: `Simulation` construction including initial scheduler prediction/rebuild;
- `advanceNanos`: `Simulation.advance(...)` only;
- `totalEngineNanos = constructionNanos + advanceNanos`.

Campaigns use configurable warmups and rotate scheduler execution order. These are whole-program JVM timings, not JMH microbenchmarks; small differences should not be generalized casually.

## Running a campaign

```bash
mvn test
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

The Maven exec entry point is property-backed in `pom.xml`; `-Dexec.mainClass=...` must select the requested runner.

Temporal pruning is enabled by default and can be disabled for controlled A/B research:

```bash
-Dbouncingballs.cadqTemporalPruning=false
```

The output starts with an environment/provenance record, followed by reference/trial records and a final summary.

## Comparing two campaigns

`compare_campaigns.py` matches exact `(workload, requestedBalls, seed, repetition)` observations:

```bash
python3 benchmarks/compare_campaigns.py \
  benchmarks/results/baseline.jsonl \
  benchmarks/results/candidate.jsonl \
  --balls 100 --bootstrap 20000 --seed 42
```

For each key it first forms the within-campaign `CADQ/GLOBAL` ratio, then compares candidate and baseline log-ratios. The aggregate is a geometric mean with deterministic non-parametric bootstrap interval.

This normalization reduces shared hosted-runner/JVM variation. It does not make different machines identical, so important conclusions still need replication.

## Diagnostic profiling

`CadqProfileCli` is deliberately separate from acceptance campaigns. It adds coarse `System.nanoTime()` probes for queue work, dependency discovery, full reselection, and local refresh, plus the same mechanism/TOI-degree counters.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CadqProfileCli \
  -Dexec.args="--workloads SPARSE_UNIFORM,DIFFERENTIAL_ACCELERATION --balls 100 --seeds 3 --warmups 1 --repetitions 5 --duration 1 --out benchmarks/results/cadq-profile.jsonl --overwrite"
```

Profiler timings are attribution evidence, not speedup evidence. Performance acceptance returns to uninstrumented campaigns or tighter same-JVM A/B experiments when process artifacts demand them.

## Accepted evidence sequence

### Canonical ownership

Canonical lower-id pair ownership removed the earlier near-2x CADQ pair-prediction duplication. Pair TOI counts became close to GLOBAL while CADQ retained a smaller event queue; timing still lagged, motivating bookkeeping work.

### Dense bookkeeping

For 105 matched 100-ball observations, dense primitive bookkeeping changed candidate/baseline factors to approximately:

- total: `0.9225`, bootstrap `0.869–0.978`;
- construction: `0.9589`, bootstrap `0.884–1.037` (parity compatible);
- advance: `0.9032`, bootstrap `0.855–0.954`.

### Profile-guided negative results

The first 100-ball profile placed about 48% of profiled scheduler time in full reselection and 38% in local refresh. Three plausible micro-optimizations then failed timing acceptance and were reverted:

| Experimental change | 100-ball total factor | 100-ball advance factor | Decision |
|---|---:|---:|---|
| retained buffer reuse | 1.038 (`0.974–1.107`) | 1.000 (`0.931–1.070`) | reverted |
| canonical local-owner traversal bound | 1.042 (`0.982–1.108`) | 1.001 (`0.942–1.067`) | reverted |
| deferred event materialization | 1.033 (`0.969–1.102`) | 0.979 (`0.905–1.051`) | reverted |

### Temporal pruning during advance

The larger enabled/disabled process replication used seven workloads, 20/100 balls, five seeds, two warmups, ten repetitions, and one simulated second. Each side produced **2,100 measured trials with zero physical correctness failures and zero execution failures**.

At 100 balls:

- exact TOI median `7,556 -> 6,754` (**-10.6%**);
- normalized advance factor `0.748` (`0.724–0.772`);
- normalized total factor `0.963` (`0.935–0.991`).

A same-JVM interleaved A/B then isolated fixed process/JIT artifacts:

| Balls | Construction | Advance | Total |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

### Temporal pruning during construction

The next candidate applied the already-accepted proof during initial owner selection. Accepted master (advance-only pruning) and candidate (construction+advance pruning) were checked out side-by-side on the same runner.

| Balls | Construction factor | Total factor | Advance factor |
|---:|---:|---:|---:|
| 100 | **0.737** (`0.642–0.849`) | **0.789** (`0.695–0.894`) | 1.015 (`0.905–1.134`) |
| 300 | **0.565** (`0.512–0.619`) | **0.665** (`0.611–0.718`) | 0.953 (`0.819–1.118`) |
| 1000 | **0.438** (`0.340–0.578`) | **0.576** (`0.476–0.695`) | 0.987 (`0.903–1.061`) |

Normalized construction therefore improved approximately **26.3%, 43.5%, and 56.2%**. At 1000 bodies, median CADQ exact TOI queries fell **538,147 -> 152,575 (-71.6%)**. The 1000-body population contains only five matched observations, so it is scale evidence rather than a precise general estimate.

All four scale campaign summaries had **0 physical correctness failures and 0 execution failures**.

Valid evidence: run `31686901547`, artifact `9175894829`, digest `sha256:99b15429e13ff5eb2546ae823ba51d82f169b3225a16aa1aff657de34acb0d1c`.

An earlier attempt is deliberately excluded. The baseline checkout had not been compiled and `tee` masked Maven failure without `pipefail`. The hardened rerun compiled both checkouts, enabled `pipefail`, required the exact four expected JSONL files, and enforced each summary. This failure is part of the provenance record, not part of the performance result.

### True-quartic workload validation

A separate campaign checked the new TOI-degree counters and acceleration semantics:

- `ACCELERATED`, `DIFFERENTIAL_ACCELERATION`;
- 20/100 balls;
- three seeds;
- one warmup;
- three repetitions;
- all three continuous schedulers;
- **108 measured trials**.

It produced **0 physical correctness failures, 0 execution failures, 0 numerical-drift warnings** and enforced the TOI accounting invariants on every trial.

At 100 balls:

- shared-gravity all-pairs: 19,800 pair queries, all quadratic;
- shared-gravity CADQ: median 1,839 pair queries, all quadratic, 3,610 temporal prunes;
- differential-acceleration all-pairs: 19,800 pair queries, all quartic;
- differential-acceleration CADQ: median 2,351 pair queries, all quartic, 3,171 temporal prunes.

This is mechanism/correctness evidence that temporal pruning avoids genuine quartic solves. It is not yet a separate timing speedup claim for the new workload.

Validation: run `31687633334`, artifact `9176054588`, digest `sha256:458939724ec33bcb270c9d01460062796370387d3722f7c111f2757a09a4dd03`.

## Interpretation rules

Before making a performance claim:

- require zero physical correctness failures;
- preserve raw JSONL/evidence artifacts;
- report numerical-drift warnings instead of suppressing them;
- report workload, ball count, seed set, resolver, duration/event budget, commit, JVM, OS, and hardware context;
- separate construction, advance, and total timing;
- pair candidate/baseline observations when possible;
- if a supposedly unchanged phase moves materially, treat that as a measurement diagnostic and tighten the experiment;
- report operation and TOI-degree counters alongside timing;
- include adversarial workloads;
- treat `maxQueueSize` only as a structural memory proxy;
- treat opt-in phase timings only as diagnostic attribution;
- repeat important conclusions on other machines/JVMs before calling them general.

Allocation/heap profiling, cross-machine orchestration, JMH/JFR integration, and hardware counters belong to later milestones or shared benchmark infrastructure. This repository should preserve collision-specific hypotheses, correctness semantics, mechanism counters, and falsification results.
