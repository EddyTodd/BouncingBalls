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

## Campaign sequence and current evidence

The current bounded research matrix uses:

- Ubuntu 24.04 GitHub-hosted runner;
- Temurin Java 17;
- seven randomized workload families;
- 20 and 100 balls;
- three seeds;
- one warmup;
- five measured repetitions;
- one simulated second.

Each execution produces 42 scenarios and 630 measured trials. The accepted canonical-ownership and dense-bookkeeping implementations both produced:

- physical correctness failures: `0`;
- execution failures: `0`;
- strict numerical-drift warnings: `30`;
- campaign passed: `true`.

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

The accepted dense version replaces hot object-keyed bookkeeping with:

- a body array sorted once by stable unique id;
- dense simulation-local owner slots;
- array-backed retained event/tie sets;
- `BitSet` reverse-dependency sets;
- direct `CollisionEvent` heap entries;
- a primitive open-addressed id-to-slot table.

The implementation does **not** require ids themselves to be dense: regression coverage uses negative, sparse, and input-unsorted ids.

Using `compare_campaigns.py` semantics on the 105 matched 100-ball observations gives:

| Metric | Canonical CADQ/GLOBAL | Dense CADQ/GLOBAL | Candidate/baseline factor | Relative change |
|---|---:|---:|---:|---:|
| `totalEngineNanos` | 1.1477 | 1.0588 | 0.9225 | **-7.7%** |
| `constructionNanos` | 1.0328 | 0.9903 | 0.9589 | -4.1% |
| `advanceNanos` | 1.3539 | 1.2228 | 0.9032 | **-9.7%** |

With 20,000 deterministic bootstrap resamples, the 100-ball candidate/baseline confidence intervals were approximately:

- total engine: `0.869–0.978`;
- construction: `0.884–1.037`;
- advance: `0.855–0.954`.

Construction therefore should be described as roughly parity on this evidence, not a proven win. Total and advance ratios support the dense representation for this campaign population.

The accepted result was reached through two falsified intermediate ideas rather than post-hoc cleanup:

1. **Dense wrappers.** The first array/`BitSet` rewrite added per-event wrapper records and boxed identity lookup. It improved portions of `advance()` but did not improve total-engine time. That design was replaced with direct heap events and primitive lookup.
2. **Lazy refresh/reused masks.** A later micro-optimization removed no-op tie-set copies and per-batch mask allocations. Two full 630-trial runs retained correctness and improved advance-time point estimates, but neither demonstrated a reproducible total-engine improvement over the already accepted dense primitive variant. It was reverted rather than merged as an assumed optimization.

The raw JSONL for these research runs was preserved as GitHub Actions artifacts during the branch experiments and is intentionally not checked into the repository as a permanent machine-independent benchmark conclusion.

## Interpretation rules

Before making a performance claim:

- require zero physical correctness failures;
- preserve raw JSONL;
- report numerical-drift warnings rather than suppressing them;
- report workload, ball count, seed set, resolver, restitution, duration, event limit, commit, JVM, OS, and hardware context;
- analyze construction, advance, and total engine time separately;
- compare campaigns on matched workload/seed/repetition observations when possible;
- report operation counters alongside timing;
- include adversarial workloads even when they make the proposed optimization lose;
- treat `maxQueueSize` as a structural memory proxy only, not measured heap allocation;
- repeat important conclusions on another machine/JVM before describing them as general.

Allocation/heap profiling, cross-machine campaign orchestration, JMH/JFR integration, and hardware counters belong to later milestones or the shared benchmark infrastructure. This repository should preserve the collision-specific hypotheses, correctness semantics, and mechanism counters even if generic benchmarking machinery moves elsewhere.
