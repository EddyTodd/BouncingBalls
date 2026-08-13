# Collision algorithm research note

## Model and numerical policy

Bodies are circles with independent radius, mass, restitution, position, velocity, and piecewise-constant acceleration. For relative state `r,v,a`, ball-ball contact solves

`|r + vt + 0.5at^2|^2 - (R1+R2)^2 = 0`.

The equation is genuinely quartic when **relative acceleration is nonzero**. If the two acceleration vectors are equal, `a = 0` in relative coordinates and the pair equation reduces to the constant-relative-velocity quadratic. Uniform gravity is therefore accelerated world-space motion but quadratic ball-ball motion.

`PolynomialRoots` isolates real roots by recursively partitioning at derivative roots and bisecting with centralized scale-aware tolerances. The trajectory model is exact between trajectory changes; IEEE-754 root approximations are not bitwise exact mathematics.

## Scheduling strategies

`ALL_PAIRS_CCD` is the small-system correctness reference. It rebuilds every pair and wall prediction after every trajectory-changing batch.

`GLOBAL_EVENT_QUEUE` stores predicted events in one generation-validated heap. Changed bodies add replacement predictions; invalid old entries are discarded lazily. This avoids global rebuilding but can accumulate stale entries and queue growth.

`COMPUTE_AHEAD_DEPENDENCY_QUEUE` (CADQ) retains only each owner's currently relevant earliest prediction/tie set, tracks reverse dependencies, and recomputes the prediction graph invalidated by a trajectory change.

## CADQ evolution

The implementation has been advanced through falsifiable milestones rather than accumulated assumed optimizations:

1. **All-owner safeguard:** correct but empirically bad; one 100-ball adversarial smoke case produced 92,700 TOI queries, 900 reselections, and 88.5% stale pops.
2. **Local invalidation:** changed bodies and retained dependencies are fully reselected; unaffected owners test only changed bodies. Complete earliest-time tie sets preserve simultaneous contact graphs.
3. **Canonical pair ownership:** each unordered ball pair is owned only by the lower-id body. This nearly halved CADQ pair prediction work.
4. **Dense bookkeeping:** simulation-local slots, array-backed retained sets, `BitSet` reverse dependencies, direct heap events, and a primitive id-to-slot map reduced the remaining bookkeeping penalty.
5. **Advance profiling:** full reselection plus local refresh dominated the profiled scheduler regions. Three plausible micro-optimizations—buffer reuse, canonical local-loop bounds, and deferred event materialization—failed the timing acceptance gate and were reverted.
6. **Conservative radial temporal pruning during advance:** pairs proven unable to reach contact before the owner's current exact event skip exact TOI. This produced a reproducible advance/total improvement.
7. **Radial temporal pruning during construction:** once the advance proof was accepted, the same wall-seeded horizon was applied to initial owner selection. Construction benefit increased strongly with N through the tested 1000-body scale.
8. **Swept uniform spatial grid:** physically correct and geometrically aggressive, but empirically redundant with temporal pruning. Median exact TOI work did not fall; total engine time regressed strongly, so the grid was removed.
9. **Axis-separable temporal pruning:** the useful geometry exposed by the failed grid was reduced to two constant-time one-dimensional reachability inequalities. This eliminated substantial additional exact quadratic and quartic work and produced reproducible timing gains.

Public body ids remain arbitrary unique `int`s; dense local slots are internal. `Simulation` requires uniqueness because stable ids define canonical pair ownership.

## Current CADQ invariant

For each owner `u`:

- only pairs `u-v` with `u.id < v.id` are canonically owned;
- all four wall predictions are owned by `u`;
- the complete earliest-time tie set is retained;
- reverse dependencies identify owners whose retained predictions reference each body;
- an exact pair solve may be skipped only after conservative temporal proofs establish that the pair cannot beat or tie an exact owner horizon.

At construction and full reselection, wall TOIs are evaluated first. Their earliest exact event usually establishes a finite horizon before the pair scan. During local refresh, the unchanged owner already has a valid retained exact event.

The temporal broad phase now has two layers.

### Axis-separable proof

At contact, both coordinate gaps must individually be no larger than combined radius `R`. For horizon `t`, X contact therefore requires

`|dx0| <= R + |dvx| t + 0.5 |dax| t^2`,

and likewise for Y. If either inequality fails, contact by the horizon is impossible. Scale-aware numerical slack is added and ambiguous/non-finite cases fail open.

### Radial proof

For horizon `t`, relative displacement obeys

`|v t + 0.5 a t^2| <= |v| t + 0.5 |a| t^2`.

