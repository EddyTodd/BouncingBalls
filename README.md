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
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload DIFFERENTIAL_ACCELERATION --balls 100
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

The original bounded CADQ matrix contains 42 scenarios and 630 measured trials. Accepted implementations in that population have **0 physical correctness failures and 0 execution failures**; 30 strict numerical-drift warnings remain reproducible in high-speed/wall-heavy 100-ball cases whose physical collision histories are identical. Larger campaigns are documented with their own populations rather than being mixed into that baseline.

Campaign schema **4** records CADQ temporal-bound checks/prunes, event materializations, and exact TOI work split into `pairToiQueries`, `quadraticPairToiQueries`, `quarticPairToiQueries`, and `wallToiQueries`. Temporal pruning can be disabled for controlled A/B research with:

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
| `ALL_PAIRS_CCD` | complete rebuild correctness reference | quadratic pair enumeration cost |
| `GLOBAL_EVENT_QUEUE` | generation-validated global heap | stale entries and queue growth under invalidation |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | canonical ownership, dense dependency tracking, local invalidation, conservative temporal pruning | worst-case invalidation/candidate work can still approach a rebuild |

Resolvers are `SEQUENTIAL`, deterministic pairwise impulses; `ITERATIVE`, symmetric projected Gauss-Seidel; and `DIRECT`, a coupled normal-impulse linear solve with iterative fallback for singular or negative-impulse systems.

## Motion-model workloads

The exact pair equation depends on **relative** acceleration, not whether the world-frame trajectories are individually accelerated.

- `ACCELERATED` applies the same gravity vector to every body. That gravity cancels from ball-ball relative motion, so its pair TOI equations remain quadratic; wall trajectories are accelerated.
- `DIFFERENTIAL_ACCELERATION` adds deterministic body-specific horizontal acceleration while retaining gravity. Every generated pair therefore has nonzero relative acceleration and exercises the quartic pair-TOI path.

A dedicated 108-trial differential validation campaign checked both workload families across all three continuous schedulers: **0 physical correctness failures, 0 execution failures, and 0 numerical-drift warnings**. The accounting invariants also held exactly: shared-gravity trials produced no quartic pair queries, while differential-acceleration trials produced no quadratic pair queries.

## CADQ status

CADQ has evolved through measured falsification rather than assumed optimization:

1. an initial correctness safeguard fully reselected every owner and empirically destroyed the intended advantage;
2. dependency-local invalidation removed that safeguard and preserved simultaneous-contact tie sets;
3. the first serious campaign showed CADQ still performed almost twice as many TOI queries as the global heap;
4. canonical lower-id ownership reduced each unordered ball pair to one prediction and brought CADQ TOI work close to GLOBAL;
5. dense simulation-local slots, array-backed retained sets, `BitSet` reverse dependencies, direct heap events, and a primitive id-to-slot table reduced the remaining bookkeeping penalty;
6. advance-phase profiling tested three smaller bookkeeping/allocation hypotheses, none of which produced a reproducible target-metric improvement, so none was retained;
7. conservative temporal reachability then reduced exact pair work during `advance()` and produced a reproducible timing improvement;
8. after that causal result was established, the same proof was extended to **initial owner selection**, removing much of the quadratic exact-TOI construction work at larger N.

The dense representation deliberately supports arbitrary unique `int` body ids: ids need not be contiguous, positive, or input-sorted. `Simulation` requires uniqueness because stable ids define canonical pair ownership.

### Dense-bookkeeping result

For 105 matched 100-ball observations, the dense primitive implementation changed the paired CADQ/GLOBAL ratios as follows:

| Metric | Canonical hash bookkeeping | Dense primitive bookkeeping | Relative change |
|---|---:|---:|---:|
| total engine | 1.148 | 1.059 | **-7.7%** |
| construction | 1.033 | 0.990 | -4.1% (not statistically conclusive) |
| advance | 1.354 | 1.223 | **-9.7%** |

A deterministic 20,000-resample bootstrap placed the 100-ball total-engine factor at approximately `0.869–0.978` and the advance factor at `0.855–0.954`. This supports the dense representation for that hosted-runner population; it is not a machine-independent speedup claim.

