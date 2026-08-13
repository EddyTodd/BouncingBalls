# CADQ swept-spatial pruning falsification

## Question

After conservative temporal pruning substantially reduced exact CADQ pair-TOI work, the next hypothesis was that a spatial index over the same owner event horizon could remove still more candidates at 100/300/1000 bodies.

A naive current-position grid is not safe for an event-driven simulator: a fast or accelerated body can cross many cells before the owner's next retained event. The experiment therefore used a conservative swept query rather than static proximity.

This note preserves the experiment because the implementation was physically correct and achieved very large geometric exclusion rates, yet **made the target timing metric substantially worse**. The code was not accepted.

## Conservative prototype

At scheduler synchronization points, all body positions describe the same simulation time. The prototype built a uniform grid over those current centers. For an owner with exact event horizon `h`, a candidate could be omitted only when its current center lay outside the owner's per-axis swept envelope.

For the X axis, the query half-width was conservatively bounded by

`R_owner + R_max + (|vx_owner| + max |vx|) h + 0.5 (|ax_owner| + max |ax|) h^2 + slack`,

with the analogous Y expression. Here `R_max` and the velocity/acceleration maxima are over every body in the system. Non-finite state, overflow, an unavailable finite horizon, or unusable grid geometry failed open to the complete canonical candidate set.

The grid was rebuilt at construction and after each physical event batch. Full CADQ owner reselections used:

1. exact wall TOIs to obtain the initial owner horizon;
2. the swept-grid query to obtain a conservative canonical subset;
3. the already-accepted temporal reachability proof on that subset;
4. exact TOI only for candidates surviving both filters.

Local changed-pair refreshes remained temporal-only because their candidate sets were already small.

Regression tests exercised randomized velocity/acceleration states and verified that every exact collision occurring inside the supplied horizon remained in the swept query. Targeted high-speed and differential-acceleration contacts were also retained.

## Causal A/B design

The experimental branch ran the **same implementation with only swept spatial pruning switched on or off**. Existing temporal pruning remained enabled on both sides, so the comparison measured the grid's incremental value rather than re-crediting the accepted temporal mechanism.

For 100/300 bodies the campaign used eight workloads, including genuine differential-acceleration/quartic motion, three seeds, one warmup, four measured repetitions, and 0.25 simulated seconds. For 1000 bodies it used six workloads, two seeds, one measured repetition, and 0.10 simulated seconds.

All four campaign files passed the independent all-pairs physical-history oracle:

- 100/300 spatial off: 576 measured scheduler trials, 0 physical failures, 0 execution failures, 0 drift warnings;
- 100/300 spatial on: 576 measured scheduler trials, 0 physical failures, 0 execution failures, 0 drift warnings;
- 1000 spatial off: 36 measured scheduler trials, 0 physical failures, 0 execution failures, 0 drift warnings;
- 1000 spatial on: 36 measured scheduler trials, 0 physical failures, 0 execution failures, 0 drift warnings.

Evidence artifact:

- Actions run: `31690445774`
- artifact: `9177447701`
- digest: `sha256:4fad8d6c9077242b6036f2ac3d18d003081c1ecc78233acea5219a159b35d0a6`

## Timing result: rejected

Matched campaign analysis normalizes CADQ against GLOBAL inside each campaign and then compares the exact workload/seed/repetition keys.

| Bodies | Total factor | Approx. 95% bootstrap interval | Construction factor | Advance factor |
|---:|---:|---:|---:|---:|
| 100 | **1.215** | `1.130–1.300` | **1.211** | 1.112 (`0.993–1.239`) |
| 300 | **1.315** | `1.205–1.440` | **1.314** | 1.125 (`0.980–1.270`) |
| 1000 | **2.013** | `1.626–2.467` | **1.980** | 1.055 (`0.792–1.339`) |

Thus total engine time regressed by approximately:

- **21.5% at 100 bodies**;
- **31.5% at 300 bodies**;
- **101.3% at 1000 bodies**.

The construction regression becomes dramatically worse with system size. Advance point estimates were also worse, although their intervals crossed parity.

## Why the apparently successful broad phase lost

The mechanism counters explain the result more strongly than the timing alone.

Median CADQ exact TOI queries were unchanged:

| Bodies | Temporal-only TOI | Swept-grid TOI | Change |
|---:|---:|---:|---:|
| 100 | 732.5 | 732.5 | **0%** |
| 300 | 2,105.5 | 2,105.5 | **0%** |
| 1000 | 7,906 | 7,906 | **0%** |

The grid did not remove a single additional median exact solve. Instead, it replaced cheap temporal-bound rejections:

- 100-body median temporal checks: `1,632.5 -> 332.5`;
- 300-body median temporal checks: `13,758.5 -> 905.5`;
- 1000-body median temporal checks: `155,698 -> 3,906`.

Corresponding temporal-prune medians fell to zero because the outer grid had already discarded those same candidates.

Direct mechanism probes made the redundancy visually impressive but economically misleading. Representative geometric exclusion rates were:

- sparse 100: **93.8%**;
- dense 300: **98.5%**;
- sparse 1000: **99.1%**;
- high-velocity 1000: **99.8%**;
- differential acceleration 1000: **97.1%**.

Those percentages initially look like a successful spatial broad phase. They are not. Almost all of those pairs were already rejected by the much cheaper scalar temporal inequality. The grid paid for cell construction, cell traversal, candidate collection, sorting, and repeated rebuild/query bookkeeping without saving exact root solving.

This is an important methodological result: **candidate-count reduction is not itself an optimization when a cheaper existing predicate already rejects the same candidates.**

## What this falsifies

The experiment rejects the tested architecture:

> build a conservative swept current-center grid, query it before the existing temporal bound, and use the grid primarily as another rejection layer.

It does **not** prove that all spatial indexing is useless for event-driven collision scheduling. A spatial structure could still be valuable if it supplies information the temporal scan does not already have—for example, cheaply finding one promising nearby pair that tightens the owner horizon before the full temporal scan, or supporting a fundamentally different scheduler whose candidate generation no longer scans every owner pair.

However, the current evidence raises the acceptance bar for another spatial structure: it must demonstrate additional exact-work reduction or asymptotically replace work, not merely report high geometric exclusion percentages.

## Immediate follow-on hypothesis

The failed grid exposed a cheaper geometric fact that does add information to the existing radial bound.

At circle contact, each coordinate separation individually satisfies

`|dx(t)| <= R` and `|dy(t)| <= R`.

Therefore contact by horizon `h` is impossible if either

`|dx(0)| > R + |dvx| h + 0.5 |dax| h^2`

or

`|dy(0)| > R + |dvy| h + 0.5 |day| h^2`,

with the same conservative numerical slack and fail-open policy.

The accepted radial predicate uses a combined L1 displacement allowance. Large possible motion on X can therefore make the radial test inconclusive even when Y provably cannot close, and vice versa. The axis proof costs only constant-time arithmetic, requires no index or rebuild, and directly captures the useful part of the spatial experiment.

That axis-separable temporal bound is the next causal experiment. The swept-grid implementation itself remains rejected and is intentionally absent from the accepted production branch.
