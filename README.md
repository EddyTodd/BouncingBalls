# Bouncing Balls Laboratory

Java 17 research engine for frictionless 2D circular rigid bodies. The project compares continuous collision-detection schedulers and simultaneous-contact resolvers through explicit mathematical invariants, differential correctness gates, mechanism counters, reproducible experiments, and preserved negative results.

The engine is independent of Swing. `legacy/` preserves the original 2022 demo unchanged.

## Build and run

Requirements: Java 17 and Maven 3.9+.

```bash
mvn test
mvn exec:java -Dexec.args="--algorithm GLOBAL_EVENT_QUEUE --workload NEWTON_CRADLE --balls 5 --duration 1"
mvn exec:java -Dexec.mainClass=io.github.eddytodd.bouncingballs.demo.SwingDemo
```

Use `--list` for available modes. The single-run CLI accepts scheduler, resolver, workload, body count, seed, restitution, duration, event limit, discrete step, and output path.

Examples:

```text
--algorithm ALL_PAIRS_CCD --resolver DIRECT --workload SYMMETRIC_IMPACT --duration 2
--algorithm SWEEP_AND_PRUNE_CCD --workload SPARSE_UNIFORM --balls 300
--algorithm GLOBAL_EVENT_QUEUE --workload ACCELERATED --restitution .8 --balls 100
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload DIFFERENTIAL_ACCELERATION --balls 100
--algorithm DISCRETE_BASELINE --step .01 --workload HIGH_VELOCITY --balls 100
```

Engine timing separates workload generation, scheduler construction, and `advance()`. `totalEngineNanos` is construction plus advance; workload generation is excluded from scheduler comparisons.

## Implemented scheduler architectures

| Mode | Architecture | Main tradeoff |
|---|---|---|
| `DISCRETE_BASELINE` | fixed-step overlap control | intentionally tunnels; non-CCD baseline |
| `ALL_PAIRS_CCD` | complete pair/wall rebuild after every trajectory change | simple correctness reference, quadratic pair enumeration |
| `SWEEP_AND_PRUNE_CCD` | conservative swept-interval broad phase + rebuild | very cheap candidate construction; repeated rebuild cost after events |
| `GLOBAL_EVENT_QUEUE` | generation-validated global heap | incremental prediction with stale entries and queue growth |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | retained canonical owner events + reverse dependencies | expensive setup, highly optimized incremental invalidation |

The repository intentionally keeps multiple architectures. Current evidence shows real workload and simulation-horizon crossovers; there is no universal scheduler winner.

## Simultaneous-contact resolvers

- `SEQUENTIAL` — deterministic pairwise impulse baseline.
- `ITERATIVE` — symmetric projected Gauss-Seidel.
- `DIRECT` — coupled normal-impulse linear solve with iterative fallback for singular or nonphysical systems.

Scheduler and resolver research are kept causally separate. Newton's cradle and larger simultaneous-contact graphs remain an independent research track.

## Motion model

Motion is piecewise exact constant velocity or constant acceleration between trajectory changes.

For relative state `r,v,a` and combined radius `R`, ball-ball contact solves

`|r + vt + 0.5at^2|^2 - R^2 = 0`.

Pair TOI is genuinely quartic only when **relative acceleration is nonzero**. Equal acceleration vectors cancel in relative coordinates.

- `ACCELERATED` applies shared gravity, so ball-ball pair equations remain quadratic.
- `DIFFERENTIAL_ACCELERATION` gives bodies distinct horizontal accelerations and exercises genuine quartic pair TOI.

`PolynomialRoots` isolates real quartic roots through derivative-root partitioning and bisection rather than a fragile closed-form quartic formula.

## Differential correctness campaign

