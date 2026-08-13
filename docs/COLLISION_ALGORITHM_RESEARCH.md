# Collision algorithm research note

## Model and numerical policy

Bodies are circles with independent radius, mass, restitution, position, velocity, and constant acceleration. For relative state `r,v,a`, contact solves `|r + vt + ½at²|² - (R1+R2)² = 0`. This is quartic; velocity-only motion reduces to quadratic. `PolynomialRoots` isolates real roots through derivative partitions and bisection with centralized scale-aware tolerances. This makes the trajectory model exact, but not the IEEE-754 root approximation.

## Scheduling and CADQ

All-pairs CCD is the small-system correctness reference. The global heap stores absolute-time events and captures each participating body's generation; invalid events are discarded lazily.

CADQ is the repository's compute-ahead hypothesis. The first implementation stored one earliest prediction per owner plus reverse dependencies, but after each trajectory change it fully reselected every owner as a correctness safeguard. That version was intentionally not an optimization claim: its adversarial workload showed 92,700 TOI queries, 900 reselections, and an 88.5% stale-pop rate for 100 balls.

The current implementation replaces that safeguard with local invalidation while preserving correctness invariants.

### CADQ invariant

For every owner `u`, CADQ retains the complete set of events tied for the earliest predicted time involving `u`, including walls. The global heap is the union of those retained owner sets. Reverse links map a body `v` to every owner whose retained set contains an event against `v`.

After a set `C` of bodies changes trajectory:

1. every body in `C` is fully reselected;
2. every owner whose retained set referenced a body in `C` is fully reselected;
3. every remaining owner keeps all predictions against unchanged bodies, because neither side of those trajectories changed;
4. each remaining owner tests only bodies in `C`; any newly earlier event replaces its retained set, while an equal-earliest event joins the retained tie set.

This is sufficient because a collision prediction can change only when at least one participating trajectory changes. It avoids the previous `N` complete owner scans while still detecting a changed body that becomes a new earlier collision for an owner that had no reverse dependency on it.

The tie-set requirement is essential. Retaining exactly one event per owner can omit an edge of a simultaneous contact graph when one body has two or more equally early contacts. The scheduler therefore retains all events within `NumericalPolicy.sameTime` of an owner's minimum and returns all globally equal-time retained events in one batch. `Simulation` deduplicates duplicate physical contacts caused by ownership symmetry before island resolution.

### Work model and falsification criteria

Let `N` be body count, `k=|C|` the changed bodies, and `d` the number of additional owners invalidated through retained reverse dependencies. Ignoring walls, a local update performs approximately `(k+d)N` pair predictions for full reselections plus `(N-k-d)k` local changed-body tests. The old safeguard performed approximately `N²` owner-pair predictions after every batch.

This does **not** imply asymptotic improvement in every workload. If dependency fan-out makes `d≈N`, CADQ degenerates toward a full rebuild. The optimization hypothesis is therefore empirical:

- sparse/local collision workloads should produce `cadqFullReselections` far below an all-owner reselection count;
- `cadqLocalPairRefreshes` should account for most unaffected-owner maintenance work;
- CADQ must remain state-equivalent to the all-pairs reference within the numerical policy across seeded workloads;
- adversarial dependency fan-out must be reported rather than hidden, even if it defeats CADQ;
- wall-clock conclusions require repeated benchmark campaigns; operation counters alone establish mechanism, not speed.

JSONL reports `cadqFullReselections`, `cadqLocalPairRefreshes`, queue size, TOI queries, stale events, and dependency invalidations so these hypotheses can be tested directly.

## Simultaneous contacts

Events within `NumericalPolicy.sameTime` are advanced together, deduplicated, partitioned into ball-sharing islands, then solved deterministically. Sequential is the ordering-sensitive baseline. Iterative uses forward/reverse projected Gauss-Seidel. Direct constructs the coupled normal-impulse matrix and falls back when singular or nonphysical. A zero-time batch guard aborts rather than silently looping.

CADQ has explicit regression coverage for a three-body line in which the middle body has two equally early contacts. The retained owner tie sets must expose both physical contacts in the same scheduler batch. A second regression verifies that a local two-body trajectory change does not mechanically trigger the former all-owner full reselection and that unaffected owners use changed-body refreshes.

## Differential validation methodology

Performance evidence is now gated by a differential state oracle rather than by event counts alone.

