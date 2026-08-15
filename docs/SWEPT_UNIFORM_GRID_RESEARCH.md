# Swept uniform-grid CCD research

## Question

After sweep-and-prune (SAP) established a successful standalone conservative broad phase and the rebuilt swept BVH failed to beat SAP through 1000 bodies, this milestone asks whether a **uniform spatial grid** can enumerate the same swept-AABB intersections more efficiently.

The experiment deliberately holds collision mathematics fixed. `SWEEP_AND_PRUNE_CCD`, `SWEPT_BVH_CCD`, and `SWEPT_UNIFORM_GRID_CCD` all consume the same `SweptAabb` conservative horizon and trajectory envelopes. Every surviving pair goes through the same exact TOI solver.

The intended variable is therefore candidate-enumeration structure:

- SAP: one-axis sorted sweep plus active set and Y rejection;
- swept BVH: rebuilt binary hierarchy and node traversal;
- uniform grid: multi-cell insertion, bucket pair enumeration, primitive pair deduplication, then exact swept-AABB overlap rejection.

## Accepted grid design

The accepted implementation uses a parameter-free density-derived square cell size:

`cellSize = sqrt(worldArea / bodyCount)`.

No named workload or benchmark result selects a tuning multiplier.

For each rebuild:

1. exact wall TOIs establish the shared finite conservative horizon;
2. each body receives the same conservative `SweptAabb.Box` used by SAP/BVH;
3. every box is inserted into every grid cell it touches;
4. memberships are encoded as packed primitive `long` values `(cellId << 32) | bodyIndex` and sorted by cell id;
5. unordered pairs sharing a cell are enumerated;
6. a reusable primitive long set deduplicates pairs that share multiple cells;
7. the original swept boxes are tested for actual 2D overlap;
8. only those exact box overlaps reach pair TOI prediction.

The grid fails open to canonical all-pairs CCD if the conservative horizon is unavailable or grid/membership dimensions cannot be represented safely.

`SimulationStats` records cell memberships, occupied cells, bucket pair attempts, duplicate pair attempts, unique cell pairs, swept-AABB rejections, exact pair candidates, maximum bucket occupancy, rebuilds, and fallbacks.

## Correctness gate

Before timing, the permanent test suite added the grid to the all-pairs differential matrix and added explicit mechanism regressions:

- a finite short wall horizon reduces 190 canonical pairs to zero exact grid candidates;
- absence of a finite wall horizon deliberately falls back to all-pairs;
- a constructed pair that shares multiple grid cells is deduplicated, and the resulting grid exact-candidate and pair-TOI counts match SAP.

Hosted CI run **#297** passed **37/37 tests** before any performance claim was accepted.

The research campaigns imposed stronger pairwise invariants. For every SAP/BVH/grid execution used in timing, the three schedulers had to preserve:

- equivalent final state inside the existing drift gate;
- identical physical-contact batch count;
- identical physical-contact count;
- identical deterministic physical-contact-history hash;
- identical exact swept-AABB candidate count;
- identical pair-TOI query count.

Thus timing differences are candidate-enumeration overhead rather than different exact collision work.

## 100/300-body three-way campaign

The first timing campaign used:

- seven stochastic workloads: sparse, dense, clustered, high velocity, wall dominated, accelerated, adversarial invalidation;
- 100 and 300 bodies;
- seeds 1–3;
- 0.25 simulated seconds;
- two warmups and seven measured repetitions;
- rotating execution order among SAP, BVH, and grid;
- per-scenario medians summarized by geometric factors.

A factor below 1 means the grid is faster than the named comparator.

### Density-derived cell result

| Population | Grid / SAP | Grid wins vs SAP | Grid / BVH | Grid wins vs BVH |
|---|---:|---:|---:|---:|
| 100 bodies | 1.128387 | 3 / 21 | 0.966154 | 10 / 21 |
| 300 bodies | 0.990776 | 10 / 21 | 0.814109 | 17 / 21 |
| combined | **1.057345** | **13 / 42** | **0.886879** | **27 / 42** |

The grid is therefore already clearly better than the rebuilt BVH in this population and reaches approximate parity with SAP at 300 bodies, but does not beat SAP over the combined 100/300 population.

Workload-level grid/SAP factors across both sizes were:

| Workload | Grid / SAP | Wins |
|---|---:|---:|
| sparse uniform | 0.784227 | 4 / 6 |
| dense uniform | 0.969906 | 3 / 6 |
| clustered | 1.144563 | 0 / 6 |
| high velocity | 1.108838 | 1 / 6 |
| wall dominated | 1.211791 | 2 / 6 |
| accelerated | 1.027183 | 3 / 6 |
| adversarial invalidation | 1.229597 | 0 / 6 |

Mechanism counters explain the clustered/adversarial losses. At 300 bodies, clustered seed 3 accumulated 54,951 cell memberships, 336,761 bucket-pair attempts, and 188,221 duplicate pair attempts before arriving at the same 87,046 exact swept-AABB candidates as SAP/BVH. The grid is not missing or adding exact candidates; it is paying extra work to discover them repeatedly through dense buckets.

## Rejected envelope-mean sizing refinement

The density rule appeared too coarse inside local clusters, so one parameter-free refinement was tested rather than tuning a workload-specific constant.

For each axis, the proposed cell extent was:

`min(sqrt(worldArea / bodyCount), mean swept-envelope extent on that axis)`.

The hypothesis was that locally small envelopes should receive finer cells while large high-velocity envelopes should preserve the global density scale.

The identical 100/300 campaign rejected this design:

