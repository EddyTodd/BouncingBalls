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
--algorithm SWEPT_BVH_CCD --workload CLUSTERED --balls 300
--algorithm SWEPT_UNIFORM_GRID_CCD --workload DENSE_UNIFORM --balls 1000
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
| `SWEEP_AND_PRUNE_CCD` | conservative swept-interval broad phase + rebuild | very cheap candidate construction; strong clustered behavior |
| `SWEPT_BVH_CCD` | conservative swept-AABB hierarchy + rebuild | same exact candidates as SAP; extra hierarchy build/traversal cost |
| `SWEPT_UNIFORM_GRID_CCD` | density-derived grid over conservative swept AABBs + rebuild | scales well in ordinary populations; duplicate bucket work under clustering |
| `GLOBAL_EVENT_QUEUE` | generation-validated global heap | incremental prediction with stale entries and queue growth |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | retained canonical owner events + reverse dependencies | expensive setup, highly optimized incremental invalidation |

The repository intentionally keeps multiple architectures, including valid approaches that lose measured comparisons. Current evidence shows real workload, simulation-horizon, and scale crossovers: SAP/CADQ depends on workload and duration, rebuilt BVH does not beat SAP through 1000 bodies, and the density grid crosses SAP between the 300- and 1000-body tested populations while remaining worse on clustered/adversarial geometry. There is no universal scheduler winner claim.

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

