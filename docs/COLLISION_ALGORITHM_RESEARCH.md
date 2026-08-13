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
6. **Conservative temporal pruning during advance:** pairs proven unable to reach contact before the owner's current exact event skip exact TOI. This produced a reproducible advance/total improvement.
7. **Temporal pruning during construction:** once the advance proof was accepted, the same wall-seeded horizon was applied to initial owner selection. Construction benefit increased strongly with N through the tested 1000-body scale.

Public body ids remain arbitrary unique `int`s; dense local slots are internal. `Simulation` requires uniqueness because stable ids define canonical pair ownership.

## Current CADQ invariant

For each owner `u`:

- only pairs `u-v` with `u.id < v.id` are canonically owned;
- all four wall predictions are owned by `u`;
- the complete earliest-time tie set is retained;
- reverse dependencies identify owners whose retained predictions reference each body;
- an exact pair solve may be skipped only after a conservative bound proves the pair cannot beat or tie an exact owner horizon.

At construction and full reselection, wall TOIs are evaluated first. Their earliest exact event usually establishes a finite horizon before the pair scan. During local refresh, the unchanged owner already has a valid retained exact event.

For horizon `t`, relative displacement obeys

`|v t + 0.5 a t^2| <= |v| t + 0.5 |a| t^2`.

