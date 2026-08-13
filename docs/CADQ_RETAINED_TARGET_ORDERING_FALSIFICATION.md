# Retained-target candidate ordering: falsification record

## Hypothesis

After axis-separable plus radial temporal pruning had removed much of CADQ's exact pair work, the next question was whether a full owner reselection could tighten its exact horizon earlier by probing the owner's previously retained pair target before scanning the remaining canonical candidates.

The experimental warm start was deliberately small: wall TOIs were evaluated exactly as accepted `master` does; one previously retained canonical pair target was recovered when available; that target passed through the accepted conservative temporal predicate and, if it survived, was exact-evaluated before the ordinary canonical scan; its normal scan position was skipped so it was never exact-evaluated twice in one reselection. No sorting, allocation, spatial structure, or persistent scheduler data structure was added. Construction had no prior retained target and was unchanged by design.

The hypothesis was that collision-partner persistence would often produce an early exact pair event, shrink the owner's horizon, and let the existing axis/radial proofs reject substantially more later candidates.

## Important correction discovered during the experiment

Reordering a candidate does **not** imply that exact TOI work can only stay equal or decrease. If the retained target normally appears late in the canonical scan, an earlier-index candidate can first tighten the baseline horizon enough that the retained target is later pruned without an exact solve. A warm start can instead exact-probe that target before the tightening occurs.

Therefore the valid invariant is only that the retained target is not evaluated twice in the same reselection. The net exact-work effect must be measured.

## First one-second scale attempt: protocol finding, not candidate evidence

A first 100/300-body attempt used eight stochastic workloads, three seeds, one warmup, four measured repetitions, and one simulated second. The baseline (`cadqWarmStartOrdering=false`) failed the existing final-state drift ceiling before the candidate ran.

The baseline summary contained 576 measured trials, 40 drift-ceiling failures, zero execution failures, and **identical physical contact histories to ALL_PAIRS for every failing GLOBAL and CADQ trial**. All 40 failures were 300-body `HIGH_VELOCITY` or `WALL_DOMINATED` cases. One representative high-velocity seed accumulated 337 physical contacts with the exact reference contact-history fingerprint while scheduler-dependent floating-point state drift exceeded the old absolute ceiling.

This is not a collision-history failure. It shows that the existing absolute state-drift ceiling cannot simply be extrapolated to longer, larger, collision-rich trajectories. The threshold was not loosened to make the experiment pass. Instead, the A/B returned to the 0.25-second horizon already used by the accepted axis-pruning milestone.

Provenance for the hardened failed baseline attempt:

- run `31708413871`;
- artifact `9184275167`;
- digest `sha256:eed66e8c101638c90efd21a959e542887a2d28437f5647be563f1ab302362ec9`.

An earlier run `31707556587` failed at the same baseline stage but did not preserve partial artifacts and is not used quantitatively.

## Valid 100/300-body A/B

The corrected causal population used eight workloads (`SPARSE_UNIFORM`, `DENSE_UNIFORM`, `CLUSTERED`, `HIGH_VELOCITY`, `WALL_DOMINATED`, `ACCELERATED`, `DIFFERENTIAL_ACCELERATION`, `ADVERSARIAL_INVALIDATION`), 100/300 balls, three seeds, one warmup, four measured repetitions, 0.25 simulated seconds, and the iterative resolver. Accepted temporal and axis pruning were enabled on both sides.

Each side produced 576 measured scheduler trials with **zero physical correctness failures, zero execution failures, and zero numerical-drift warnings**. There were 96 matched CADQ observations per size.

## Mechanism result: essentially no useful horizon tightening

At 100 balls, aggregate exact pair TOI work changed by only **-0.023%**. Of 96 matched observations, 16 used fewer pair TOIs, 72 were unchanged, and 8 used more. Median pair TOI changed only `1495.5 -> 1494.0`; the median paired difference was zero.

