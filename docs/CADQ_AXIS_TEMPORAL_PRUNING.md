# CADQ axis-separable temporal pruning

## Result

This milestone tested two successive broad-phase hypotheses on top of the already-accepted CADQ radial temporal bound.

1. A conservative swept uniform grid was physically correct and geometrically aggressive, but **falsified as an optimization**: it removed essentially no additional exact TOI work and made total engine time substantially worse.
2. The useful geometric fact exposed by that failed grid was then reduced to a constant-time **axis-separable temporal proof**. That proof removed substantial additional exact pair work and produced reproducible construction, advance, and total-engine improvements.

The swept-grid failure is preserved separately in [`CADQ_SPATIAL_PRUNING_FALSIFICATION.md`](CADQ_SPATIAL_PRUNING_FALSIFICATION.md). This note documents the accepted follow-on.

## Why the original radial bound leaves work on the table

For owner horizon `h`, the accepted temporal bound uses an upper bound on total relative displacement:

`|v h + 0.5 a h^2| <= |v| h + 0.5 |a| h^2`.

The implementation deliberately uses L1 velocity and acceleration norms. That is conservative and cheap, but it mixes the axes. Large possible motion on X can make the scalar bound inconclusive even when the bodies provably cannot close their Y separation before the owner event, and vice versa.

The swept-grid experiment made this redundancy measurable: it excluded 94–99% of geometrically impossible pairs at scale, yet median exact TOI work was unchanged because almost every excluded pair was already rejected by the scalar temporal test. Paying for grid rebuilds, cell traversal, candidate collection, and sorting was therefore pure overhead.

The right follow-on was not a more elaborate grid. It was to extract the extra one-dimensional proof directly.

## Conservative axis proof

At circle contact, center distance is at most the combined radius `R`. Therefore each coordinate separately must satisfy

`|dx(t)| <= R`

and

`|dy(t)| <= R`.

Under piecewise constant acceleration,

`dx(t) = dx0 + dvx t + 0.5 dax t^2`.

By the triangle inequality, the maximum amount by which the X separation can close during horizon `h` is bounded by

`|dvx| h + 0.5 |dax| h^2`.

Consequently, contact by `h` requires

`|dx0| <= R + |dvx| h + 0.5 |dax| h^2`.

The identical condition holds for Y. If **either** axis fails its condition, contact before the horizon is impossible.

The implementation adds the same scale-aware `NumericalPolicy` slack used by the radial proof and fails open for negative, infinite, NaN, or overflowed horizons/state. Thus a rejection remains a conservative proof; a non-rejection still proceeds to the radial proof and, if necessary, exact TOI.

The production order is:

1. axis-separable proof;
2. historical radial/L1 proof;
3. exact quadratic or quartic TOI only for survivors.

No index, allocation, sorting, or scheduler data structure is added.

For causal research the axis layer can be disabled at JVM startup while retaining the accepted radial layer:

```bash
-Dbouncingballs.cadqAxisTemporalPruning=false
```

The default is enabled.

## Correctness evidence

Regression coverage includes:

- velocity contact accepted at its exact TOI horizon;
- genuine quartic differential-acceleration contact accepted at its exact TOI horizon;
- a constructed cross-axis case that the radial L1 bound admits but the axis proof correctly rejects;
- 2,000 deterministic randomized accelerated pair states, checking every finite exact collision within five simulated seconds against the axis proof at the exact TOI.

The process-level A/B campaign additionally ran each scheduler against the independent `ALL_PAIRS_CCD` contact-history oracle.

For 100/300 bodies, each axis-disabled and axis-enabled campaign contained **576 measured scheduler trials**. For the 1000-body probe each side contained **18 measured scheduler trials**. Every campaign produced:

- physical correctness failures: **0**;
- execution failures: **0**;
- numerical-drift warnings: **0**;
- campaign passed: **true**.

An accidental second complete workflow run independently repeated the same A/B and also passed all correctness gates. Because it changed only research documentation between experiment commits, it is useful as process-level timing replication rather than a different algorithm population.

## Mechanism result

The number of temporal candidates checked is unchanged; the axis layer simply proves more of those same candidates unreachable before the exact owner horizon.

First complete run, pooled CADQ medians:

| Bodies | Exact TOI, radial only | Exact TOI, radial + axis | Change | Temporal checks | Temporal prunes, radial only -> axis |
|---:|---:|---:|---:|---:|---:|
| 100 | 3,282.5 | 1,927.5 | **-41.3%** | 5,556 -> 5,556 | 2,987 -> 4,334 |
| 300 | 27,108.5 | 13,835.5 | **-49.0%** | 65,189 -> 65,189 | 39,650 -> 52,507 |
| 1000 | 154,079.5 | 66,083 | **-57.1%** | 665,439.5 -> 665,439.5 | 551,191.5 -> 620,568 |

