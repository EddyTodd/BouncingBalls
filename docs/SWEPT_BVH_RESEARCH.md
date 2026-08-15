# Swept-AABB BVH research

## Question

After `SWEEP_AND_PRUNE_CCD` established that a standalone conservative spatial broad phase can replace rebuild-all-pairs efficiently, the next question was whether a hierarchical representation of the **same swept boxes** could enumerate candidates more efficiently than a one-axis sweep.

This experiment deliberately does **not** change the collision mathematics. `SWEEP_AND_PRUNE_CCD` and `SWEPT_BVH_CCD` share one `SweptAabb` implementation for the conservative horizon and trajectory envelope. The only intended variable is candidate enumeration:

- SAP sorts by swept `minX`, maintains an active X set, then rejects Y-disjoint pairs;
- BVH builds a binary hierarchy over the same swept X/Y boxes and queries overlapping leaves.

Every surviving pair still goes through the same exact TOI solver.

## Conservative envelope

For each rebuild, both schedulers first compute exact wall TOIs. The earliest finite wall event is expanded by numerical slack to form a conservative horizon. A ball-ball event that can beat or tie that wall event must occur inside this horizon.

For each axis under constant acceleration,

`p(t) = p0 + v0 t + 0.5 a t^2`,

`SweptAabb` evaluates both endpoints and the interior parabolic extremum when `-v/a` lies inside the horizon. The resulting positional interval is expanded by the ball radius and numerical slack. If a finite conservative horizon or finite box cannot be represented, the broad phase fails open to canonical all-pairs CCD.

Because SAP and BVH consume the same boxes, equal exact candidate counts are an explicit experimental invariant rather than an expected coincidence.

## BVH implementation

The accepted implementation is a rebuild-on-trajectory-change binary BVH:

- flat reusable node arrays avoid per-rebuild node allocation;
- leaves are the shared swept AABBs;
- the widest centroid axis is selected at each node;
- a linear midpoint partition is used normally;
- badly imbalanced partitions fall back to a deterministic median sort to bound depth;
- each leaf queries the hierarchy, and stable body id ordering emits each unordered overlapping leaf pair once;
- exact pair TOI is evaluated only after leaf overlap.

`SimulationStats` records rebuilds, canonical pairs, nodes built, maximum depth, node visits, exact pair candidates, and all-pairs fallbacks.

## Correctness gate

Before timing, PR #18 expanded the permanent differential matrix so both SAP and BVH are checked against `ALL_PAIRS_CCD` across every deterministic workload and seed already used by the scheduler regression suite.

Hosted CI run **#285** at head `681d5d39ab99fa7407516033fe76d39ce9618e72` passed **34/34 tests**. The permanent BVH mechanism regressions additionally prove two boundary cases:

1. a finite short wall horizon reduces 190 canonical pairs to zero exact pair candidates in a deliberately separated construction;
2. a state with no finite conservative wall horizon deliberately falls back to all-pairs.

The timing campaigns added stronger pairwise invariants between SAP and BVH. Every paired execution had to preserve:

- final state equivalence inside the existing drift gate;
- physical-contact batch count;
- physical-contact count;
- deterministic contact-history hash;
- exact broad-phase candidate count;
- exact pair-TOI query count.

A violation would invalidate timing rather than be averaged into the result.

## Naive BVH baseline

The first hierarchy used allocated node objects plus recursive copied/sorted sublists. This was kept only long enough to expose implementation cost; it is not the accepted implementation.

Hosted CI run **#287** at head `ed4209e090e2800f74752d38f2f3fd383cab168e` used:

- seven stochastic workloads;
- 100 and 300 bodies;
- seeds 1–3;
- 0.25 simulated seconds;
- two warmups and seven measured paired repetitions;
- alternating SAP/BVH execution order;
- per-scenario medians, summarized with geometric factors.

A factor greater than 1 means BVH was slower than SAP.

| Population | BVH / SAP geometric factor | BVH wins |
|---|---:|---:|
| 100 bodies | 1.970318 | 0 / 21 |
| 300 bodies | 2.186609 | 0 / 21 |
| combined | **2.075648** | **0 / 42** |