### Advance-phase profiling and negative results

`CadqProfileCli` adds opt-in coarse timers and mechanism counters without changing normal benchmark behavior. The first 105-trial 100-ball profile attributed roughly **86% of the profiled scheduler regions** to full reselection plus local changed-pair refresh, while queue validation plus dependency discovery accounted for only about **8%**.

That evidence motivated three experiments—reusable retained-owner buffers, canonical bounds on local-owner traversal, and deferred `CollisionEvent` materialization. All preserved physical correctness, but their matched 100-ball bootstrap intervals for total and advance time crossed `1`. They were reverted rather than retained as assumed optimizations.

See [`docs/CADQ_ADVANCE_PROFILE.md`](docs/CADQ_ADVANCE_PROFILE.md) for the phase attribution and falsification intervals.

### Conservative temporal-pruning result

`TemporalReachability` uses a conservative upper bound on relative displacement over an owner's exact event horizon. If the circles cannot possibly close their current separation by that time, CADQ skips exact pair TOI. L1 velocity/acceleration norms, numerical slack, tie-time slack, and fail-open handling make the bound conservative; it is a broad-phase proof, not a heuristic distance cutoff.

The larger advance-phase replication used seven workloads, 20/100 balls, five seeds, two warmups, and ten measured repetitions. Enabled and disabled campaigns each produced **2,100 measured trials with zero physical correctness failures and zero execution failures**. At 100 balls, median exact TOI work changed from `7,556` to `6,754` (**-10.6%**) and normalized `advance()` improved about **25.2%** with factor interval `0.724–0.772`.

A same-JVM interleaved A/B then isolated process/JIT noise:

| Ball count | Construction | Advance | Total engine |
|---:|---:|---:|---:|
| 20 | parity: `0.982–1.009` | **-8.2%**, factor `0.897–0.940` | **-3.3%**, factor `0.953–0.980` |
| 100 | parity: `0.995–1.016` | **-26.7%**, factor `0.721–0.746` | **-9.5%**, factor `0.895–0.915` |

### Construction-pruning scale result

Once the advance mechanism was accepted, initial CADQ selection was allowed to use exactly the same wall-seeded conservative horizon. A side-by-side hosted-runner campaign compared accepted master (advance-only pruning) with construction+advance pruning. Both sides passed the all-pairs physical-history gate.

| Balls | Construction factor | Total-engine factor | Advance factor |
|---:|---:|---:|---:|
| 100 | **0.737** (`0.642–0.849`) | **0.789** (`0.695–0.894`) | 1.015 (`0.905–1.134`) |
| 300 | **0.565** (`0.512–0.619`) | **0.665** (`0.611–0.718`) | 0.953 (`0.819–1.118`) |
| 1000 | **0.438** (`0.340–0.578`) | **0.576** (`0.476–0.695`) | 0.987 (`0.903–1.061`) |

That is approximately **26.3%, 43.5%, and 56.2% lower normalized construction cost** at 100/300/1000 bodies, while the unchanged advance phase remains statistically at parity. At 1000 bodies, median CADQ exact TOI queries fell from **538,147 to 152,575 (-71.6%)**. The 1000-body comparison has only five matched observations, so it is scale evidence rather than a precise universal estimate.

The first attempt at this scale campaign was discarded: the master checkout had not been compiled and `tee` masked the Maven failure because the workflow lacked `pipefail`. The hardened rerun compiled both checkouts, used `set -o pipefail`, required all four expected JSONL files, and is the only scale run used for the result above.

See [`docs/CADQ_TEMPORAL_PRUNING.md`](docs/CADQ_TEMPORAL_PRUNING.md) for the proof, advance acceptance, construction-scaling evidence, artifact provenance, and next hypotheses.

## Motion model

Motion is piecewise exact constant velocity or constant acceleration between trajectory changes. Ball-ball contact is **quartic when relative acceleration is nonzero**. Equal acceleration vectors—uniform gravity is the important example—cancel from relative motion and reduce the pair equation to the constant-relative-velocity quadratic. `PolynomialRoots` isolates real quartic roots through derivative-root partitioning and bisection rather than a fragile closed-form quartic formula.

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