`CampaignCli` regenerates identical deterministic initial states across its continuous validation set and uses `ALL_PAIRS_CCD` as the physical reference. The general campaign predates the newer standalone broad phases; SAP, BVH, and the uniform grid are covered by permanent all-pairs differential regressions plus dedicated research campaigns. Generalizing reusable campaign selection remains benchmark-harness work, not a correctness gap.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/validation.jsonl"
```

Candidate runs must preserve physical contact counts, simultaneous-batch count, deterministic contact-history fingerprint, and final state inside the explicit drift ceiling. A tighter state comparison remains visible separately so floating-point path dependence is not silently treated as missed physics.

Raw JSONL and hosted CI logs are research evidence, not universal benchmark results. Hosted-runner timing claims remain population-specific until independently replicated.

## Mechanism evidence

`SimulationStats` records exact-work and scheduler-mechanism counters, including:

- pair, wall, quadratic-pair, and quartic-pair TOI queries;
- event materializations and queue behavior;
- CADQ reselection, local refresh, dependency, and temporal-pruning work;
- SAP canonical pairs, X sweep overlap work, exact pair candidates, rebuilds, and fallbacks;
- swept-BVH canonical pairs, nodes built/depth, node visits, exact pair candidates, rebuilds, and fallbacks;
- uniform-grid memberships, occupied cells, bucket pair attempts, duplicate pairs, unique cell pairs, AABB rejects, exact candidates, max bucket occupancy, rebuilds, and fallbacks;
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

The failed CADQ grid is not evidence that standalone spatial grids are ineffective. It showed that an extra spatial filter was redundant with cheaper CADQ temporal proofs. The standalone uniform-grid scheduler later demonstrates a scale crossover against SAP when the grid *replaces* pair enumeration rather than redundantly filtering CADQ.

Detailed CADQ records are under `docs/CADQ_*.md`.

## Sweep-and-prune result

`SWEEP_AND_PRUNE_CCD` uses the earliest exact wall event as a conservative horizon. For each body, its constant-acceleration trajectory is enclosed over that horizon by radius-expanded X/Y intervals; X is swept and Y-disjoint pairs are rejected before exact TOI.

Against rebuild-all-pairs, a same-JVM 100/300-body campaign reduced exact SAP candidates to about **0.85% / 1.06% of canonical pairs** and produced total factors of about **0.092 / 0.033** in that hosted population.

The more important comparison timed SAP directly against GLOBAL and CADQ while using all-pairs only as an untimed physical oracle. Two complete runs reproduced the same qualitative result at 0.25 simulated seconds:

- SAP beats GLOBAL in total engine cost at 100 and 300 bodies;
- SAP beats CADQ at 100 bodies;
- SAP versus CADQ at 300 bodies is workload dependent;
- SAP construction is dramatically cheaper, while SAP advance is substantially more expensive because it rebuilds after trajectory changes.

Duration mapping then showed real SAP/CADQ crossover behavior, but simple single-feature rules did not explain it. See [`docs/SAP_CROSSOVER.md`](docs/SAP_CROSSOVER.md) and [`docs/SAP_CADQ_DURATION_CROSSOVER.md`](docs/SAP_CADQ_DURATION_CROSSOVER.md).

## Adaptive-selector result

The first evidence-driven adaptive-scheduler attempt was deliberately tested before any production selector was added. A continuous six-dimensional parametric workload manifold was introduced so a model could not memorize named benchmark categories.

A generic pre-run physics-feature ridge model improved aggregate regret only marginally over always choosing SAP and failed badly on a held-out high-speed design stratum: the worst held fold reached about **59.7% accuracy**, **1.287x geometric regret**, **3.08x p95 regret**, and **4.60x maximum regret**.

That gate failed. Adaptive scheduling is therefore paused rather than implemented speculatively. See [`docs/PARAMETRIC_SELECTOR_RESEARCH.md`](docs/PARAMETRIC_SELECTOR_RESEARCH.md).

## Swept-BVH result

`SWEPT_BVH_CCD` shares SAP's exact conservative horizon and swept-AABB implementation, then replaces sweep enumeration with a rebuilt binary hierarchy. Paired runs require equal exact candidate counts, equal pair-TOI counts, equal physical-contact history, and equivalent final state.

A naive allocated/sorted BVH was about **2.076x** slower than SAP over the combined 100/300-body population. After replacing it with reusable flat node arrays and mostly linear midpoint partitioning, the disadvantage fell to **1.222x**.

The optimized BVH still did not overtake SAP: at 1000 bodies it was **1.244x** slower overall and won **0/21** tested scenarios. The BVH remains as a correct architectural comparator and preserved negative result. This does not falsify persistent/dynamic trees or higher-dimensional BVHs. See [`docs/SWEPT_BVH_RESEARCH.md`](docs/SWEPT_BVH_RESEARCH.md).

## Swept uniform-grid result

`SWEPT_UNIFORM_GRID_CCD` consumes the same `SweptAabb` boxes as SAP/BVH and uses a parameter-free density rule:

`cellSize = sqrt(worldArea / bodyCount)`.

Packed primitive memberships, a reusable primitive pair set, and final exact AABB rejection avoid object-heavy bucket structures while explicitly measuring duplicate discovery work.

At 100/300 bodies the density grid was **1.057x** SAP overall but **0.887x** BVH. It moved from 1.128x SAP at 100 bodies to 0.991x at 300. A proposed envelope-mean cell refinement was rejected because it worsened the combined grid/SAP factor to **1.308x** by exploding membership/sort work.

At 1000 bodies, two independent hosted runs reproduced a scale crossover:

- grid/SAP **0.961x** and **0.944x**, each with 15/21 grid wins;
- grid/BVH **0.776x** and **0.768x**, each with 17/21 grid wins.

The workload split is stable: the grid wins every seed in sparse, dense, high-velocity, wall-dominated, and accelerated workloads, while SAP wins every clustered/adversarial seed. Clustered seed 1 can exceed 19 million bucket-pair attempts and 11 million duplicate attempts, explaining the grid's failure mode despite identical exact candidate sets.

This is evidence of a scale/workload crossover, not a universal grid victory. See [`docs/SWEPT_UNIFORM_GRID_RESEARCH.md`](docs/SWEPT_UNIFORM_GRID_RESEARCH.md).

## Current research direction

The next collision-detection milestone is a **persistent/dynamic AABB tree**. The rebuilt BVH experiment answered only the cost of reconstructing a hierarchy after every trajectory-changing batch. A dynamic tree should preserve topology and refit/reinsert only changed leaves, directly testing whether incremental spatial structure can recover hierarchy advantages without paying full rebuild cost.

The experiment should preserve the same conservative swept-AABB semantics where possible and measure tree insertions/reinsertions/refits/rotations, changed-leaf fraction, node visits, exact candidates, pair TOIs, construction versus advance cost, and allocation behavior. It should include the same sparse/dense/clustered/high-motion/adversarial workload set and scale through at least 1000 bodies when correctness permits.

Later high-value tracks include calendar/bucket event queues when queue work is measured as dominant, explicit allocation/GC profiling, stronger cross-machine replication, and simultaneous-contact resolver research.

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