At 300 balls, aggregate exact pair TOI work changed by only **-0.49%**. Of 96 matched observations, 48 used fewer pair TOIs, 28 were unchanged, and 20 used more. Median pair TOI changed `12395.5 -> 12391.5`, a median paired reduction of only 3.5 exact queries.

The strongest workload-level aggregate changes at 300 bodies were:

| Workload | Baseline pair TOI | Warm start | Change |
|---|---:|---:|---:|
| `ADVERSARIAL_INVALIDATION` | 215,952 | 213,428 | **-1.17%** |
| `CLUSTERED` | 215,952 | 213,428 | **-1.17%** |
| `WALL_DOMINATED` | 188,508 | 188,072 | -0.23% |
| `HIGH_VELOCITY` | 235,416 | 234,952 | -0.20% |
| `DENSE_UNIFORM` | 95,796 | 95,712 | -0.09% |
| `ACCELERATED` | 66,280 | 66,280 | 0.00% |
| `DIFFERENTIAL_ACCELERATION` | 92,976 | 92,980 | approximately 0.00% |
| `SPARSE_UNIFORM` | 122,412 | 122,416 | approximately 0.00% |

The previously retained target is therefore not a strong enough predictor of the next useful horizon after the accepted axis/radial predicates.

## Timing result

The process-level normalized comparison was also unfavorable:

| Balls | Total factor | Construction factor | Advance factor |
|---:|---:|---:|---:|
| 100 | 1.0125 (`0.9619–1.0659`) | 1.0150 (`0.9628–1.0706`) | 0.9830 (`0.9197–1.0502`) |
| 300 | **1.0732 (`1.0404–1.1123`)** | **1.0757 (`1.0384–1.1196`)** | **1.0594 (`1.0301–1.0905`)** |

The 300-body process run measured a regression. Construction is algorithmically unchanged by this advance-only idea yet also moved materially, so process/JVM variation is still visible. Timing is therefore supporting evidence, not the primary rejection criterion.

The decisive result is deterministic mechanism evidence: **less than half a percent aggregate pair-TOI reduction at 300 bodies and effectively zero at 100 bodies**. There is no justification for retaining extra state/branch complexity or spending another replication campaign trying to rescue a mechanism that barely changes the expensive-work count.

Valid A/B provenance:

- run `31708890827`;
- artifact `9184773548`;
- digest `sha256:89975ce58e7148936aee3ca6b916e2bfbc84600ee05bd32d593c300814d62fbe`.

## 1000-body probe boundary

The same run attempted a separate 1000-body probe on `SPARSE_UNIFORM`, `HIGH_VELOCITY`, and `DIFFERENTIAL_ACCELERATION`. The baseline again exceeded the existing drift ceiling in `HIGH_VELOCITY` before the candidate ran. The four failing measured baseline trials were two GLOBAL and two CADQ repetitions of the same seed; all preserved the exact all-pairs physical contact history and had zero execution failures.

This supplies another numerical-drift-scale observation, not candidate performance evidence. No 1000-body warm-start claim is made.

## Decision

**Reject and revert retained-target warm-start ordering.** The experimental scheduler code, counters, test, JVM property, and temporary workflow were not retained in the production branch. Only this falsification/protocol record remains.

The experiment narrows the search space:

- collision-partner persistence is too weak a ranking signal after current pruning;
- moving one historical target earlier does not tighten horizons enough;
- some reorderings increase exact work because baseline order would have pruned that target later;
- candidate ordering must be judged by incremental exact-work elimination, not intuitive locality.

A stronger next hypothesis should estimate **earliest reachable time**, not reuse target identity alone. A small allocation-free probe set based on conservative lower bounds is a better candidate than a full sort. Before timing, any future ordering experiment should first demonstrate a material reduction in exact pair TOIs; a sub-1% effect at 300 bodies is not enough.

The large-N drift observations also motivate a separate future validation-protocol milestone: keep exact physical-contact-history equivalence as the structural gate while modeling numerical state drift as a trajectory/event-sensitivity diagnostic instead of silently increasing one global absolute tolerance.