Thus contact by `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implementation uses L1 speed/acceleration bounds, squared center distance, scale-aware numerical slack, additional tie-time slack, and fail-open handling for non-finite/overflow cases.

The axis proof executes before the radial proof. A rejection from either is a proof that the candidate cannot beat/tie the current event; a non-rejection only permits the next proof or exact TOI. The axis layer can be disabled independently at JVM startup for causal A/B research with `-Dbouncingballs.cadqAxisTemporalPruning=false`.

Worst-case asymptotics remain quadratic: adversarial geometry can make every temporal bound inconclusive and invalidation fan-out can approach a rebuild. The optimization reduces exact work in measured populations, not the formal worst-case class.

## Simultaneous contacts and structural correctness

Events within `NumericalPolicy.sameTime` advance together, are deduplicated into physical pair/wall identities, partitioned into ball-sharing islands, and resolved deterministically. `SEQUENTIAL` is the ordering-sensitive baseline; `ITERATIVE` uses symmetric projected Gauss-Seidel; `DIRECT` solves a coupled normal-impulse system with iterative fallback for singular/nonphysical cases.

The simulator records a deterministic physical-contact-history fingerprint. It is order-sensitive between event batches and canonicalized within one simultaneous batch. This is a compact diagnostic rather than a cryptographic proof, but it distinguishes “same physical collision history with floating-point state drift” from missed/reordered contacts.

Regression coverage includes simultaneous three-body contact, scheduler-independent event budgets, canonical ownership, directional invalidation, sparse/non-contiguous ids, high-speed contact-history equivalence, radial and axis temporal-bound safety under velocity and acceleration, construction pruning, and TOI polynomial-degree accounting. The axis milestone additionally checks thousands of deterministic randomized accelerated states against exact finite TOI horizons.

## Workloads and TOI-degree evidence

Randomized workloads are deterministic, finite, in-bounds, uniquely identified, and nonpenetrating at time zero. Constructed topologies may start exactly touching.

Two acceleration workloads have deliberately different semantics:

- `ACCELERATED` applies identical gravity `(0,-9.81)` to every body. Ball-ball relative acceleration cancels, so exact pair queries are quadratic.
- `DIFFERENTIAL_ACCELERATION` retains gravity but assigns each generated body a distinct bounded horizontal acceleration. Every generated pair has nonzero relative acceleration and therefore a quartic pair equation.

`SimulationStats` records `pairToiQueries`, `quadraticPairToiQueries`, `quarticPairToiQueries`, and `wallToiQueries`.

A dedicated validation campaign used both acceleration workloads, 20/100 balls, three seeds, one warmup, three repetitions, and all three continuous schedulers: **108 measured trials, 0 physical correctness failures, 0 execution failures, 0 numerical-drift warnings**.

At 100 balls the representative medians were:

| Workload | Scheduler | Pair TOI | Quadratic | Quartic | Temporal prunes |
|---|---|---:|---:|---:|---:|
| shared gravity | ALL_PAIRS | 19,800 | 19,800 | 0 | — |
| shared gravity | GLOBAL | 5,445 | 5,445 | 0 | — |
| shared gravity | CADQ | 1,839 | 1,839 | 0 | 3,610 |
| differential acceleration | ALL_PAIRS | 19,800 | 0 | 19,800 | — |
| differential acceleration | GLOBAL | 5,445 | 0 | 5,445 | — |
| differential acceleration | CADQ | 2,351 | 0 | 2,351 | 3,171 |

This corrected an earlier interpretation: the high prune rate under shared gravity was candidate-reduction evidence, not quartic-root evidence. The differential workload and counters now make that distinction explicit.

Validation provenance: GitHub Actions run `31687633334`, artifact `9176054588`, digest `sha256:458939724ec33bcb270c9d01460062796370387d3722f7c111f2757a09a4dd03`.

## Differential validation methodology

For each scenario, `CampaignCli` regenerates the same deterministic initial state for each scheduler. `ALL_PAIRS_CCD` supplies the reference.

Acceptance requires:

1. identical resolved-contact count, deduplicated physical-contact count, simultaneous-batch count, and contact-history fingerprint;
2. final time/position/velocity inside an explicit drift ceiling.

A tighter state tolerance remains visible as `numericalDriftWarning`. The original 630-trial scheduler campaign found 30 strict state mismatches in high-speed/wall-heavy 100-ball cases while preserving the same physical histories. Those warnings remain recorded instead of weakening the tolerance until they disappear.

Campaign schema 4 reports temporal-pruning counters and TOI polynomial-degree counters. Research feature switches are passed explicitly to the JVM and preserved in experiment logs/artifacts.

## Timing and comparison definitions

Engine timing is separated into `constructionNanos`, `advanceNanos`, and `totalEngineNanos = constructionNanos + advanceNanos`. Workload generation is excluded from scheduler comparisons.

`benchmarks/compare_campaigns.py` matches `(workload, requestedBalls, seed, repetition)` observations. Within each campaign it first forms `CADQ/GLOBAL`, then compares candidate/baseline log-ratios and bootstraps them. This reduces shared hosted-runner/JVM noise but does not make different machines equivalent.

`CadqProfileCli` inserts opt-in `nanoTime` probes and is diagnostic attribution only. Uninstrumented campaigns remain the timing acceptance gate.

`maxQueueSize` is a structural proxy, not heap allocation. Event/materialization and TOI-degree counters describe mechanisms, not memory use.

## Empirical optimization sequence

### Canonical ownership

Representative 100-ball exact TOI counts after canonical ownership were close to GLOBAL—for example accelerated `6,276 vs 6,277`, sparse `6,173 vs 6,174`, high velocity `15,772 vs 15,238`—while CADQ retained substantially smaller queues. Before canonical ownership, CADQ pair work was roughly doubled. The mechanism was correct, but timing still lost.

### Dense bookkeeping

For 105 matched 100-ball observations:

| Metric | Canonical CADQ/GLOBAL | Dense CADQ/GLOBAL | Dense/canonical factor |
|---|---:|---:|---:|
| total | 1.1477 | 1.0588 | **0.9225** |
| construction | 1.0328 | 0.9903 | 0.9589 |
| advance | 1.3539 | 1.2228 | **0.9032** |

Bootstrap intervals supported total (`0.869–0.978`) and advance (`0.855–0.954`) improvement; construction was compatible with parity (`0.884–1.037`).

### Advance profiling and micro-optimization falsification

A 105-trial 100-ball profile attributed roughly 48% of profiled scheduler time to full reselection and 38% to local refresh. Three subsequent micro-optimizations all preserved physics but had total/advance bootstrap intervals crossing `1`, so they were reverted. See [`CADQ_ADVANCE_PROFILE.md`](CADQ_ADVANCE_PROFILE.md).

### Radial temporal pruning during advance

The larger reversed-order enabled/disabled replication produced **2,100 measured scheduler trials per side**, with zero physical correctness or execution failures.

At 100 balls:

- median exact TOI `7,556 -> 6,754` (**-10.6%**);
- normalized advance factor **0.748** (`0.724–0.772`);
- normalized total factor **0.963** (`0.935–0.991`).

A stronger same-JVM interleaved A/B produced 700 paired observations per size:

| Balls | Construction | Advance | Total |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

This established the advance mechanism causally while initialization stayed at parity.

### Radial temporal pruning during construction

The accepted master baseline (advance-only pruning) and construction-pruning candidate were checked out side-by-side on one hosted runner. Every campaign summary passed the independent all-pairs correctness gate.

| Balls | Construction candidate/baseline | Total candidate/baseline | Advance candidate/baseline |
|---:|---:|---:|---:|
| 100 | **0.737** (`0.642–0.849`) | **0.789** (`0.695–0.894`) | 1.015 (`0.905–1.134`) |
| 300 | **0.565** (`0.512–0.619`) | **0.665** (`0.611–0.718`) | 0.953 (`0.819–1.118`) |
| 1000 | **0.438** (`0.340–0.578`) | **0.576** (`0.476–0.695`) | 0.987 (`0.903–1.061`) |

The normalized construction reductions were about **26.3%, 43.5%, and 56.2%**. At 1000 bodies, median CADQ exact TOI queries fell **538,147 -> 152,575 (-71.6%)**. The 1000-body sample was small and is treated as scale evidence.

Valid scale provenance: run `31686901547`, artifact `9175894829`, digest `sha256:99b15429e13ff5eb2546ae823ba51d82f169b3225a16aa1aff657de34acb0d1c`.

An earlier scale workflow was discarded because its baseline checkout was uncompiled and `tee` masked the Maven failure without `pipefail`. The corrected run compiled both checkouts, enabled `pipefail`, and required exactly the expected result files.

### Swept spatial-grid hypothesis: falsified

The next candidate built a current-center uniform grid at scheduler synchronization points and queried it with a conservative swept per-axis envelope over the exact owner horizon. It was specifically designed to avoid the unsafe static-grid failure mode for fast or accelerated bodies.

The implementation passed randomized and process-level correctness gates, and direct probes reported geometric exclusion rates from about 94% to 99.8%. Yet median exact TOI work was unchanged:

| Bodies | Temporal-only TOI | Swept-grid TOI |
|---:|---:|---:|
| 100 | 732.5 | 732.5 |
| 300 | 2,105.5 | 2,105.5 |
| 1000 | 7,906 | 7,906 |

The grid had simply replaced cheap temporal rejections with more expensive index/rebuild/query work. Matched total-engine factors were **1.215** at 100, **1.315** at 300, and **2.013** at 1000. The implementation was removed.

Evidence: run `31690445774`, artifact `9177447701`, digest `sha256:4fad8d6c9077242b6036f2ac3d18d003081c1ecc78233acea5219a159b35d0a6`.

Full falsification record: [`CADQ_SPATIAL_PRUNING_FALSIFICATION.md`](CADQ_SPATIAL_PRUNING_FALSIFICATION.md).

### Axis-separable temporal pruning: accepted

The failed grid revealed one useful predicate the radial L1 bound did not encode: each coordinate gap must independently be closable by the owner horizon. The axis test adds only constant-time scalar arithmetic before the radial predicate.

A direct initial-horizon probe found that, after accounting for candidates already rejected radially, the axis condition rejected another approximately **26–30% of all canonical pairs** across sparse, dense, high-velocity, and differential-acceleration workloads from 100 through 1000 bodies.

In the first complete A/B, the number of temporal checks stayed unchanged while pooled median exact TOI work fell:

| Bodies | Radial-only TOI | Radial + axis TOI | Change |
|---:|---:|---:|---:|
| 100 | 3,282.5 | 1,927.5 | **-41.3%** |
| 300 | 27,108.5 | 13,835.5 | **-49.0%** |
| 1000 | 154,079.5 | 66,083 | **-57.1%** |

For the true-quartic `DIFFERENTIAL_ACCELERATION` workload, quartic pair solves fell **57.0%**, **59.9%**, and **61.6%** at 100/300/1000 bodies respectively.

First process-level timing result:

| Bodies | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 100 | **0.765** (`0.709–0.824`) | **0.797** (`0.731–0.872`) | **0.779** (`0.726–0.837`) |
| 300 | **0.737** (`0.697–0.779`) | **0.769** (`0.726–0.814`) | **0.747** (`0.707–0.787`) |
| 1000 | 0.826 (`0.593–1.100`) | **0.792** (`0.647–0.891`) | 0.810 (`0.605–1.009`) |

The 1000-body first-run total/construction sample had only six matched observations and crossed parity. An accidental second complete process run independently reproduced the 100/300 improvement and put all three 1000-body intervals below parity:

| Bodies | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 100 | **0.809** (`0.753–0.867`) | **0.798** (`0.733–0.869`) | **0.816** (`0.762–0.874`) |
| 300 | **0.798** (`0.751–0.843`) | **0.820** (`0.774–0.866`) | **0.804** (`0.759–0.848`) |
| 1000 | **0.751** (`0.568–0.919`) | **0.801** (`0.625–0.941`) | **0.776** (`0.587–0.938`) |

The 1000-body result remains scale evidence, not a precise estimate. Both complete runs had zero physical correctness failures, zero execution failures, and zero numerical-drift warnings.

Accepted A/B provenance:

- run `31691581974`, artifact `9177829256`, digest `sha256:d8ea7f53b2fb25785da097f36e5bf9f47913b69eba99d3b197dc014489f99a36`;
- replication run `31691678121`, artifact `9177875301`, digest `sha256:7d6345c1c74f0ae3efa3f9062203aaf7d710b5b44deee0cf9491827e20957bbc`.

Full proof and interpretation: [`CADQ_AXIS_TEMPORAL_PRUNING.md`](CADQ_AXIS_TEMPORAL_PRUNING.md).

## Current conclusion and next hypotheses

Evidence now supports six concrete conclusions:

1. duplicate pair prediction was a large CADQ defect;
2. object/hash bookkeeping was a measurable secondary defect;
3. several plausible micro-overhead reductions were not reproducible timing wins and were correctly rejected;
4. radial temporal pruning reduced expensive candidate work in both advance and construction;
5. layering a swept spatial grid ahead of that predicate was redundant and substantially slower despite spectacular geometric exclusion rates;
6. axis-separable temporal pruning adds genuinely new information at negligible structural cost and removes roughly half of the remaining exact pair work in the tested 100–1000-body populations.

The next scheduler experiments should therefore prioritize **horizon tightening and candidate ordering** rather than immediately trying another broad index. A cheap method that discovers a promising early pair before the full scan could tighten the horizon and make both temporal predicates reject even more work without a global spatial structure.

Independent tracks remain important:

- stronger 1000+ and cross-machine replication;
- explicit allocation/GC evidence rather than queue-size proxies;
- later calendar/bucket queue and adaptive-scheduler experiments when queue behavior becomes the measured bottleneck;
- simultaneous-contact resolver research kept causally separate from scheduler timing.

These hosted-runner measurements are research evidence, not universal machine-independent performance claims.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references include Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), Bannerman et al., *DynamO* (2011), and Johnson et al., *Reflections on Simultaneous Impact*.

The repository does not claim that spatial indexing is generally useless; it claims the tested swept-grid-as-an-extra-filter architecture was redundant with cheaper temporal predicates. The repository also does not yet claim a calendar/bucket queue, adaptive scheduler, JMH/JFR allocation results, cross-machine statistical conclusions, or million-ball scalability. Those should be added only with measured implementations and preserved evidence.
