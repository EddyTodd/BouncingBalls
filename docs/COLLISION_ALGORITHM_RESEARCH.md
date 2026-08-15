# Collision algorithm research

## Objective

This repository is a controlled research laboratory for exact continuous collision detection and simultaneous-contact resolution for frictionless 2D circular rigid bodies. The goal is not to crown one algorithm from one benchmark. The goal is to maintain materially different implementations, state their invariants, test them against independent correctness oracles, measure where each wins, preserve falsified hypotheses, and make future scheduler-selection decisions evidence-driven.

The 2022 Swing implementation remains under `legacy/`; the research engine is rendering-independent.

## Motion model

For two circles with relative position `r`, relative velocity `v`, relative acceleration `a`, and combined radius `R`, contact solves

`|r + vt + 0.5at^2|^2 - R^2 = 0`.

The equation is genuinely quartic only when relative acceleration is nonzero. Equal world-frame accelerations cancel in relative coordinates, so uniform gravity still produces quadratic ball-ball TOI. `DIFFERENTIAL_ACCELERATION` exists specifically to exercise genuine quartic pair motion.

Exact here means exact for the piecewise constant-acceleration model up to IEEE-754 numerical root approximation. `PolynomialRoots` isolates real quartic roots through derivative-root partitioning and bisection rather than relying on a fragile closed-form quartic expression.

## Continuous schedulers

### `ALL_PAIRS_CCD`

Rebuilds every wall and unordered pair prediction after every trajectory-changing batch. It is intentionally expensive and simple, and remains the primary small-system physical correctness oracle.

### `SWEEP_AND_PRUNE_CCD`

Rebuilds after every trajectory-changing batch, but replaces all-pairs candidate enumeration with conservative swept intervals over the earliest exact wall horizon. Radius-expanded X intervals are swept, Y-disjoint survivors are rejected, and only two-axis overlaps reach exact pair TOI. Ambiguous or unbounded cases fail open.

SAP is architecturally important because it trades extremely cheap construction for repeated rebuild work. See [`SAP_CROSSOVER.md`](SAP_CROSSOVER.md) and [`SAP_CADQ_DURATION_CROSSOVER.md`](SAP_CADQ_DURATION_CROSSOVER.md).

### `SWEPT_BVH_CCD`

Uses the **same** conservative horizon and swept trajectory boxes as SAP, but enumerates overlaps with a rebuilt binary AABB hierarchy. The accepted implementation uses reusable flat node arrays, widest-centroid-axis splits, linear midpoint partitioning, and a deterministic median fallback for badly imbalanced partitions.

Because SAP and BVH share `SweptAabb`, their exact candidate sets are required to match in paired research runs. The BVH is currently a valid but slower architectural comparator through the tested 1000-body scale. See [`SWEPT_BVH_RESEARCH.md`](SWEPT_BVH_RESEARCH.md).

### `GLOBAL_EVENT_QUEUE`

Maintains one generation-validated heap. Changed bodies add replacement predictions while stale entries are discarded lazily. It avoids global rebuilds but can accumulate invalid events and larger queues.

### `COMPUTE_AHEAD_DEPENDENCY_QUEUE` (CADQ)

Retains only each canonical owner's earliest event/tie set, tracks reverse dependencies, and refreshes the prediction graph invalidated by trajectory changes. Dense simulation-local slots support arbitrary unique public body ids.

CADQ currently combines canonical pair ownership, dense dependency bookkeeping, local invalidation, conservative axis and radial temporal pruning, and a canonical bound on local-owner traversal.

## Correctness gate

Timing is never accepted without a physical-equivalence check.

For deterministic seeded scenarios, `ALL_PAIRS_CCD` supplies the reference. Candidate schedulers must preserve:

- resolved and deduplicated physical contact counts;
- simultaneous-batch count;
- deterministic physical-contact-history fingerprint;
- final simulation time, positions, and velocities inside the explicit drift ceiling.

A tighter state comparison remains visible separately so floating-point path dependence is not silently relabeled as correctness. Constructed simultaneous-contact cases additionally exercise Newton's cradle and symmetric multi-contact behavior.

Architecture-specific campaigns may impose stronger invariants. SAP/BVH comparisons additionally require equal exact swept candidate counts and equal exact pair-TOI counts because they intentionally share the same trajectory envelopes.

## Exact-work accounting

`SimulationStats` separates:

- pair and wall TOI queries;
- quadratic and quartic pair TOI queries;
- predicted-event materializations;
- queue and invalidation mechanisms;
- CADQ temporal checks/prunes and owner-refresh work;
- SAP canonical pairs, sweep overlap work, exact candidates, rebuilds, and fallback rebuilds;
- swept-BVH canonical pairs, nodes built/depth, node visits, exact candidates, rebuilds, and fallback rebuilds.

Mechanism counters explain *why* timing moved. They do not substitute for uninstrumented timing. Likewise, queue size is a structural proxy and not a heap-allocation measurement.

## CADQ research sequence

CADQ has been developed through falsifiable milestones rather than accumulated assumed optimizations.