This was not accepted as an architectural conclusion because object allocation, repeated sublist copying, and recursive sorting were avoidable confounders.

## Optimized rebuild result

The BVH was then rewritten around reusable flat arrays and mostly linear midpoint partitioning. No collision, envelope, candidate, or benchmark semantics changed.

The identical 100/300-body protocol was rerun in hosted CI run **#289** at head `c36fe4fe3876a568153195c61502ac744dbfa66f`.

| Population | BVH / SAP geometric factor | BVH wins |
|---|---:|---:|
| 100 bodies | **1.170270** | 2 / 21 |
| 300 bodies | **1.275374** | 0 / 21 |
| combined | **1.221693** | **2 / 42** |

The implementation cleanup therefore mattered substantially: the combined disadvantage fell from about 2.08x to about 1.22x. Two 100-body scenarios crossed below parity, but the population result still favored SAP.

Optimized workload-level geometric factors across both sizes were:

| Workload | BVH / SAP factor |
|---|---:|
| sparse uniform | 1.265617 |
| dense uniform | 1.350391 |
| clustered | 1.084664 |
| high velocity | 1.323495 |
| wall dominated | 1.344368 |
| accelerated | 1.067534 |
| adversarial invalidation | 1.153599 |

Clustered and accelerated motion came closest to parity, which motivated a larger-body scaling check rather than stopping at 300 bodies.

## 1000-body scaling check

A separate scaling appendix used the same seven workloads and seeds 1–3 at 1000 bodies, still at 0.25 simulated seconds. Because these scenarios are much more expensive, the predeclared protocol used one warmup and five measured paired repetitions. It is therefore reported separately rather than pooled with the 100/300-body campaign.

Hosted CI run **#291** at head `1c3407b5a9f11a14ff1e07c1f967ae75996f6120` passed all correctness/candidate invariants and produced:

| Workload | BVH / SAP factor | BVH wins |
|---|---:|---:|
| sparse uniform | 1.434644 | 0 / 3 |
| dense uniform | 1.226649 | 0 / 3 |
| clustered | 1.127527 | 0 / 3 |
| high velocity | 1.388235 | 0 / 3 |
| wall dominated | 1.375224 | 0 / 3 |
| accelerated | 1.079238 | 0 / 3 |
| adversarial invalidation | 1.125584 | 0 / 3 |
| **all 1000-body scenarios** | **1.243665** | **0 / 21** |

There is no observed rebuild-BVH/SAP crossover through 1000 bodies in this tested 2D population.

## Conclusion

The tested hypothesis was:

> A spatial hierarchy over the same conservative swept AABBs will recover its extra build/traversal cost through more efficient candidate enumeration as body count increases.

**This hypothesis is not supported through 1000 bodies.** After removing obvious implementation confounders, the BVH remains slower than SAP in aggregate and loses every 1000-body scenario.

The result is unusually clean because the two schedulers perform the same exact pair-TOI work and produce the same physical histories. Under this rebuild-on-change 2D design, the hierarchy is paying build and node-traversal overhead without reducing the exact candidate set relative to the simpler sweep.

`SWEPT_BVH_CCD` remains in the repository as a correct, instrumented architectural comparator. Keeping it is useful for the collection objective and for future dimensionality/workload research; removing a slower valid architecture would erase evidence.

This result does **not** falsify all BVH-family approaches. In particular it does not answer:

- a persistent/dynamic AABB tree updated incrementally instead of rebuilt after every event batch;
- different BVH construction heuristics when candidate sets are not fixed to the same swept rectangles;
- higher-dimensional collision spaces, where sweep behavior and hierarchy tradeoffs change;
- nonuniform shape families rather than equal-type circles.

The next standalone broad-phase experiment should again isolate one variable. A swept spatial hash/uniform grid scheduler can consume the same `SweptAabb` envelopes and be compared against SAP and BVH without layering the grid redundantly in front of CADQ. The earlier failed CADQ grid does not answer that standalone question.
