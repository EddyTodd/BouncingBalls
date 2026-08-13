# Collision algorithm research note

## Model and numerical policy

Bodies are circles with independent radius, mass, restitution, position, velocity, and constant acceleration. For relative state `r,v,a`, contact solves `|r + vt + ½at²|² - (R1+R2)² = 0`. This is quartic; velocity-only motion reduces to quadratic. `PolynomialRoots` isolates real roots through derivative partitions and bisection with centralized scale-aware tolerances. This makes the trajectory model exact, but not the IEEE-754 root approximation.

## Scheduling strategies

`ALL_PAIRS_CCD` is the small-system correctness reference. It rebuilds every pair and wall prediction after every trajectory-changing batch.

`GLOBAL_EVENT_QUEUE` stores predicted events in one generation-validated heap. Changed bodies add replacement predictions; invalid old entries are discarded lazily. This avoids global rebuilding but can accumulate stale entries and a comparatively large queue.

`COMPUTE_AHEAD_DEPENDENCY_QUEUE` (CADQ) is the repository's compute-ahead hypothesis: retain only the currently relevant earliest predictions, track the bodies on which they depend, and recompute only the portion of the prediction graph invalidated by a trajectory change.

## CADQ evolution

The first CADQ implementation stored one earliest prediction per body plus reverse dependencies, but fully reselected every body after a trajectory change as a correctness safeguard. Its own adversarial smoke result—92,700 TOI queries, 900 reselections, and 88.5% stale pops for 100 balls—falsified that implementation as an optimization.

The next version removed the all-owner safeguard. It retained the complete earliest-time tie set per owner, fully recomputed changed/dependent owners, and tested only changed bodies from otherwise unaffected owners. This established local invalidation and correct simultaneous-contact batching, but every unordered ball pair was still evaluated from both endpoints.

Canonical pair ownership then assigned every ball-ball pair `(a,b)` to exactly one endpoint: the lower-id body. `Simulation` therefore requires unique ids. This nearly halved CADQ pair-prediction work and brought TOI counts close to the global heap, but CADQ remained slower. That result falsified duplicated collision prediction as the primary remaining bottleneck and shifted the hypothesis toward dependency bookkeeping.

The accepted dense implementation then replaced hot hash/object bookkeeping with dense simulation-local state while preserving arbitrary unique public ids:

- bodies are sorted once by stable id and assigned dense local slots;
- retained earliest-time tie sets use arrays indexed by owner slot;
- reverse dependencies use `BitSet`s indexed by referenced-body slot;
- the heap stores `CollisionEvent` directly rather than CADQ wrapper objects;
- target slots are retained in parallel primitive arrays;
- a primitive open-addressed id-to-slot table avoids boxed lookup in queue validation and invalidation.

Advance-phase profiling subsequently showed that the dominant remaining regions were full reselection and local changed-pair refresh. Three micro-optimization hypotheses inside those regions failed the wall-clock acceptance gate. The accepted follow-on therefore attacks exact candidate work directly with a conservative temporal reachability bound.

The public id space does not need to be dense, positive, or input-ordered. Regression coverage explicitly exercises sparse, negative, and unsorted ids.

## Current CADQ invariant

For each owner `u`:

- only canonically owned pairs `u-v` with `u.id < v.id` are considered;
- all four wall predictions remain owned by `u`;
- the owner retains the complete set of events tied for its earliest time;
- reverse dependencies identify owners whose retained sets currently reference each body;
- once an exact earliest horizon is known, a pair may skip exact TOI only when a conservative reachability bound proves it cannot contact by that horizon, including tie-time slack.

When a set `C` changes trajectory:

1. every body in `C` is fully reselected over the pairs it canonically owns plus its walls;
2. every owner whose retained set references a body in `C` is fully reselected;
3. predictions between two unchanged trajectories remain valid;
4. each remaining owner tests only changed bodies whose pair it canonically owns;
5. full reselection evaluates the four wall TOIs first to establish an exact horizon when possible, then applies temporal reachability before exact pair TOI;
6. local refresh uses the unchanged owner's already-valid retained event as its exact horizon before testing changed pairs.

Canonical ownership is safe for compute-ahead selection. Suppose owner `u` has an omitted, later prediction against `v`. If `u`'s retained event occurs first, `u` changes and is reselected before the omitted event can occur. If `v` changes first, `u-v` is either invalidated through a retained dependency or tested through the changed-body refresh path. A second endpoint owner is not required merely to preserve the event.

Temporal pruning is also conservative. Over horizon `t`, relative displacement satisfies