1. **All-owner correctness safeguard:** correct but empirically destroyed the intended compute-ahead advantage.
2. **Dependency-local invalidation:** removed unconditional all-owner reselection while preserving complete simultaneous tie sets.
3. **Canonical pair ownership:** assigned each unordered pair once and nearly halved duplicate pair work.
4. **Dense bookkeeping:** replaced hot object/hash structures with dense slots, arrays, bitsets, direct heap events, and primitive id lookup; total and advance cost improved measurably.
5. **Advance profiling:** located most measured scheduler work in full reselection and local refresh. Buffer reuse, the original local-owner bound, and deferred event materialization did not pass the timing gate and were reverted.
6. **Radial temporal pruning:** conservatively skipped exact pair TOI when contact could not beat/tie the current exact owner horizon. Same-JVM evidence established a causal advance improvement.
7. **Construction temporal pruning:** extended the accepted proof to initial selection. Construction benefit increased strongly through the tested 1000-body scale.
8. **Swept uniform grid as an extra CADQ filter:** physically safe and geometrically aggressive, but redundant with temporal pruning; exact TOI work did not improve and total time regressed sharply. The grid was removed.
9. **Axis-separable temporal proof:** extracted useful geometry from the failed grid as two cheap one-dimensional reachability inequalities. It removed substantial additional quadratic and genuine-quartic exact work and produced replicated timing gains.
10. **Retained-target candidate ordering:** falsified. Historical partner identity did not tighten the horizon enough to remove meaningful exact work.
11. **Analytical lower-bound probe ordering:** falsified. The tested top-k probes added lower-bound work while tightening the horizon zero times in the mechanism population.
12. **Canonical local traversal retest:** an optimization correctly rejected in milestone 5 became useful after temporal pruning changed the cost structure. It now removes roughly 38–45% of local-owner visits and replicated a small 300-body total-engine improvement.

The important methodological lesson is that optimization value is conditional on surrounding cost structure. A previous negative result is not invalidated when a later architecture change creates a measurable crossover; both results remain part of the record.

Detailed records:

- [`CADQ_ADVANCE_PROFILE.md`](CADQ_ADVANCE_PROFILE.md)
- [`CADQ_TEMPORAL_PRUNING.md`](CADQ_TEMPORAL_PRUNING.md)
- [`CADQ_SPATIAL_PRUNING_FALSIFICATION.md`](CADQ_SPATIAL_PRUNING_FALSIFICATION.md)
- [`CADQ_AXIS_TEMPORAL_PRUNING.md`](CADQ_AXIS_TEMPORAL_PRUNING.md)
- [`CADQ_RETAINED_TARGET_ORDERING_FALSIFICATION.md`](CADQ_RETAINED_TARGET_ORDERING_FALSIFICATION.md)
- [`CADQ_LOWER_BOUND_PROBE_FALSIFICATION.md`](CADQ_LOWER_BOUND_PROBE_FALSIFICATION.md)
- [`CADQ_LOCAL_TRAVERSAL_RETEST.md`](CADQ_LOCAL_TRAVERSAL_RETEST.md)

## Sweep-and-prune result

SAP first established that a conservative spatial broad phase can be highly effective when it *replaces* rebuild-all-pairs enumeration rather than being layered redundantly in front of CADQ.

At 100/300 bodies, the first same-JVM campaign reduced exact SAP candidates to about 0.85%/1.06% of canonical pairs and produced total factors of about 0.092/0.033 relative to `ALL_PAIRS_CCD` in that population.

The more important cross-architecture experiment then timed SAP directly against GLOBAL and CADQ while using all-pairs only as an untimed physical oracle. Two complete runs reproduced the same conclusion at 0.25 simulated seconds:

- SAP total cost is decisively lower than GLOBAL at 100 and 300 bodies;
- SAP total cost is decisively lower than CADQ at 100 bodies;
- SAP versus CADQ at 300 bodies is workload dependent and the aggregate interval crosses parity;
- SAP construction is much cheaper than both incremental schedulers, while SAP advance is substantially more expensive because it rebuilds after each trajectory-changing batch.

At 300 bodies, replicated SAP/CADQ workload factors range from strong SAP wins on sparse/dense/differential-acceleration scenarios to strong CADQ wins on clustered/adversarial scenarios, with wall-dominated motion near parity.

Duration sweeps confirmed that the winner changes with horizon, but event-batch count alone and initial SAP candidate fraction alone both failed as universal selectors.

This is direct evidence that the repository should not reduce to one scheduler implementation. Different architectures occupy different regions of the workload/horizon space.

## Parametric selector result

The observed SAP/CADQ crossover motivated an explicit test of whether a cheap pre-run model could select the faster scheduler. Instead of training on named workload labels, the repository introduced a deterministic six-dimensional Halton workload manifold spanning fill, clustering, speed, wall bias, shared acceleration, and differential acceleration.

The first manifold was rejected because it under-sampled CADQ-favorable high-speed regimes and entangled density with wall horizon. The redesigned fixed-domain manifold expanded speed through 300 and varied fill through radius.

