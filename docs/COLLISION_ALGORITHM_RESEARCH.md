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

Canonical pair ownership then assigned every ball-ball pair `(a,b)` to exactly one endpoint: the lower-id body. `Simulation` therefore requires unique ids. This nearly halved CADQ pair-prediction work and brought TOI counts close to the global heap, but CADQ remained slower. That result falsified collision prediction as the primary remaining bottleneck and shifted the hypothesis toward dependency bookkeeping.

The current version replaces the hot hash/object representation with **dense simulation-local bookkeeping** while preserving arbitrary unique public ids:

- bodies are sorted once by stable id and assigned dense local slots;
- retained earliest-time tie sets use arrays indexed by owner slot;
- reverse dependencies use `BitSet`s indexed by referenced-body slot;
- the heap stores `CollisionEvent` directly rather than CADQ wrapper objects;
- target slots are retained in parallel primitive arrays;
- a primitive open-addressed id-to-slot table avoids boxed lookup in queue validation and invalidation.

The public id space does not need to be dense, positive, or input-ordered. Regression coverage explicitly exercises sparse, negative, and unsorted ids.

### Current CADQ invariant

For each owner `u`:

- only canonically owned pairs `u-v` with `u.id < v.id` are considered;
- all four wall predictions remain owned by `u`;
- the owner retains the complete set of events tied for its earliest time;
- reverse dependencies identify owners whose retained sets currently reference each body.

When a set `C` changes trajectory:

1. every body in `C` is fully reselected over the pairs it canonically owns plus its walls;
2. every owner whose retained set references a body in `C` is fully reselected;
3. predictions between two unchanged trajectories remain valid;
4. each remaining owner tests only changed bodies whose pair it canonically owns.

Canonical ownership is safe for compute-ahead selection. Suppose owner `u` has an omitted, later prediction against `v`. If `u`'s retained event occurs first, `u` changes and is reselected before the omitted event can occur. If `v` changes first, `u-v` is either invalidated through a retained dependency or tested through the changed-body refresh path. A second endpoint owner is not required merely to preserve the event.

The tie-set requirement remains essential. A body may have multiple physically distinct contacts at the same earliest time; all of those canonical edges must survive so simultaneous collision islands are not truncated.

### Work model

With `N` bodies, the initial CADQ pair scan is exactly `N(N-1)/2` ball-ball TOI queries plus `4N` wall queries.

For an update with `k` changed bodies and `d` additional reverse-dependency owners, full reselections still cost up to quadratic work in the affected owner set, while local refreshes test only canonically owned changed pairs. Worst-case dependency fan-out can still approach a full rebuild. Dense bookkeeping changes constant factors and allocation/lookup behavior; it does not change that worst-case asymptotic bound.

## Simultaneous contacts and structural correctness

Events within `NumericalPolicy.sameTime` are advanced together, deduplicated into physical ball-pair/wall identities, partitioned into ball-sharing islands, then resolved deterministically. Sequential is the ordering-sensitive baseline. Iterative uses forward/reverse projected Gauss-Seidel. Direct constructs a coupled normal-impulse system and falls back when singular or nonphysical.

The simulator records a deterministic **physical contact-history fingerprint** in addition to counts. The fingerprint is order-sensitive between event batches and order-insensitive inside one simultaneous batch. It is a diagnostic, not a cryptographic proof, but it distinguishes “same collision topology with floating-point drift” from “scheduler missed or reordered a physical contact.”

Regression coverage includes simultaneous three-body contact, scheduler-independent physical event budgeting, canonical pair ownership, directional invalidation, large/high-speed contact-history equivalence, duplicate-body-id rejection, and sparse/non-contiguous id behavior under dense CADQ slots.

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

## Timing and campaign-comparison definition

The single-run and campaign tools separate:

- workload generation time where recorded, excluded from scheduler comparisons;
- `constructionNanos`, including `Simulation` construction and initial scheduler predictions;
- `advanceNanos`, timing `Simulation.advance(...)`;
- `totalEngineNanos = constructionNanos + advanceNanos`.

Campaigns perform configurable warmups and rotate scheduler execution order across repetitions. These are whole-program JVM measurements, not JMH microbenchmarks. Small timing differences should be treated cautiously.

`benchmarks/compare_campaigns.py` compares optimization campaigns on exact matched `(workload, requestedBalls, seed, repetition)` observations. For each observation it first calculates the within-campaign `CADQ/GLOBAL` timing ratio. Candidate and baseline ratios are then compared through log-ratio differences. The reported aggregate is the geometric mean and the interval is a deterministic non-parametric bootstrap over those matched log-ratio changes.

Normalizing to GLOBAL inside each run reduces shared hosted-runner/JVM variation, but it does not make different machines identical. Cross-machine replication remains necessary before generalizing a speed claim.

`maxQueueSize` is a structural memory proxy, not measured allocation or retained heap. Allocation rate, GC behavior, cache effects, and hardware counters require later instrumentation or the shared benchmark system.

## Empirical optimization sequence