`|v t + 0.5 a t^2| <= |v| t + 0.5 |a| t^2`.

Therefore current center separation `|r|` can reach the combined radius `R` only if

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implementation uses L1 norms for speed and acceleration, inflates the reachable distance with `NumericalPolicy` slack, inflates the horizon with tie-time slack, and fails open on non-finite/overflow cases. Rejection therefore means the pair is proved unable to beat or tie the exact retained horizon; acceptance still requires the exact quadratic/quartic TOI calculation.

The tie-set requirement remains essential. A body may have multiple physically distinct contacts at the same earliest time; all of those canonical edges must survive so simultaneous collision islands are not truncated.

### Work model

With `N` bodies, initial CADQ construction deliberately remains the exact `N(N-1)/2` ball-ball scan plus `4N` wall queries. Temporal pruning targets `advance()` so initialization remains a causal control.

For an update with `k` changed bodies and `d` additional reverse-dependency owners, full reselections still have a worst case approaching quadratic work, while local refreshes test only canonically owned changed pairs. Temporal pruning reduces the number of exact pair TOI solves when an existing owner horizon is restrictive enough; it does not improve the formal worst case because an adversarial geometry can make every bound inconclusive.

## Simultaneous contacts and structural correctness

Events within `NumericalPolicy.sameTime` are advanced together, deduplicated into physical ball-pair/wall identities, partitioned into ball-sharing islands, then resolved deterministically. Sequential is the ordering-sensitive baseline. Iterative uses forward/reverse projected Gauss-Seidel. Direct constructs a coupled normal-impulse system and falls back when singular or nonphysical.

The simulator records a deterministic **physical contact-history fingerprint** in addition to counts. The fingerprint is order-sensitive between event batches and order-insensitive inside one simultaneous batch. It is a diagnostic, not a cryptographic proof, but it distinguishes “same collision topology with floating-point drift” from “scheduler missed or reordered a physical contact.”

Regression coverage includes simultaneous three-body contact, scheduler-independent physical event budgeting, canonical pair ownership, directional invalidation, large/high-speed contact-history equivalence, duplicate-body-id rejection, sparse/non-contiguous id behavior under dense CADQ slots, velocity/acceleration temporal-bound safety, and an enabled/disabled temporal-pruning mechanism check.

## Differential validation methodology

For each scenario, `CampaignCli` regenerates the same deterministic initial state for every scheduler. `ALL_PAIRS_CCD` supplies the reference trajectory.

Validation deliberately separates two questions:

1. **Physical scheduler correctness.** The measured run must have the same resolved-contact count, deduplicated physical-contact count, simultaneous-batch count, and contact-history fingerprint as the reference.
2. **Numerical reproducibility.** Final simulation time, positions, and velocities are compared at a strict state tolerance. A second, explicitly larger drift ceiling bounds scheduler-dependent floating-point path divergence.

A run is accepted only if physical history matches and final state remains inside the drift ceiling. Failure of the tighter state comparison is emitted as `numericalDriftWarning`; it is not silently discarded or mislabeled as a missed collision.

The first 630-trial campaign found 30 strict state mismatches, all confined to 100-ball high-speed/wall-heavy scenarios. GLOBAL and CADQ showed the same small coordinate drift from all-pairs while resolving the same contacts. A targeted regression verified identical contact counts, batch counts, and contact-history fingerprints in those cases. The campaign preserves that numerical fact instead of weakening the single tolerance until the warning disappears.

## Workload validity and provenance

Randomized workloads use deterministic rejection sampling and are validated before simulation: body ids are unique, state values are finite, every body begins inside the bounds, and no pair begins penetrated. Deliberately constructed workloads may start exactly touching when the topology requires it. `NEWTON_CRADLE` expands its domain for large requested counts.

Changing workload generation changes the experiment population. Historical numbers from older generators remain historical observations and must not be mixed with current campaign measurements without labeling the provenance difference.

## Timing, comparison, and profiling definitions

The single-run and campaign tools separate:

- workload generation time where recorded, excluded from scheduler comparisons;
- `constructionNanos`, including `Simulation` construction and initial scheduler predictions;
- `advanceNanos`, timing `Simulation.advance(...)`;
- `totalEngineNanos = constructionNanos + advanceNanos`.

Campaigns perform configurable warmups and rotate scheduler execution order across repetitions. These are whole-program JVM measurements, not JMH microbenchmarks. Small timing differences should be treated cautiously.

