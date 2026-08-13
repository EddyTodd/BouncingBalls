# Bouncing Balls Laboratory

Java 17 engine and research harness for frictionless 2D circular rigid bodies. The engine is independent of Swing: it predicts time of impact (TOI), schedules events, batches simultaneous contacts into islands, and resolves impulses. `legacy/` preserves the 2022 Swing prototype unchanged.

## Build and run

Requirements: Java 17 and Maven 3.9+.

```bash
mvn test
mvn exec:java -Dexec.args="--algorithm GLOBAL_EVENT_QUEUE --workload NEWTON_CRADLE --balls 5 --duration 1"
mvn exec:java -Dexec.mainClass=io.github.eddytodd.bouncingballs.demo.SwingDemo
```

Use `--list` to list modes. The single-run CLI accepts `--algorithm`, `--resolver`, `--workload`, `--balls`, `--seed`, `--restitution`, `--duration`, `--events`, `--step`, and `--out`.

Examples:

```text
--algorithm ALL_PAIRS_CCD --resolver DIRECT --workload SYMMETRIC_IMPACT --duration 2
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload ADVERSARIAL_INVALIDATION --balls 100 --out benchmarks/results/cadq.jsonl
--algorithm DISCRETE_BASELINE --step .01 --workload HIGH_VELOCITY --balls 100
--algorithm GLOBAL_EVENT_QUEUE --workload ACCELERATED --restitution .8 --balls 100
```

Single-run output separates workload generation, simulation/scheduler construction, and `advance()` timing. `totalEngineNanos` includes scheduler initialization plus simulation advance; workload generation is excluded from engine comparisons.

## Differential research campaign

`CampaignCli` regenerates the same seeded initial state for every scheduler, uses `ALL_PAIRS_CCD` as a reference, performs configurable warmups and interleaved repetitions, and emits raw JSONL evidence.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

Correctness is structural as well as numerical. Runs record a deterministic physical contact-history fingerprint. A measured scheduler must reproduce the reference collision history and remain inside an explicit final-state drift ceiling. A tighter state comparison remains visible separately as `numericalDriftWarning`, so floating-point path dependence is not mislabeled as a missed collision or silently ignored.

The bounded research matrix used during the current CADQ milestones contains 42 scenarios and 630 measured trials. Accepted implementations have **0 physical correctness failures and 0 execution failures**. Thirty strict numerical-drift warnings remain reproducible in high-speed/wall-heavy 100-ball cases whose physical collision histories are identical. Larger replication campaigns are documented with their own sample counts rather than being mixed into this baseline population.

Campaign schema 3 records CADQ temporal-bound checks, successful prunes, prune percentage, event materializations, and whether temporal pruning was enabled. The accepted feature can be disabled for A/B research with:

```bash
-Dbouncingballs.cadqTemporalPruning=false
```

Campaign-to-campaign timing comparisons can be reproduced without third-party Python packages:

```bash
python3 benchmarks/compare_campaigns.py \
  benchmarks/results/baseline.jsonl \
  benchmarks/results/candidate.jsonl \
  --balls 100
```

The comparison first forms matched CADQ/GLOBAL ratios for each workload/seed/repetition and then bootstraps the candidate-vs-baseline log-ratio change. This reduces hosted-runner/JVM noise but does not replace cross-machine replication.

See [`benchmarks/README.md`](benchmarks/README.md) for the campaign protocol and [`docs/COLLISION_ALGORITHM_RESEARCH.md`](docs/COLLISION_ALGORITHM_RESEARCH.md) for the algorithmic evidence and interpretation.

## Implemented modes

| Mode | Purpose | Caveat |
|---|---|---|
| `DISCRETE_BASELINE` | fixed-step overlap control | tunnels by design |
| `ALL_PAIRS_CCD` | complete rebuild correctness reference | quadratic prediction work |
| `GLOBAL_EVENT_QUEUE` | generation-validated global heap | stale entries and queue growth under invalidation |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | canonical ownership, dense dependency tracking, local invalidation, conservative temporal pair pruning | worst-case invalidation and candidate work can still approach a rebuild |

Resolvers are `SEQUENTIAL`, deterministic pairwise impulses; `ITERATIVE`, symmetric projected Gauss-Seidel; and `DIRECT`, a coupled normal-impulse linear solve with iterative fallback for singular or negative-impulse systems.

## CADQ status

CADQ has evolved through measured falsification rather than assumed optimization:

1. an initial correctness safeguard fully reselected every owner and empirically destroyed the intended advantage;
2. dependency-local invalidation removed that safeguard and preserved simultaneous-contact tie sets;
3. the first serious campaign showed CADQ still performed almost twice as many TOI queries as the global heap;
4. canonical lower-id ownership reduced each unordered ball pair to one prediction and brought CADQ TOI work close to GLOBAL;
5. dense simulation-local slots, array-backed retained sets, `BitSet` reverse dependencies, direct heap events, and a primitive id-to-slot table reduced the remaining bookkeeping penalty;
6. advance-phase profiling tested three smaller bookkeeping/allocation hypotheses, none of which produced a reproducible target-metric improvement, so none was retained;
7. a conservative temporal reachability bound then attacked the profiled candidate-selection work directly and produced a reproducible reduction in exact TOI work and wall-clock time.