On the redesigned 360-scenario population, SAP won 317 scenarios and CADQ 43. A generic physics-feature ridge model slightly improved aggregate regret over always-SAP, but held-design-point validation exposed severe extrapolation failure in an unseen high-speed stratum: the worst fold had about 59.7% accuracy, 1.287x geometric regret, 3.08x p95 regret, and 4.60x maximum regret.

The production adaptive scheduler was therefore **not implemented**. A crossover is not sufficient evidence for adaptation if the winner cannot be predicted robustly from cheap pre-run features. See [`PARAMETRIC_SELECTOR_RESEARCH.md`](PARAMETRIC_SELECTOR_RESEARCH.md).

## Swept-BVH result

The standalone BVH experiment asks a narrower question than the failed CADQ grid: if SAP and BVH receive the **same conservative swept rectangles**, is hierarchy traversal a better way to enumerate their intersections?

The first naive BVH used allocated nodes and recursively copied/sorted sublists. Under a paired 100/300-body protocol it was 2.075648x slower than SAP overall and won 0/42 scenarios. This result was treated as implementation-confounded rather than architectural evidence.

After replacing that rebuild with reusable flat arrays and mostly linear midpoint partitioning, the identical campaign improved to:

- 100 bodies: 1.170270x BVH/SAP, 2/21 BVH wins;
- 300 bodies: 1.275374x, 0/21 wins;
- combined: **1.221693x**, 2/42 wins.

A separate 1000-body scaling appendix then tested whether hierarchy eventually overcame its higher constant cost. It did not:

- all workloads combined: **1.243665x BVH/SAP**, 0/21 wins;
- closest workload families: accelerated 1.079238x, adversarial 1.125584x, clustered 1.127527x.

Every paired run preserved equal exact candidate counts, equal pair-TOI counts, equal physical history, and equivalent final state. The measured disadvantage is therefore candidate-enumeration overhead rather than different collision work.

The rebuild-on-change swept BVH remains in the collection as a correct, instrumented negative result. The experiment does not falsify persistent/dynamic AABB trees or higher-dimensional BVHs. See [`SWEPT_BVH_RESEARCH.md`](SWEPT_BVH_RESEARCH.md).

## Resolver track

Scheduler research is kept causally separate from simultaneous-contact resolution.

Available resolvers are:

- `SEQUENTIAL`: deterministic pairwise baseline;
- `ITERATIVE`: symmetric projected Gauss-Seidel;
- `DIRECT`: coupled normal-impulse linear solve with iterative fallback for singular or nonphysical systems.

Future resolver work should independently study Newton's cradle transfer, larger simultaneous contact graphs, redundant/singular constraints, conservation error, restitution, convergence, determinism, and scaling. Scheduler timing should not be credited for resolver changes.

## Current roadmap

Two tempting shortcuts are now closed by evidence:

1. more horizon-ordering heuristics for CADQ are not currently justified after retained-target and lower-bound probes failed their mechanism gates;
2. a production adaptive SAP/CADQ scheduler is not justified after the first cheap pre-run selector failed held high-speed generalization.

The current collection objective therefore returns to **materially different standalone broad-phase architectures**.

### Next scheduler experiment: standalone swept spatial hash / uniform grid

Build a scheduler that consumes the same conservative `SweptAabb` boxes used by SAP and BVH, but enumerates overlap candidates through grid/hash buckets. The experiment should preserve the same exact-candidate and physical-history invariants where the box semantics are identical.

This is not a repeat of the rejected CADQ spatial filter. That earlier grid was layered in front of CADQ's already-effective temporal pruning and added redundant work. A standalone grid replaces rebuild-all-pairs enumeration and therefore tests a different architectural hypothesis.

Compare at minimum:

- SAP versus spatial hash at 100/300 bodies;
- scaling through at least 1000 bodies if the small/medium result does not settle the question;
- sparse, dense, clustered, high-velocity, wall-dominated, accelerated, and adversarial regimes;
- bucket occupancy, duplicate candidate generation, deduplication cost, exact candidate counts, and total engine timing.

Do not accept a grid tuning constant from one named workload. Cell-size strategy should be either mathematically derived from the current swept boxes or tested across a predeclared parameter set.

### Later independent tracks

- persistent/dynamic AABB tree rather than rebuilding the BVH after every event batch;
- calendar/bucket event queues when queue behavior becomes a measured bottleneck;
- explicit allocation/GC profiling;
- stronger 1000+ and cross-machine replication;
- simultaneous-contact resolver research.

Adaptive scheduling can be revisited only after new generic features, early-run evidence, or additional architectures make the decision problem materially better posed.

## Limitations

Worst-case asymptotics remain important. SAP can degrade to quadratic active overlap; rebuilt BVH traversal can still approach quadratic overlap plus tree construction; CADQ invalidation can approach a rebuild; global heaps can accumulate stale events. The current hosted-runner results describe measured populations, not universal machine-independent speedups.

No claim is currently made for million-body scalability, GPU acceleration, 3D/polygons/friction, a calendar queue, successful adaptive scheduling, or universal optimality.

## Prior work

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; the repository does not claim that these broad ideas are novel. Useful starting references include Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), Bannerman et al., *DynamO* (2011), and Johnson et al., *Reflections on Simultaneous Impact*.