`benchmarks/compare_campaigns.py` compares optimization campaigns on exact matched `(workload, requestedBalls, seed, repetition)` observations. For each observation it first calculates the within-campaign `CADQ/GLOBAL` timing ratio. Candidate and baseline ratios are then compared through log-ratio differences. The reported aggregate is the geometric mean and the interval is a deterministic non-parametric bootstrap over those matched log-ratio changes.

Normalizing to GLOBAL inside each run reduces shared hosted-runner/JVM variation, but it does not make different machines identical. Cross-machine replication remains necessary before generalizing a speed claim.

`CadqProfileCli` is a separate diagnostic tool. It inserts opt-in coarse `System.nanoTime()` probes into CADQ queue work, dependency discovery, full reselection, and local refresh. Those probes perturb execution, so profiler timings are used to select hypotheses, **not** to establish speedups. Every performance acceptance decision returns to uninstrumented runs.

The temporal-pruning milestone additionally used an interleaved same-JVM A/B because two separate-process campaigns showed impossible construction shifts despite the feature being inactive during initial construction. The A/B alternated enabled/disabled order every repetition and compared adjacent executions of the same scheduler/workload/seed. This separated the causal advance effect from process-order/JIT noise.

`maxQueueSize` is a structural memory proxy, not measured allocation or retained heap. `predictedEventMaterializations` is a mechanism counter for finite `CollisionEvent` construction, not a direct heap-allocation measurement. `cadqTemporalBoundChecks` and `cadqTemporalPrunes` distinguish cheap broad-phase work from exact pair TOI work.

## Empirical optimization sequence

The original bounded campaign uses GitHub-hosted Ubuntu 24.04 with Temurin Java 17 and tests seven randomized workload families, 20/100 balls, three seeds, one warmup, five measured repetitions, and one simulated second: 42 scenarios and **630 measured trials** per campaign. Larger replications state their own populations explicitly.

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

Before canonical ownership, corresponding CADQ counts were approximately 12,116 accelerated, 11,914 sparse, 15,435 dense, 31,050 high velocity, 23,293 wall dominated, and 14,726 adversarial. The mechanism change did what it was designed to do, but CADQ still lost on time.

### 2. Dense bookkeeping reduced the measured scheduler penalty

The canonical hash/object implementation and accepted dense primitive implementation were compared on the 105 matched 100-ball observations:

| Metric | Canonical CADQ/GLOBAL | Dense CADQ/GLOBAL | Dense/canonical factor | Relative change |
|---|---:|---:|---:|---:|
| total engine | 1.1477 | 1.0588 | 0.9225 | **-7.7%** |
| construction | 1.0328 | 0.9903 | 0.9589 | -4.1% |
| advance | 1.3539 | 1.2228 | 0.9032 | **-9.7%** |

A 20,000-resample matched bootstrap gave approximate 95% factor intervals:

- total engine: `0.869–0.978`;
- construction: `0.884–1.037`;
- advance: `0.855–0.954`.

Thus the data support a reduction in total and advance penalty for this campaign population; construction is consistent with parity. The dense implementation did not yet make CADQ universally faster than GLOBAL.

### 3. Earlier dense micro-variants were rejected

The first dense rewrite used wrapper records around every retained/queued event and a boxed identity lookup. It reduced some hot-path work but failed to improve total engine time. The accepted variant removed those wrappers, returned the priority queue to direct `CollisionEvent` entries, used parallel primitive target-slot arrays, and added a primitive id-to-slot table.

A later experiment reused changed/full `BitSet`s and lazily copied retained tie sets only when a refresh actually changed an owner. Two complete 630-trial replications preserved correctness and improved advance-time point estimates, but neither produced a reproducible total-engine improvement. That micro-optimization was reverted.

### 4. Advance profiling isolated the dominant regions

A 105-trial 100-ball diagnostic profile found approximate median phase shares of the **profiled scheduler regions**:

- queue work: **4.6%**;
- dependency discovery: **3.5%**;
- full reselection: **48.2%**;
- local refresh: **37.9%**.

The four probes covered roughly 72% of median whole `advance()` time. Full reselection plus local refresh therefore dominated the measured CADQ scheduler regions; queue validation and dependency discovery did not.

Representative medians were about 24 full owners visited, 877 local owners visited, only 4 local owners modified, 128 retained installs, and 7,556 TOI queries.

### 5. Three plausible advance micro-optimizations were falsified

Each candidate was compared with the accepted dense baseline using exact matched observations and a 20,000-resample bootstrap:

| Experimental change | 100-ball total factor (95% interval) | 100-ball advance factor (95% interval) | Decision |
|---|---:|---:|---|
| reuse retained owner buffers | 1.038 (`0.974–1.107`) | 1.000 (`0.931–1.070`) | reverted |
| skip local owners that cannot canonically own a changed pair | 1.042 (`0.982–1.108`) | 1.001 (`0.942–1.067`) | reverted |
| materialize `CollisionEvent`s only after owner selection | 1.033 (`0.969–1.102`) | 0.979 (`0.905–1.051`) | reverted |

All intervals span `1`. The experiments each improved or removed a real mechanism—array churn, provably useless owner visits, or throwaway event construction—but none demonstrated a reproducible improvement in the target timing metric. The implementations were reverted instead of accumulating complexity that the evidence did not justify.

Detailed phase counts and the full falsification log are in [`CADQ_ADVANCE_PROFILE.md`](CADQ_ADVANCE_PROFILE.md).

### 6. Conservative temporal pruning reduced exact candidate work

Temporal pruning was first evaluated in a process-level campaign and then replicated with process order reversed. The larger replication used seven workload families, 20/100 balls, five seeds, two warmups, ten measured repetitions, and one simulated second. Enabled and disabled campaigns each produced **2,100 measured scheduler trials** with zero physical correctness failures and zero execution failures.

At 100 balls:

- median exact TOI queries changed from `7,556` to `6,754` (**-10.6%**);
- normalized total-engine factor was **0.963** (`0.935–0.991`);
- normalized advance factor was **0.748** (`0.724–0.772`).

At 20 balls, exact TOI work fell only about 2%, but normalized advance still improved about 5.6% (`0.898–0.996`). Separate-process total/construction numbers were contradictory between process orders despite pruning being disabled during construction, so they were not accepted as causal evidence.

The final same-JVM interleaved A/B used **700 adjacent enabled/disabled CADQ pairs per ball count**, alternating execution order. Every pair had to match physical contact count, batch count, contact-history fingerprint, and bounded final state before timing analysis.

| Ball count | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

This resolves the construction artifact exactly as the mechanism predicts: initialization is parity, while the advance path improves. At 100 balls, enabled-first and enabled-second advance factors were about `0.737` and `0.730`, respectively.

A 105-trial enabled profile measured a median temporal prune rate of about **49.6%** at 100 balls, with workload medians ranging from roughly 37.7% to 78.5%. Accelerated workloads pruned most aggressively, which is valuable because a rejected accelerated candidate avoids the quartic root-isolation path.

Full proof, per-workload prune rates, all evidence passes, and follow-on hypotheses are in [`CADQ_TEMPORAL_PRUNING.md`](CADQ_TEMPORAL_PRUNING.md).

## Current conclusion and next hypotheses

The optimization sequence has now established four useful facts:

1. duplicate pair prediction was a real and large CADQ defect;
2. object/hash bookkeeping was a measurable secondary defect, and dense representation improved it;
3. simple queue/owner-loop/allocation micro-overhead did not explain the remaining advance gap;
4. conservative temporal candidate rejection **did** reduce the dominant exact-selection work and produced a reproducible advance/total improvement in the tested hosted-runner population.

The next experiments should build on that causal result rather than immediately replacing the scheduler with an unrelated spatial structure. High-value directions are:

- **cost-aware temporal pruning**, because accelerated quartic candidates benefit far more than some cheap velocity-only candidates;
- **swept spatial indexing** over the current owner horizon, not a static current-position grid;
- **larger-N crossover campaigns** at 300/1000+ bodies to locate the CADQ/GLOBAL regime boundary;
- **allocation/GC profiling** so structural queue-size evidence is not confused with actual memory cost.

A conventional current-position spatial grid is not automatically safe for event-driven future collisions, especially with high velocities or acceleration. Spatial indexing should be introduced only with swept/temporal bounds or another proof that no earlier valid event can be discarded.

These hosted-runner timings are campaign evidence, not universal performance claims. Cross-machine replication and dedicated statistical benchmarking remain required before publishing general speed conclusions.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references: Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), DOI [10.1016/j.jcp.2006.06.042](https://doi.org/10.1016/j.jcp.2006.06.042); Bannerman et al., *DynamO* (2011), DOI [10.1002/jcc.21915](https://doi.org/10.1002/jcc.21915); and Johnson et al., *Reflections on Simultaneous Impact* ([paper index](https://www.cs.columbia.edu/cg/rosi/)).

The repository does not yet claim a swept spatial broad phase, calendar/bucket queue, adaptive scheduler, JMH/JFR allocation results, cross-machine statistical conclusions, or million-ball scalability. Those should be added only with measured implementations and preserved evidence.