The dense representation deliberately supports arbitrary unique `int` body ids: ids need not be contiguous, positive, or input-sorted. `Simulation` requires uniqueness because stable ids define canonical pair ownership.

### Dense-bookkeeping result

The dense candidate was evaluated with the same seven workload families, 20/100 balls, three seeds, one warmup, five measured repetitions, and one simulated second as the canonical-ownership baseline. Both campaigns passed all 630 physical-correctness trials.

For the 105 matched 100-ball workload/seed/repetition observations, the geometric-mean paired CADQ/GLOBAL ratios changed as follows:

| Metric | Canonical hash bookkeeping | Dense primitive bookkeeping | Relative change |
|---|---:|---:|---:|
| total engine | 1.148 | 1.059 | **-7.7%** |
| construction | 1.033 | 0.990 | -4.1% (not statistically conclusive) |
| advance | 1.354 | 1.223 | **-9.7%** |

A deterministic 20,000-resample bootstrap on the matched ratio changes places the 100-ball total-engine factor at approximately `0.869–0.978` and the advance factor at `0.855–0.954`. The result therefore supports the dense representation on this hosted-runner experiment. It does **not** establish a universal machine-independent speedup.

### Advance-phase profiling and negative results

`CadqProfileCli` adds opt-in coarse timers and mechanism counters without changing normal benchmark behavior:

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CadqProfileCli \
  -Dexec.args="--balls 100 --seeds 3 --warmups 1 --repetitions 5 --duration 1 --out benchmarks/results/cadq-profile.jsonl --overwrite"
```

Profiling uses `System.nanoTime()` probes and is therefore diagnostic attribution, not an uninstrumented performance benchmark. In the first 105-trial 100-ball profile, full reselection plus local changed-pair refresh accounted for roughly **86% of the profiled scheduler regions**, while queue validation plus dependency discovery accounted for only about **8%**.

That evidence motivated three concrete experiments: reusable retained-owner buffers, canonical bounds on local-owner traversal, and deferred `CollisionEvent` materialization. All three preserved physical correctness across complete 630-trial campaigns, but their matched 100-ball bootstrap intervals for total and advance time all crossed `1`. They were reverted rather than merged as assumed optimizations.

See [`docs/CADQ_ADVANCE_PROFILE.md`](docs/CADQ_ADVANCE_PROFILE.md) for the phase attribution and falsification intervals.

### Conservative temporal-pruning result

`TemporalReachability` uses a conservative upper bound on relative displacement over an owner's already-known event horizon. If the circles cannot possibly close their current separation by that time, CADQ skips the exact pair TOI. L1 velocity/acceleration norms, numerical slack, tie-time slack, and fail-open handling make the bound conservative; it is a broad-phase proof, not a heuristic distance cutoff.

The larger process-level replication used seven workloads, 20/100 balls, five seeds, two warmups, and ten measured repetitions. Both enabled and disabled campaigns produced **2,100 measured trials with zero physical correctness failures and zero execution failures**. At 100 balls, median exact TOI work changed from `7,556` to `6,754` (**-10.6%**) and normalized `advance()` improved about **25.2%** with a bootstrap factor interval of `0.724–0.772`.

Because sequential process runs showed impossible construction-time shifts even though pruning is disabled during construction, acceptance used an additional same-JVM interleaved A/B: 700 adjacent enabled/disabled pairs per ball count with alternating order and per-pair physics equivalence checks.

| Ball count | Construction | Advance | Total engine |
|---:|---:|---:|---:|
| 20 | parity: `0.982–1.009` | **-8.2%**, factor `0.897–0.940` | **-3.3%**, factor `0.953–0.980` |
| 100 | parity: `0.995–1.016` | **-26.7%**, factor `0.721–0.746` | **-9.5%**, factor `0.895–0.915` |

At 100 balls the advance factor was `0.737` when pruning ran first and `0.730` when it ran second, providing evidence against execution-order bias. These are still GitHub-hosted Ubuntu/Temurin observations, not universal hardware claims.

See [`docs/CADQ_TEMPORAL_PRUNING.md`](docs/CADQ_TEMPORAL_PRUNING.md) for the proof, mechanism counters, all three evidence passes, and next hypotheses.

The motion model is exact constant velocity or constant acceleration between trajectory changes. Constant-acceleration circle contact is quartic in time; the implementation isolates real roots by recursively partitioning at derivative roots and bisecting rather than relying on a fragile closed-form quartic implementation.

Public API: create `Ball`s with unique ids, construct `Simulation(balls, bounds, config)`, call `advance(seconds, maxEvents)`, then read `balls()` and `stats()`.

## Structure

- `src/main/java/.../core` — bodies, numerical policy, TOI, conservative temporal reachability, event simulation
- `scheduler` — CCD scheduling strategies
- `resolver` — simultaneous-island strategies
- `research` — canonical differential state oracle
- `cli` — seeded workloads, single-run experiments, differential campaigns, and opt-in CADQ profiling
- `benchmarks` — empirical protocol and paired campaign comparison; machine-local raw results are ignored by Git
- `demo` — optional Swing state consumer
- `legacy` — preserved 2022 implementation
- `docs` — methodology, invariants, evidence, falsification logs, and limitations

This is deliberately balls-only: no rotation, friction, polygons, or 3D. Those extensions should not obscure the current objective of understanding and empirically improving collision scheduling.