`CampaignCli` regenerates identical deterministic initial states across its continuous validation set and uses `ALL_PAIRS_CCD` as the physical reference. The current general campaign predates SAP; SAP is covered by permanent all-pairs differential regressions and the dedicated crossover evidence described below. Generalizing reusable campaign selection is part of the next benchmark-harness work.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/validation.jsonl"
```

Candidate runs must preserve physical contact counts, simultaneous-batch count, deterministic contact-history fingerprint, and final state inside the explicit drift ceiling. A tighter state comparison remains visible separately so floating-point path dependence is not silently treated as missed physics.

Raw JSONL is research evidence, not a universal benchmark result. Hosted-runner timing claims remain population-specific until independently replicated.

## Mechanism evidence

`SimulationStats` records exact-work and scheduler-mechanism counters, including:

- pair, wall, quadratic-pair, and quartic-pair TOI queries;
- event materializations and queue behavior;
- CADQ reselection, local refresh, dependency, and temporal-pruning work;
- SAP canonical pairs, X sweep overlap work, exact pair candidates, rebuilds, and fallbacks;
- deterministic physical contact history.

Mechanism counters explain why timing changed; uninstrumented timing remains the performance acceptance gate. `maxQueueSize` is a structural proxy, not an allocation/heap measurement.

## CADQ research status

CADQ has been optimized through measured acceptance and falsification rather than intuition.

Major accepted steps include:

1. dependency-local invalidation;
2. canonical ownership of each unordered pair;
3. dense slot/array/bitset bookkeeping;
4. conservative radial temporal pruning;
5. the same pruning during construction;
6. axis-separable temporal pruning;
7. a canonical local-owner traversal bound that became useful only after the surrounding cost structure changed.

Important rejected or falsified ideas include:

- reusable retained-owner buffers as a meaningful timing win at the earlier cost structure;
- deferred event materialization at the earlier cost structure;
- a swept uniform grid layered in front of CADQ temporal pruning;
- retained-target candidate ordering;
- analytical lower-bound top-k candidate probes.

The failed swept grid is not evidence that spatial broad phases are generally ineffective. It showed that an extra spatial filter was redundant with cheaper CADQ temporal proofs. That distinction directly motivated the standalone SAP scheduler.

Detailed CADQ records are under `docs/CADQ_*.md`.

## Sweep-and-prune result

`SWEEP_AND_PRUNE_CCD` uses the earliest exact wall event as a conservative horizon. For each body, its constant-acceleration trajectory is enclosed over that horizon by radius-expanded X/Y intervals; X is swept and Y-disjoint pairs are rejected before exact TOI.

Against rebuild-all-pairs, a same-JVM 100/300-body campaign reduced exact SAP candidates to about **0.85% / 1.06% of canonical pairs** and produced total factors of about **0.092 / 0.033** in that hosted population.

The more important comparison timed SAP directly against GLOBAL and CADQ while using all-pairs only as an untimed physical oracle. Two complete runs reproduced the same qualitative result at 0.25 simulated seconds:

- SAP beats GLOBAL in total engine cost at 100 and 300 bodies;
- SAP beats CADQ at 100 bodies;
- SAP versus CADQ at 300 bodies is workload dependent;
- SAP construction is dramatically cheaper, while SAP advance is substantially more expensive because it rebuilds after trajectory changes.

This creates a concrete scheduler-selection research problem rather than a single-winner benchmark. See [`docs/SAP_CROSSOVER.md`](docs/SAP_CROSSOVER.md).

## Current research direction

The next scheduler milestone is to map the **SAP/CADQ crossover surface** before building an adaptive scheduler.

The campaign should vary:

- simulated duration / expected event count;
- body count;
- density and clustering;
- velocity scale;
- shared versus differential acceleration;
- wall-dominated versus pair-dominated motion.

The hypothesis is useful only if cheap pre-run or early-run features can predict the faster scheduler without first paying both schedulers' costs. If such a boundary is stable, an adaptive scheduler can then be compared with both fixed schedulers and an offline best-choice oracle.

Other important tracks remain standalone spatial hashing/BVH architectures, calendar/bucket event queues when queue work becomes a measured bottleneck, explicit allocation/GC profiling, larger/cross-machine replication, and simultaneous-contact resolver research.

For the full evidence history and current roadmap, see [`docs/COLLISION_ALGORITHM_RESEARCH.md`](docs/COLLISION_ALGORITHM_RESEARCH.md).

## Research controls

Accepted CADQ layers can be disabled independently for causal A/B work:

```bash
-Dbouncingballs.cadqTemporalPruning=false
-Dbouncingballs.cadqAxisTemporalPruning=false
-Dbouncingballs.cadqCanonicalLocalTraversal=false
```

These are JVM-startup research controls, not recommended production settings.

Campaign-to-campaign comparisons can be reproduced without third-party Python dependencies:

```bash
python3 benchmarks/compare_campaigns.py \
  benchmarks/results/baseline.jsonl \
  benchmarks/results/candidate.jsonl \
  --balls 100
```

## Public API

Create `Ball` objects with unique ids, construct `Simulation(balls, bounds, config)`, call `advance(seconds, maxEvents)`, then inspect `balls()` and `stats()`.

Public ids may be sparse, negative, or input-unsorted. CADQ maps them to dense internal slots and uses stable id ordering for canonical pair ownership.

## Repository structure

- `src/main/java/.../core` — bodies, numerical policy, TOI, simulation
- `scheduler` — CCD scheduler architectures
- `resolver` — simultaneous-contact resolvers
- `research` — deterministic differential state oracle
- `cli` — workloads, experiments, campaigns, profiling
- `benchmarks` — empirical protocol and comparison tooling
- `docs` — proofs, evidence, falsification records, roadmap
- `demo` — optional Swing consumer
- `legacy` — preserved 2022 implementation

The current scope is deliberately 2D balls. Rotation, friction, polygons, 3D, GPUs, and unrelated rigid-body features do not obscure the collision-scheduling research question.
