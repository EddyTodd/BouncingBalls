# Collision algorithm research note

## Model and numerical policy

Bodies are circles with independent radius, mass, restitution, position, velocity, and constant acceleration. For relative state `r,v,a`, contact solves `|r + vt + ½at²|² - (R1+R2)² = 0`. This is quartic; velocity-only motion reduces to quadratic. `PolynomialRoots` isolates real roots through derivative partitions and bisection with centralized scale-aware tolerances. This makes the trajectory model exact, but not the IEEE-754 root approximation.

## Scheduling strategies

`ALL_PAIRS_CCD` is the small-system correctness reference. It rebuilds every pair and wall prediction after every trajectory-changing batch.

`GLOBAL_EVENT_QUEUE` stores predicted events in one generation-validated heap. Changed bodies add replacement predictions; invalid old entries are discarded lazily. This avoids global rebuilding but can accumulate stale entries and a comparatively large queue.

`COMPUTE_AHEAD_DEPENDENCY_QUEUE` (CADQ) is the repository's compute-ahead hypothesis: retain only the currently relevant earliest predictions, track the bodies on which they depend, and recompute only the portion of the prediction graph invalidated by a trajectory change.

### CADQ evolution

The first CADQ implementation stored one earliest prediction per body plus reverse dependencies, but fully reselected every body after a trajectory change as a correctness safeguard. Its own adversarial smoke result—92,700 TOI queries, 900 reselections, and 88.5% stale pops for 100 balls—falsified that implementation as an optimization.

The next version removed the all-owner safeguard. It retained the complete earliest-time tie set per owner, fully recomputed changed/dependent owners, and tested only changed bodies from otherwise unaffected owners. This established local invalidation and correct simultaneous-contact batching, but every unordered ball pair was still evaluated from both endpoints.

The current version adds **canonical pair ownership**. For a ball-ball pair `(a,b)`, exactly one body owns the prediction: the lower-id body. Body ids are therefore required to be unique and `Simulation` enforces that invariant.

### Current CADQ invariant

For each owner `u`:

- only canonically owned pairs `u-v` with `u.id < v.id` are considered;
- all four wall predictions remain owned by `u`;
- the owner retains the complete set of events tied for its earliest time;
- reverse dependencies map each referenced body to owners whose retained sets currently depend on it.

When a set `C` changes trajectory:

1. every body in `C` is fully reselected over the pairs it canonically owns plus its walls;
2. every owner whose retained set references a body in `C` is fully reselected;
3. predictions between two unchanged trajectories remain valid;
4. each remaining owner tests only changed bodies whose pair it canonically owns.

Canonical ownership is safe for compute-ahead selection. Suppose owner `u` has an omitted, later prediction against `v`. If `u`'s retained event occurs first, `u` changes and is reselected before the omitted event can occur. If `v` changes first, `u-v` is either invalidated through a retained dependency or tested through the changed-body refresh path. No second endpoint owner is required merely to preserve the event.

The tie-set requirement remains essential. A body may have multiple physically distinct contacts at the same earliest time; all of those canonical edges must survive so simultaneous collision islands are not truncated.

### Work model

With `N` bodies, the initial CADQ pair scan is now exactly `N(N-1)/2` ball-ball TOI queries rather than `N(N-1)`, plus `4N` wall queries.

For an update with `k` changed bodies and `d` additional reverse-dependency owners, full reselections still cost up to quadratic work in the affected owner set, while local refreshes test only canonically owned changed pairs. Worst-case dependency fan-out can still approach a full rebuild. The optimization is therefore judged empirically, not by a blanket asymptotic claim.

## Simultaneous contacts and structural correctness

Events within `NumericalPolicy.sameTime` are advanced together, deduplicated into physical ball-pair/wall identities, partitioned into ball-sharing islands, then resolved deterministically. Sequential is the ordering-sensitive baseline. Iterative uses forward/reverse projected Gauss-Seidel. Direct constructs a coupled normal-impulse system and falls back when singular or nonphysical.

The simulator now records a deterministic **physical contact-history fingerprint** in addition to counts. The fingerprint is order-sensitive between event batches and order-insensitive inside one simultaneous batch. It is a diagnostic, not a cryptographic proof, but it distinguishes “same collision topology with floating-point drift” from “scheduler missed or reordered a physical contact.”

Regression coverage includes simultaneous three-body contact, scheduler-independent physical event budgeting, canonical pair ownership, directional invalidation, large/high-speed contact-history equivalence, and duplicate-body-id rejection.

## Differential validation methodology

For each scenario, `CampaignCli` regenerates the same deterministic initial state for every scheduler. `ALL_PAIRS_CCD` supplies the reference trajectory.

Validation deliberately separates two questions:

1. **Physical scheduler correctness.** The measured run must have the same resolved-contact count, deduplicated physical-contact count, simultaneous-batch count, and contact-history fingerprint as the reference.
2. **Numerical reproducibility.** Final simulation time, positions, and velocities are compared at a strict state tolerance. A second, explicitly larger drift ceiling bounds scheduler-dependent floating-point path divergence.