For each scenario, `CampaignCli` regenerates a fresh deterministic initial state for every scheduler invocation and executes `ALL_PAIRS_CCD` as the correctness reference. The final state is canonicalized by ball id and includes simulation time, position, and velocity. `GLOBAL_EVENT_QUEUE` and `COMPUTE_AHEAD_DEPENDENCY_QUEUE` are accepted only when every scalar agrees with the reference under a scale-aware tolerance derived from `NumericalPolicy` and the campaign tolerance multiplier.

A campaign records a failure and exits unsuccessfully if a scheduler throws, fails to reach an equivalent simulation time, produces a non-finite state, changes the body identity set, or exceeds the state tolerance. This makes correctness a prerequisite for interpreting speed or mechanism counters.

The Maven test suite contains a smaller deterministic matrix across all workload families and multiple seeds. The campaign is intentionally larger and emits the raw evidence artifact.

## Workload validity and provenance

The original randomized workload generator sampled positions independently, which meant dense or clustered cases could begin overlapped and Gaussian samples could theoretically begin outside the box. Such a state confounds collision-search research with zero-time penetration recovery.

Randomized workloads now use deterministic rejection sampling and are validated before simulation: ids must be unique, state values finite, every body must be inside the bounds, and no pair may begin penetrated. Deliberately constructed workloads may start exactly touching when the topology requires it. `NEWTON_CRADLE` also expands its domain for large requested counts so the generated system remains valid.

This sanitation changes the experiment population. Historical numbers from the earlier generator remain historical observations, but they are not directly interchangeable with new campaign measurements. Exact commit identity and campaign configuration should accompany every published dataset.

## Timing definition

The previous single-run CLI measured only `Simulation.advance()`. That omits the scheduler's initial event construction, which can be substantial and differs among algorithms. New evidence separates:

- workload generation time, excluded from engine timing;
- `constructionNanos`, including simulation construction and initial scheduler rebuild;
- `advanceNanos`, timing only the requested simulation advance;
- `totalEngineNanos = constructionNanos + advanceNanos`.

The campaign performs configurable warmups and rotates scheduler execution order across repetitions to reduce fixed-order and JVM warmup bias. These are still whole-program JVM timings, not a substitute for a dedicated JMH/JFR or cross-machine benchmark layer. Small timing differences should therefore be treated cautiously.

`maxQueueSize` is useful as a structural memory proxy, but it is not a heap-allocation measurement. Allocation rate, retained heap, GC behavior, cache effects, and hardware counters require later instrumentation.

## Validation and evidence status

The intended test gate now covers velocity TOI, accelerated TOI, elastic head-on conservation across all resolvers, stale-event behavior, CADQ simultaneous tie batching, CADQ local invalidation, deterministic/valid workload construction, large-cradle bounds, and a multi-workload/multi-seed differential scheduler matrix.

Historical smoke observations from the first laboratory milestone remain useful only as a pre-optimization baseline: all-pairs sparse 10 balls/1 s performed 85 TOI queries with no contacts; global heap sparse 100 performed 5,865 queries and resolved 3 contacts in 20.6 ms; the original safeguarded CADQ adversarial 100 performed 92,700 queries, 900 reselections, and 88.5% stale pops in 44.6 ms on a local Windows 11 / Microsoft OpenJDK 17 run.

Those measurements predate both local CADQ invalidation and workload sanitation. They must not be presented as results for the current implementation.

No new wall-clock performance result is claimed by this milestone. The execution environment used to prepare it does not provide Maven or outbound GitHub access to obtain a clean local checkout, and the repository's hosted Actions quota may be unavailable. The contribution of this pass is the evidence machinery and correctness gate needed to produce the next trustworthy dataset. Exact campaign procedure and interpretation rules are in [`../benchmarks/README.md`](../benchmarks/README.md).

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references: Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), DOI [10.1016/j.jcp.2006.06.042](https://doi.org/10.1016/j.jcp.2006.06.042); Bannerman et al., *DynamO* (2011), DOI [10.1002/jcc.21915](https://doi.org/10.1002/jcc.21915); and Johnson et al., *Reflections on Simultaneous Impact* ([paper index](https://www.cs.columbia.edu/cg/rosi/)).

This pass intentionally does not yet claim spatial broad phases, bucket calendars, JMH measurements, adaptive switching, property fuzzing, statistically aggregated timing conclusions, or million-ball scalability. Those require measured implementations rather than placeholders. JSONL remains the primary machine-readable dataset format.
