# CADQ conservative temporal pruning

## Hypothesis

Advance-phase profiling showed that CADQ's remaining cost was concentrated in full owner reselection and local changed-pair refresh, not in heap validation or reverse-dependency discovery. Those regions repeatedly solve exact ball-ball time of impact (TOI).

The hypothesis was therefore narrower than a general spatial broad phase:

> once an owner has an exact earliest event at time `T`, reject a pair before exact TOI evaluation when a conservative displacement bound proves the two circles cannot reach contact by `T`.

This is a correctness-preserving broad phase, not a heuristic proximity filter.

## Conservative bound

Let current center offset be `r`, combined radius be `R`, relative velocity be `v`, relative acceleration be `a`, and candidate horizon be `t >= 0`.

The relative displacement over the horizon is

`v t + 0.5 a t^2`.

By the triangle inequality,

`|v t + 0.5 a t^2| <= |v| t + 0.5 |a| t^2`.

Therefore contact by time `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implementation is intentionally more conservative still:

- relative speed and acceleration use L1 norms, upper-bounding Euclidean norms without square roots;
- center distance is compared in squared form;
- the reachable radius is inflated using `NumericalPolicy` slack;
- the event horizon is inflated by tie-time slack so an event that could tie under `sameTime` is not pruned;
- NaN, infinite, overflowed, or otherwise ambiguous inputs fail open and execute exact TOI.

Consequently, `TemporalReachability.couldContactWithin(...) == false` proves that the pair cannot beat or tie the exact owner event. `true` means only "possibly reachable" and still requires exact TOI.

## CADQ integration

### Advance-phase acceptance

The first implementation deliberately left initial scheduler construction unchanged. That made the experiment causal: the new mechanism ran only in the previously profiled `advance()` path.

During a full owner reselection after a trajectory change:

1. evaluate the four wall TOIs first;
2. use the earliest wall as an exact finite upper bound when available;
3. send each canonically owned ball pair through temporal reachability;
4. skip exact pair TOI if contact is impossible by the current horizon;
5. tighten the horizon whenever an exact pair wins.

During local refresh, the unchanged owner already retains a valid exact earliest event, which supplies the horizon before testing changed-body pairs.

### Construction extension

After the advance-only mechanism passed correctness and timing acceptance, initial construction was tested as a separate milestone. Every owner has the same four exact wall predictions available at time zero, so initial selection can seed a horizon before scanning its canonically owned pairs. The same conservative proof applies; only the lifecycle phase changes.

The accepted implementation now uses temporal pruning during **both construction and advance**. The research toggle remains:

```bash
-Dbouncingballs.cadqTemporalPruning=false
```

## TOI degree: an important workload correction

Ball-ball polynomial degree depends on **relative acceleration**.

For relative state `r,v,a`, contact solves

`|r + vt + 0.5at^2|^2 - R^2 = 0`.

If `a != 0`, the quartic coefficient is positive and the pair equation is genuinely quartic. If two bodies have exactly the same acceleration, their relative acceleration is zero and the equation reduces to a quadratic even though each world-space trajectory is accelerated.

This exposed an imprecision in the original temporal-pruning interpretation. The existing `ACCELERATED` workload applies the same gravity `(0,-9.81)` to every body. Its high temporal-prune rate was genuine evidence of candidate reduction, but it was **not evidence of avoiding quartic ball-ball solves**, because uniform gravity cancels from pair-relative motion.

The repository now distinguishes:

- `ACCELERATED`: shared gravity, quadratic ball-ball TOI;
- `DIFFERENTIAL_ACCELERATION`: shared gravity plus a deterministic unique horizontal acceleration per body, guaranteeing nonzero relative acceleration for every generated pair and therefore genuine quartic pair TOI.

`SimulationStats` now records:

- `pairToiQueries`;
- `quadraticPairToiQueries`;
- `quarticPairToiQueries`;
- `wallToiQueries`.

The accounting invariants are

`toiQueries = pairToiQueries + wallToiQueries`

and

`pairToiQueries = quadraticPairToiQueries + quarticPairToiQueries`.

## Advance-phase evidence

### Process-level replication

The larger enabled/disabled replication used seven workload families, 20/100 balls, five seeds, two warmups, ten measured repetitions, and one simulated second. Each side produced **2,100 measured scheduler trials**:

- physical correctness failures: **0**;
- execution failures: **0**;
- numerical-drift warnings: **80** on each side;
- campaign passed: **true** on each side.

At 100 balls:

- exact TOI median: `7,556 -> 6,754` (**-10.6%**);
- normalized total-engine factor: **0.963** (`0.935–0.991`);
- normalized advance factor: **0.748** (`0.724–0.772`).

At 20 balls, exact TOI work fell about 2% and normalized advance improved about 5.6% (`0.898–0.996`).

Separate process runs showed contradictory construction shifts even though pruning was intentionally inactive during construction. Instead of assigning a mechanism to an impossible effect, the experiment was tightened.

### Same-JVM interleaved A/B

The final advance acceptance experiment alternated enabled/disabled order every repetition inside one JVM. Every adjacent pair had to match contact count, batch count, contact-history fingerprint, and bounded final state before its timing was retained. It produced **700 paired measurements per ball count**.

| Balls | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

Construction is consistent with parity, as the design predicts. The causal target improves at both sizes. At 100 balls, splitting by execution order produced advance factors around `0.737` when enabled ran first and `0.730` when enabled ran second.

## Initial-construction scale experiment

The next experiment compared:

- **baseline:** accepted master with temporal pruning only after construction;
- **candidate:** the same implementation with temporal pruning also enabled during initial owner selection.

Both checkouts ran on the same Ubuntu 24.04 hosted runner and each campaign retained the independent all-pairs physical-history oracle.

For 100 and 300 balls, the campaign used seven workloads, two seeds, one warmup, two measured repetitions, and 0.25 simulated seconds. For 1000 balls, it used five workloads, one seed, no warmup, one measured repetition, and 0.10 simulated seconds.

All four campaign files passed:

- physical correctness failures: **0**;
- execution failures: **0**;
- numerical-drift warnings: **0** in this bounded population.

Matched CADQ/GLOBAL factors were:

| Balls | Construction factor | Total-engine factor | Advance factor |
|---:|---:|---:|---:|
| 100 | **0.737** (`0.642–0.849`) | **0.789** (`0.695–0.894`) | 1.015 (`0.905–1.134`) |
| 300 | **0.565** (`0.512–0.619`) | **0.665** (`0.611–0.718`) | 0.953 (`0.819–1.118`) |
| 1000 | **0.438** (`0.340–0.578`) | **0.576** (`0.476–0.695`) | 0.987 (`0.903–1.061`) |

Thus normalized construction improved about **26.3%**, **43.5%**, and **56.2%** at 100/300/1000 bodies. Total engine improved about **21.1%**, **33.5%**, and **42.4%**. The unchanged advance phase remained statistically compatible with parity.

The deterministic mechanism moved in the expected direction as N increased. At 1000 bodies, median CADQ exact TOI queries fell from **538,147 to 152,575 (-71.6%)**. The 1000-body timing result has only five matched observations, so it is useful crossover/scale evidence, not a precise universal estimate.

### Harness failure caught and discarded

The first attempt at the scale campaign was invalid and is intentionally recorded as such. The baseline checkout had not been compiled, so `CampaignCli` failed with `ClassNotFoundException`. Because the command was piped through `tee` without `set -o pipefail`, Maven's nonzero exit status was masked, and the original file-presence gate was too weak to detect the missing baseline JSONL.

No performance conclusion uses that run.

The corrected workflow:

- compiled both baseline and candidate checkouts;
- used `set -o pipefail` for piped Maven/Python commands;
- required exactly the four expected campaign JSONL files;
- failed if any final campaign summary did not pass.

Valid scale evidence provenance:

- GitHub Actions run: `31686901547`;
- artifact: `9175894829`;
- digest: `sha256:99b15429e13ff5eb2546ae823ba51d82f169b3225a16aa1aff657de34acb0d1c`.

The discarded harness run was `31686628738`; its artifact is retained only as a provenance record and must not be used for performance claims.

## True-quartic validation evidence

A dedicated campaign validated the new degree counters and workload semantics across all three continuous schedulers:

- workloads: `ACCELERATED`, `DIFFERENTIAL_ACCELERATION`;
- balls: 20 and 100;
- three seeds;
- one warmup;
- three measured repetitions;
- 0.5 simulated seconds;
- **108 measured scheduler trials**.

Results:

- physical correctness failures: **0**;
- execution failures: **0**;
- numerical-drift warnings: **0**;
- all TOI accounting invariants passed.

Representative medians:

| Workload / balls / scheduler | Pair TOI | Quadratic | Quartic | CADQ temporal prunes |
|---|---:|---:|---:|---:|
| shared gravity / 100 / all-pairs | 19,800 | 19,800 | 0 | — |
| shared gravity / 100 / GLOBAL | 5,445 | 5,445 | 0 | — |
| shared gravity / 100 / CADQ | 1,839 | 1,839 | 0 | 3,610 |
| differential acceleration / 100 / all-pairs | 19,800 | 0 | 19,800 | — |
| differential acceleration / 100 / GLOBAL | 5,445 | 0 | 5,445 | — |
| differential acceleration / 100 / CADQ | 2,351 | 0 | 2,351 | 3,171 |

This establishes the missing mechanism evidence: temporal pruning does avoid many exact **genuine quartic** pair queries under differential acceleration. It does not by itself establish a separate timing speedup for that workload; timing claims still require a matched acceptance experiment.

Validation provenance:

- GitHub Actions run: `31687633334`;
- artifact: `9176054588`;
- digest: `sha256:458939724ec33bcb270c9d01460062796370387d3722f7c111f2757a09a4dd03`.

## Interpretation

The temporal-pruning sequence now supports two causal claims for the tested hosted-runner populations:

1. pruning exact pair candidates during `advance()` reduced exact work and improved advance/total wall-clock time;
2. applying the same proven bound during initial selection sharply reduced construction exact-TOI work, with the timing benefit increasing through 1000 bodies.

The workload correction also changes the next research question. “Accelerated” is not sufficient shorthand for “quartic.” Future cost-aware policies must classify relative motion or directly use the degree counters rather than infer solver cost from world-frame acceleration.

## Next hypotheses

The next investigations should build on conservative candidate reduction:

1. **Swept spatial indexing over the owner horizon.** Use conservative swept cells/AABBs; a current-position grid can miss fast future collisions.
2. **Cost-aware temporal pruning under true quartic workloads.** Measure whether a more selective hierarchy pays off differently for quadratic and quartic candidates.
3. **Larger-N crossover refinement.** Repeat 1000+ body measurements with more matched samples and additional machines/JVMs.
4. **Memory/allocation evidence.** Measure allocation and GC explicitly instead of inferring memory behavior from queue size.

Any spatial extension must remain conservative under high velocity and differential acceleration.

## Evidence policy

Raw hosted-runner JSONL and diagnostic artifacts remain attached to completed GitHub Actions runs rather than being committed as universal benchmark truth. The repository records experiment shape, correctness gates, mechanism counters, matched comparison method, invalid-run provenance, and conclusions. Cross-machine replication is still required before machine-independent performance claims.