The direct initial-horizon probe explains why. Beyond candidates already rejected by the old radial proof, the axis condition rejects another approximately **26–30% of all canonical pairs** across sparse, dense, high-velocity, and genuine differential-acceleration workloads at 100/300/1000 bodies.

This is not merely a cheap-quadratic effect. For `DIFFERENTIAL_ACCELERATION`, where every pair is genuinely quartic, median CADQ quartic solves changed:

| Bodies | Radial only | Radial + axis | Change |
|---:|---:|---:|---:|
| 100 | 2,275 | 979 | **-57.0%** |
| 300 | 19,204 | 7,710 | **-59.9%** |
| 1000 | 151,260 | 58,116 | **-61.6%** |

Thus the new predicate directly avoids expensive quartic root-isolation work, not just additional quadratic candidates.

## Timing evidence

The A/B compared the same branch in separate JVM processes with only `bouncingballs.cadqAxisTemporalPruning` changed. Existing radial temporal pruning remained enabled on both sides. Results were normalized through the repository's matched `CADQ/GLOBAL` comparison, then bootstrapped over exact workload/seed/repetition keys.

### First complete run

For 100/300 bodies: eight workloads including `DIFFERENTIAL_ACCELERATION`, three seeds, one warmup, four measured repetitions, and 0.25 simulated seconds. There were 96 matched CADQ/GLOBAL observations per size.

| Bodies | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 100 | **0.765** (`0.709–0.824`) | **0.797** (`0.731–0.872`) | **0.779** (`0.726–0.837`) |
| 300 | **0.737** (`0.697–0.779`) | **0.769** (`0.726–0.814`) | **0.747** (`0.707–0.787`) |

Point-estimate reductions are approximately:

- 100 bodies: **23.5% construction, 20.3% advance, 22.1% total**;
- 300 bodies: **26.3% construction, 23.1% advance, 25.3% total**.

For 1000 bodies, six workloads, one seed, and one repetition produced six matched observations:

- construction factor `0.826` (`0.593–1.100`);
- advance factor **`0.792` (`0.647–0.891`)**;
- total factor `0.810` (`0.605–1.009`).

The first 1000-body total/construction intervals are too wide to establish those two timing effects independently; the advance interval is below parity.

### Independent process replication

The accidental second complete run reproduced the 100/300 result and strengthened the small 1000-body probe:

| Bodies | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 100 | **0.809** (`0.753–0.867`) | **0.798** (`0.733–0.869`) | **0.816** (`0.762–0.874`) |
| 300 | **0.798** (`0.751–0.843`) | **0.820** (`0.774–0.866`) | **0.804** (`0.759–0.848`) |
| 1000 | **0.751** (`0.568–0.919`) | **0.801** (`0.625–0.941`) | **0.776** (`0.587–0.938`) |

The 1000-body population is still only six matched observations per run and should be described as scale evidence rather than a precise estimate. The important point is that the larger effect does not disappear under replication.

## Provenance

First accepted A/B evidence:

- GitHub Actions run: `31691581974`
- artifact: `9177829256`
- digest: `sha256:d8ea7f53b2fb25785da097f36e5bf9f47913b69eba99d3b197dc014489f99a36`

Independent process replication:

- GitHub Actions run: `31691678121`
- artifact: `9177875301`
- digest: `sha256:7d6345c1c74f0ae3efa3f9062203aaf7d710b5b44deee0cf9491827e20957bbc`

The research workflow was removed before merge; raw JSONL remains in the artifacts rather than being committed as machine-independent benchmark truth.

## Interpretation

The spatial-grid failure and axis-bound success together sharpen the repository's optimization rule:

> Do not optimize for the number of candidates a new structure can reject. Optimize for **additional expensive work eliminated after accounting for predicates that already exist**.

The swept grid reported spectacular geometric exclusion percentages but removed no median exact TOI work. The axis predicate reports no data structure at all, yet eliminates roughly half of remaining pair root solves at 100–1000 bodies because it contributes genuinely new information to the existing radial proof.

This also raises the bar for future spatial indexing. A spatial structure should not be added merely to pre-filter the same pairs temporal pruning already rejects. It should either:

- find an earlier promising event cheaply enough to tighten the owner horizon before scanning the rest;
- replace an asymptotically larger candidate-generation operation rather than layering on top of it;
- or demonstrate additional exact-work/allocation reduction not available from constant-time temporal predicates.

The next research direction should therefore measure **horizon tightening / candidate ordering** before another general spatial index, while allocation/GC evidence and cross-machine replication remain important independent tracks.