Thus contact by `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implementation uses L1 speed/acceleration bounds, squared center distance, scale-aware numerical slack, additional tie-time slack, and fail-open handling for non-finite/overflow cases. A temporal rejection is therefore a proof that the candidate cannot beat/tie the current event; a non-rejection is only permission to run exact TOI.

Worst-case asymptotics remain quadratic: adversarial geometry can make every temporal bound inconclusive and invalidation fan-out can approach a rebuild. The optimization reduces exact work in measured populations, not the formal worst-case class.

## Simultaneous contacts and structural correctness

Events within `NumericalPolicy.sameTime` advance together, are deduplicated into physical pair/wall identities, partitioned into ball-sharing islands, and resolved deterministically. `SEQUENTIAL` is the ordering-sensitive baseline; `ITERATIVE` uses symmetric projected Gauss-Seidel; `DIRECT` solves a coupled normal-impulse system with iterative fallback for singular/nonphysical cases.

The simulator records a deterministic physical-contact-history fingerprint. It is order-sensitive between event batches and canonicalized within one simultaneous batch. This is a compact diagnostic rather than a cryptographic proof, but it distinguishes “same physical collision history with floating-point state drift” from missed/reordered contacts.

Regression coverage includes simultaneous three-body contact, scheduler-independent event budgets, canonical ownership, directional invalidation, sparse/non-contiguous ids, high-speed contact-history equivalence, temporal-bound safety under velocity and acceleration, construction pruning, and TOI polynomial-degree accounting.

## Workloads and TOI-degree evidence

Randomized workloads are deterministic, finite, in-bounds, uniquely identified, and nonpenetrating at time zero. Constructed topologies may start exactly touching.

Two acceleration workloads now have deliberately different semantics:

- `ACCELERATED` applies identical gravity `(0,-9.81)` to every body. Ball-ball relative acceleration cancels, so exact pair queries are quadratic.
- `DIFFERENTIAL_ACCELERATION` retains gravity but assigns each generated body a distinct bounded horizontal acceleration. Every generated pair has nonzero relative acceleration and therefore a quartic pair equation.

`SimulationStats` records:

- `pairToiQueries`;
- `quadraticPairToiQueries`;
- `quarticPairToiQueries`;
- `wallToiQueries`.

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

This corrects an earlier interpretation: the high prune rate observed under shared gravity was real candidate-reduction evidence, but it was not evidence about quartic pair-root avoidance. The new workload/counters now provide that mechanism evidence explicitly.

Validation provenance: GitHub Actions run `31687633334`, artifact `9176054588`, digest `sha256:458939724ec33bcb270c9d01460062796370387d3722f7c111f2757a09a4dd03`.

## Differential validation methodology

For each scenario, `CampaignCli` regenerates the same deterministic initial state for each scheduler. `ALL_PAIRS_CCD` supplies the reference.

Acceptance requires:

1. identical resolved-contact count, deduplicated physical-contact count, simultaneous-batch count, and contact-history fingerprint;
2. final time/position/velocity inside an explicit drift ceiling.

A tighter state tolerance remains visible as `numericalDriftWarning`. The original 630-trial scheduler campaign found 30 strict state mismatches in high-speed/wall-heavy 100-ball cases while preserving the same physical histories. Those warnings remain recorded instead of weakening the tolerance until they disappear.

Campaign schema 4 additionally reports temporal-pruning counters and TOI polynomial-degree counters.

## Timing and comparison definitions

Engine timing is separated into:

- `constructionNanos`: `Simulation` construction and initial scheduler prediction work;
- `advanceNanos`: `Simulation.advance(...)` only;
- `totalEngineNanos = constructionNanos + advanceNanos`.

Workload generation is excluded from scheduler comparisons.

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

### Advance profiling and falsification

A 105-trial 100-ball profile attributed roughly 48% of profiled scheduler time to full reselection and 38% to local refresh. Three subsequent micro-optimizations all preserved physics but had total/advance bootstrap intervals crossing `1`, so they were reverted. See [`CADQ_ADVANCE_PROFILE.md`](CADQ_ADVANCE_PROFILE.md).

### Temporal pruning during advance

The larger reversed-order enabled/disabled replication produced **2,100 measured scheduler trials per side**, with zero physical correctness or execution failures.

At 100 balls:

- median exact TOI `7,556 -> 6,754` (**-10.6%**);
- normalized advance factor **0.748** (`0.724–0.772`);
- normalized total factor **0.963** (`0.935–0.991`).

A stronger same-JVM interleaved A/B then produced 700 paired observations per size:

| Balls | Construction | Advance | Total |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

This established the advance mechanism causally while initialization stayed at parity.

### Temporal pruning during construction

The accepted master baseline (advance-only pruning) and construction-pruning candidate were checked out side-by-side on one hosted runner. At 100/300 balls there were 28 matched CADQ observations per size; the 1000-body probe had five matched observations. Every campaign summary passed the independent all-pairs correctness gate.

| Balls | Construction candidate/baseline | Total candidate/baseline | Advance candidate/baseline |
|---:|---:|---:|---:|
| 100 | **0.737** (`0.642–0.849`) | **0.789** (`0.695–0.894`) | 1.015 (`0.905–1.134`) |
| 300 | **0.565** (`0.512–0.619`) | **0.665** (`0.611–0.718`) | 0.953 (`0.819–1.118`) |
| 1000 | **0.438** (`0.340–0.578`) | **0.576** (`0.476–0.695`) | 0.987 (`0.903–1.061`) |

The corresponding normalized construction reductions are about **26.3%, 43.5%, and 56.2%**. At 1000 bodies, median CADQ exact TOI queries fell **538,147 -> 152,575 (-71.6%)**. The unchanged advance phase remained compatible with parity, exactly matching the intended mechanism boundary.

The 1000-body sample is small and must not be presented as a precise universal estimate.

Valid scale provenance: run `31686901547`, artifact `9175894829`, digest `sha256:99b15429e13ff5eb2546ae823ba51d82f169b3225a16aa1aff657de34acb0d1c`.

An earlier scale workflow was discarded because its baseline checkout was uncompiled and `tee` masked the Maven failure without `pipefail`. The corrected run compiled both checkouts, enabled `pipefail`, and required exactly the expected result files. The invalid run is retained only as a provenance/failure record.

Full temporal-pruning proof and evidence are in [`CADQ_TEMPORAL_PRUNING.md`](CADQ_TEMPORAL_PRUNING.md).

## Current conclusion and next hypotheses

Evidence now supports five concrete conclusions:

1. duplicate pair prediction was a large CADQ defect;
2. object/hash bookkeeping was a measurable secondary defect;
3. several plausible micro-overhead reductions were not reproducible timing wins and were correctly rejected;
4. conservative temporal pruning reduced exact candidate work and improved `advance()`;
5. applying the same proven horizon during initial selection produced increasingly large construction gains through the tested 1000-body scale.

The most justified next directions are:

- a **conservative swept spatial index** over each owner's horizon, rather than an unsafe current-position grid;
- **cost-aware pruning experiments** using the new quadratic/quartic counters and true differential-acceleration workload;
- stronger **1000+ body replication** on more samples/machines;
- explicit **allocation/GC evidence** rather than queue-size proxies.

These hosted-runner measurements are research evidence, not universal machine-independent performance claims.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references include Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), Bannerman et al., *DynamO* (2011), and Johnson et al., *Reflections on Simultaneous Impact*.

The repository does not yet claim a swept spatial broad phase, calendar/bucket queue, adaptive scheduler, JMH/JFR allocation results, cross-machine statistical conclusions, or million-ball scalability. Those should be added only with measured implementations and preserved evidence.