A run is accepted only if physical history matches and final state remains inside the drift ceiling. Failure of the tighter state comparison is emitted as `numericalDriftWarning`; it is not silently discarded or mislabeled as a missed collision.

This distinction was introduced empirically. The first 630-trial campaign found 30 strict state mismatches, all confined to 100-ball high-speed/wall-heavy scenarios. GLOBAL and CADQ showed the same small coordinate drift from all-pairs while resolving the same contacts. A targeted regression then verified identical contact counts, batch counts, and contact-history fingerprints in those cases. The campaign now preserves that numerical fact instead of weakening the single tolerance until the warning disappears.

## Workload validity and provenance

Randomized workloads use deterministic rejection sampling and are validated before simulation: body ids are unique, state values are finite, every body begins inside the bounds, and no pair begins penetrated. Deliberately constructed workloads may start exactly touching when the topology requires it. `NEWTON_CRADLE` expands its domain for large requested counts.

Changing workload generation changes the experiment population. Historical numbers from the older generator remain historical observations and must not be mixed with current campaign measurements without labeling the provenance difference.

## Timing definition

The single-run and campaign tools separate:

- workload generation time where recorded, excluded from scheduler comparisons;
- `constructionNanos`, including `Simulation` construction and initial scheduler predictions;
- `advanceNanos`, timing `Simulation.advance(...)`;
- `totalEngineNanos = constructionNanos + advanceNanos`.

Campaigns perform configurable warmups and rotate scheduler execution order across repetitions. These are whole-program JVM measurements, not JMH microbenchmarks. Small timing differences should be treated cautiously.

`maxQueueSize` is a structural memory proxy, not measured allocation or retained heap. Allocation rate, GC behavior, cache effects, and hardware counters require later instrumentation or the shared benchmark system.

## First post-optimization campaign

A bounded campaign on a GitHub-hosted Ubuntu 24.04 runner with Temurin Java 17 tested seven randomized workload families, 20 and 100 requested balls, three seeds, one warmup, five measured repetitions, and one simulated second. It produced 42 scenarios and **630 measured trials**.

Validation result after canonical pair ownership:

- physical correctness failures: **0**;
- execution failures: **0**;
- strict numerical-drift warnings: **30**;
- all warnings remained inside the drift ceiling and preserved identical physical contact histories.

The important mechanism result is deterministic: canonical ownership nearly halved CADQ's TOI work. Examples at 100 balls:

| Workload | CADQ TOI | Global TOI | CADQ max queue | Global max queue |
|---|---:|---:|---:|---:|
| Accelerated | 6,276 | 6,277 | 103 | 249 |
| Sparse | 6,173 | 6,174 | 103 | 247 |
| Dense | 7,789 | 7,410 | 120 | 328 |
| High velocity | 15,772 | 15,238 | 133 | 372 |
| Wall dominated | 11,841 | 11,530 | 124 | 329 |
| Adversarial invalidation | 7,556 | 7,204 | 117 | 297 |

Before canonical ownership, the same campaign shape observed CADQ TOI counts of 12,116 accelerated, 11,914 sparse, 15,435 dense, 31,050 high velocity, 23,293 wall dominated, and 14,726 adversarial. Timing across separate hosted-runner executions is only indicative, but these operation counts directly demonstrate the mechanism change.

Within the final campaign, CADQ remained generally slower than the global heap despite similar TOI work. At 100 balls the median total-engine gap was roughly 2% accelerated, 13% adversarial, 11% clustered, 17% dense, 19% high velocity, 45% sparse, and 12% wall dominated. CADQ simultaneously kept a much smaller global queue and often a lower stale-pop fraction.

**Current conclusion:** collision prediction is no longer the main CADQ deficit. The next hypothesis is data-structure/bookkeeping overhead—hash maps, hash sets, object-heavy retained-event lists, and owner/dependency maintenance. The next CADQ milestone should replace those structures with dense simulation-slot data where possible and re-run the same evidence campaign before introducing a spatial broad phase. This keeps the optimization path causal and measurable.

These hosted-runner timings are campaign evidence, not a universal performance claim. Cross-machine replication and dedicated statistical benchmarking remain required before publishing general speed conclusions.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references: Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), DOI [10.1016/j.jcp.2006.06.042](https://doi.org/10.1016/j.jcp.2006.06.042); Bannerman et al., *DynamO* (2011), DOI [10.1002/jcc.21915](https://doi.org/10.1002/jcc.21915); and Johnson et al., *Reflections on Simultaneous Impact* ([paper index](https://www.cs.columbia.edu/cg/rosi/)).

The repository does not yet claim a spatial broad phase, calendar/bucket queue, adaptive scheduler, JMH/JFR allocation results, cross-machine statistical conclusions, or million-ball scalability. Those should be added only with measured implementations and preserved evidence.