The bounded campaign used for the current sequence runs on GitHub-hosted Ubuntu 24.04 with Temurin Java 17 and tests seven randomized workload families, 20/100 balls, three seeds, one warmup, five measured repetitions, and one simulated second: 42 scenarios and **630 measured trials** per campaign.

All accepted variants in this sequence had:

- physical correctness failures: **0**;
- execution failures: **0**;
- strict numerical-drift warnings: **30**;
- identical physical histories for those warnings and drift below the explicit ceiling.

### 1. Canonical ownership removed duplicated prediction work

Representative deterministic 100-ball operation counts after canonical ownership:

| Workload | CADQ TOI | Global TOI | CADQ max queue | Global max queue |
|---|---:|---:|---:|---:|
| Accelerated | 6,276 | 6,277 | 103 | 249 |
| Sparse | 6,173 | 6,174 | 103 | 247 |
| Dense | 7,789 | 7,410 | 120 | 328 |
| High velocity | 15,772 | 15,238 | 133 | 372 |
| Wall dominated | 11,841 | 11,530 | 124 | 329 |
| Adversarial invalidation | 7,556 | 7,204 | 117 | 297 |

Before canonical ownership, corresponding CADQ counts were approximately 12,116 accelerated, 11,914 sparse, 15,435 dense, 31,050 high velocity, 23,293 wall dominated, and 14,726 adversarial. The mechanism change therefore did what it was designed to do, but CADQ still lost on time. That was evidence against further TOI-focused optimization as the immediate next step.

### 2. Dense bookkeeping reduced the measured scheduler penalty

The canonical hash/object implementation and accepted dense primitive implementation were compared on the 105 matched 100-ball observations. Aggregate paired ratios were:

| Metric | Canonical CADQ/GLOBAL | Dense CADQ/GLOBAL | Dense/canonical factor | Relative change |
|---|---:|---:|---:|---:|
| total engine | 1.1477 | 1.0588 | 0.9225 | **-7.7%** |
| construction | 1.0328 | 0.9903 | 0.9589 | -4.1% |
| advance | 1.3539 | 1.2228 | 0.9032 | **-9.7%** |

A 20,000-resample matched bootstrap gave approximate 95% factor intervals:

- total engine: `0.869–0.978`;
- construction: `0.884–1.037`;
- advance: `0.855–0.954`.

Thus the data support a reduction in total and advance penalty for this campaign population; construction is consistent with parity. The dense implementation does **not** make CADQ universally faster than GLOBAL. Its aggregate 100-ball total ratio remains about `1.059`, and advance remains about `1.223`.

### 3. Intermediate implementations were retained as falsification evidence, not merged

The first dense rewrite used wrapper records around every retained/queued event and a boxed identity lookup. It reduced some hot-path work but failed to improve total engine time. The accepted variant removed those wrappers, returned the priority queue to direct `CollisionEvent` entries, used parallel primitive target-slot arrays, and added a primitive id-to-slot table.

A subsequent experiment reused changed/full `BitSet`s and lazily copied retained tie sets only when a refresh actually changed an owner. Two complete 630-trial replications preserved correctness and improved advance-time point estimates. However, neither produced a reproducible total-engine improvement over the accepted dense primitive implementation. That micro-optimization was reverted. This is deliberate: lower apparent operation count is not sufficient reason to retain complexity when the target metric does not confirm the benefit.

## Current conclusion and next hypothesis

The research path has now eliminated two measured CADQ deficits:

1. duplicate pair prediction work;
2. a substantial portion of object/hash bookkeeping overhead.

The remaining 100-ball deficit is concentrated inside `advance()`: CADQ performs roughly GLOBAL-level TOI work and construction is near parity, yet its aggregate advance ratio is still around `1.223`. The next milestone should therefore **instrument and isolate advance-time scheduler subphases before changing the algorithm again**. The leading candidates are:

- valid/stale heap-entry validation and CADQ's extra retained-owner membership check;
- reverse-dependency insertion/removal;
- full-owner reselection versus local changed-pair refresh traversal;
- retained tie-set copying/mutation and per-batch temporary structures.

A spatial broad phase remains premature. It would change prediction work even though prediction counts are no longer the measured primary deficit, making the experiment less causal. The next optimization should be selected only after subphase evidence identifies the dominant remaining CADQ cost.

These hosted-runner timings are campaign evidence, not a universal performance claim. Cross-machine replication and dedicated statistical benchmarking remain required before publishing general speed conclusions.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references: Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), DOI [10.1016/j.jcp.2006.06.042](https://doi.org/10.1016/j.jcp.2006.06.042); Bannerman et al., *DynamO* (2011), DOI [10.1002/jcc.21915](https://doi.org/10.1002/jcc.21915); and Johnson et al., *Reflections on Simultaneous Impact* ([paper index](https://www.cs.columbia.edu/cg/rosi/)).

The repository does not yet claim a spatial broad phase, calendar/bucket queue, adaptive scheduler, JMH/JFR allocation results, cross-machine statistical conclusions, or million-ball scalability. Those should be added only with measured implementations and preserved evidence.