| Population | Refined grid / SAP | Refined grid / BVH |
|---|---:|---:|
| 100 bodies | 1.451359 | 1.180310 |
| 300 bodies | 1.178300 | 0.919837 |
| combined | **1.307722** | **1.041966** |

The refinement moved cost rather than removing it. Finer cells reduced some bucket occupancy, but high-velocity and wall-dominated bodies touched far more cells, increasing membership construction and sorting enough to overwhelm the benefit. The change was reverted, and the simpler density-derived rule remains the accepted implementation.

This negative result is important: cell resolution cannot be judged by bucket occupancy alone. Membership volume and sorting cost are part of the mechanism.

## 1000-body scaling crossover

Because the density grid was near SAP parity at 300 bodies and substantially better than BVH, a separate 1000-body scaling protocol was justified.

The scaling appendix kept the same seven workloads, seeds 1–3, 0.25-second horizon, rotating three-way execution order, and strict candidate/history equivalence. Because these cases are much more expensive, it used one warmup and five measured repetitions and is reported separately rather than pooled with the 100/300 campaign.

Two independent GitHub Actions runs executed the identical commit and protocol on separate hosted workers.

### Replication A

| Workload | Grid / SAP | SAP wins? | Grid / BVH |
|---|---:|---:|---:|
| sparse uniform | 0.933085 | grid 3 / 3 | 0.631251 |
| dense uniform | 0.888826 | grid 3 / 3 | 0.760071 |
| clustered | 1.106074 | grid 0 / 3 | 1.004181 |
| high velocity | 0.879889 | grid 3 / 3 | 0.625866 |
| wall dominated | 0.876006 | grid 3 / 3 | 0.626645 |
| accelerated | 0.965017 | grid 3 / 3 | 0.894366 |
| adversarial invalidation | 1.111147 | grid 0 / 3 | 1.004458 |
| **all scenarios** | **0.961221** | **grid 15 / 21** | **0.776201** |

### Replication B

| Workload | Grid / SAP | SAP wins? | Grid / BVH |
|---|---:|---:|---:|
| sparse uniform | 0.875084 | grid 3 / 3 | 0.637627 |
| dense uniform | 0.901875 | grid 3 / 3 | 0.753264 |
| clustered | 1.138154 | grid 0 / 3 | 0.992613 |
| high velocity | 0.823910 | grid 3 / 3 | 0.611506 |
| wall dominated | 0.819147 | grid 3 / 3 | 0.609327 |
| accelerated | 0.961616 | grid 3 / 3 | 0.884487 |
| adversarial invalidation | 1.142383 | grid 0 / 3 | 0.999515 |
| **all scenarios** | **0.943580** | **grid 15 / 21** | **0.767621** |

Both runs therefore reproduce the same qualitative crossover:

- the density grid beats SAP overall at 1000 bodies;
- it wins all three seeds in five of seven workload families;
- clustered and adversarial invalidation remain consistently SAP-favorable;
- it beats rebuilt BVH strongly overall, although clustered/adversarial are essentially BVH/grid parity at this scale.

The 1000-body grid/SAP geometric factor is about 0.94–0.96 across the two hosted replications, versus 0.991 at 300 and 1.128 at 100. This is evidence of a scale crossover, not a universal grid victory.

## Why clustered/adversarial remain bad for the grid

The failure mode becomes extremely explicit at 1000 bodies. For clustered seed 1, the grid accumulates about:

- 2.03 million cell memberships;
- 19.1 million bucket pair attempts;
- 11.4 million duplicate pair attempts;
- 7.72 million unique cell-sharing pairs;
- 4.55 million exact swept-AABB candidates;
- maximum bucket occupancy 48.

SAP reaches the same exact candidate set without multi-cell pair duplication. The adversarial workload intentionally shares the clustered geometry and exhibits the same broad-phase mechanism counts.

This explains why the grid's favorable asymptotic trend in ordinary populations does not erase geometry-dependent pathologies.

## Conclusion

The tested hypothesis was:

> A parameter-free uniform grid over the same conservative swept AABBs can amortize candidate enumeration better than a sorted sweep as body count grows, while avoiding rebuilt-BVH traversal cost.

**The hypothesis is supported for the tested 1000-body aggregate population, with an important workload qualification.** The density-derived grid crosses from slower-than-SAP at 100 bodies, to near parity at 300, to faster overall at 1000 in two independent hosted runs. It is also consistently better than the rebuilt BVH in aggregate.

The repository should retain all three standalone swept broad phases:

- SAP remains simpler and is clearly superior in highly clustered/adversarial geometry;
- the uniform grid becomes the strongest tested rebuilt swept-box enumerator at 1000 bodies in ordinary sparse/dense/high-motion populations;
- the rebuilt BVH remains a valid negative comparator and an architectural stepping stone toward a dynamic tree.

No universal winner is claimed.

## Next experiment

The next architecture should be a **persistent/dynamic AABB tree**, not another rebuilt hierarchy. PR #18 only tested a BVH rebuilt after every trajectory-changing batch. A dynamic tree can preserve topology and update/refit only changed leaves, directly testing whether incremental spatial structure can recover the hierarchy's candidate-query properties without paying full rebuild cost after every event batch.

The clean comparison should preserve the same exact `SweptAabb` semantics where possible and separately measure:

- tree insertions/reinsertions/refits/rotations;
- nodes visited per query;
- changed-leaf fraction per event batch;
- exact candidate count and pair-TOI count;
- construction versus advance cost;
- memory/allocation behavior;
- sparse/dense/clustered/high-motion/adversarial workload behavior;
- scale through at least 1000 bodies when correctness permits.

The observed SAP/grid scale crossover and clustered grid failure also strengthen the case for continuing to keep multiple architectures rather than prematurely forcing one adaptive selector.
