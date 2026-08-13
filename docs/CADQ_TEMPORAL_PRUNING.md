# CADQ conservative temporal pruning

## Hypothesis

Advance-phase profiling showed that CADQ's remaining cost was concentrated in full owner reselection and local changed-pair refresh, not in heap validation or reverse-dependency discovery. Those dominant regions repeatedly solve exact ball-ball time of impact (TOI), including quadratic constant-velocity and quartic constant-acceleration cases.

The hypothesis for this milestone was therefore narrower than a general spatial broad phase:

> once an owner already has an exact earliest event at time `T`, reject a pair before exact TOI evaluation when a conservative displacement bound proves that the two circles cannot reach contact by `T`.

This is a correctness-preserving broad phase. It is not a heuristic proximity filter.

## Conservative bound

Let the current center offset be `r`, combined circle radius be `R`, relative velocity be `v`, relative acceleration be `a`, and candidate horizon be `t >= 0`.

The relative displacement over the horizon is

`v t + 0.5 a t^2`.

By the triangle inequality,

`|v t + 0.5 a t^2| <= |v| t + 0.5 |a| t^2`.

Therefore contact by time `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implementation is intentionally even more conservative:

- relative speed and acceleration use L1 norms, which upper-bound the Euclidean norms without square roots;
- center distance is compared in squared form, avoiding `sqrt`/`hypot`;
- the reachable radius is inflated using `NumericalPolicy` slack;
- the event horizon is inflated by additional time slack so an event that could tie under `sameTime` is not pruned;
- NaN, infinite, overflowed, or otherwise ambiguous inputs fail open and execute the exact TOI solver.

Consequently, `TemporalReachability.couldContactWithin(...) == false` is a conservative proof that the exact pair cannot beat or tie the current owner event. `true` means only "possibly reachable" and still requires exact TOI.

## CADQ integration

Initial scheduler construction deliberately retains the pre-existing exact pair scan. Temporal pruning targets the measured `advance()` deficit, so initialization remains unchanged and can still be compared causally.

During a full owner reselection after a trajectory change:

1. the four wall TOIs are evaluated first;
2. the earliest wall normally supplies an exact finite upper bound for that owner;
3. each canonically owned ball pair receives the cheap temporal reachability test;
4. pairs that cannot reach contact by the current exact horizon skip exact ball-ball TOI;
5. whenever an exact pair beats the horizon, the horizon tightens for subsequent candidates.

During a local refresh, the unchanged owner already retains a valid exact earliest event. That retained event is the horizon used to test changed-body pairs before exact TOI.

For research A/B runs the feature can be disabled without changing source code:

```bash
-Dbouncingballs.cadqTemporalPruning=false
```

The default is enabled.

## Mechanism counters

`SimulationStats` exposes:

- `cadqTemporalBoundChecks` — pair candidates sent through the temporal broad phase;
- `cadqTemporalPrunes` — candidates proved unable to reach contact by the owner horizon;
- `cadqTemporalPrunePercent()` — prune rate among temporal checks.

`LabCli`, `CampaignCli`, and `CadqProfileCli` expose the relevant counters in their evidence output. Campaign schema 3 also records whether temporal pruning was enabled.

A 105-trial 100-ball profile of the enabled implementation observed a median:

- temporal checks: **2,114**;
- temporal prunes: **802**;
- prune percentage: **49.6%**;
- exact TOI queries: **6,770**.

Median prune percentage varied strongly by workload:

| Workload | Median temporal prune rate |
|---|---:|
| accelerated | 78.5% |
| dense uniform | 56.4% |
| sparse uniform | 53.5% |
| high velocity | 49.6% |
| wall dominated | 49.4% |
| clustered | 37.7% |
| adversarial invalidation | 37.7% |

The high accelerated prune rate is especially important because avoided accelerated pair queries skip the quartic root-isolation path rather than only the cheaper velocity quadratic.

## Correctness evidence

Unit/regression coverage verifies:

- a constant-velocity pair is never rejected at its exact collision horizon;
- an accelerated pair is never rejected at its exact quartic collision horizon;
- a constructed far-pair case is actually pruned when a nearer wall supplies the horizon;
- disabling the feature removes the broad-phase checks and restores the larger exact-TOI count.

Two full process-level differential campaigns then compared enabled and disabled CADQ against the independent all-pairs correctness oracle. The larger reversed-order replication used seven workload families, 20/100 balls, five seeds, two warmups, ten measured repetitions, and one simulated second. Each side produced **2,100 measured scheduler trials**, with:

- physical correctness failures: **0**;
- execution failures: **0**;
- numerical-drift warnings: **80** on each side;
- campaign passed: **true** on each side.

The warnings were the already-understood strict floating-point drift cases: accepted runs preserved the reference physical contact history and remained inside the explicit drift ceiling.

An additional same-JVM A/B harness generated **700 adjacent enabled/disabled CADQ pairs per ball count**. Every pair was required to match on contact count, batch count, contact-history fingerprint, and bounded final state before its timing was retained.

## Performance evidence

### First process-level campaign

Using three seeds, one warmup, five repetitions, and 105 matched observations at each 100-ball scheduler ratio:

- exact TOI median: `7,556 -> 6,770` (about **-10.4%**);
- normalized 100-ball total-engine factor: **0.882** relative to disabled (`0.839–0.925` bootstrap interval);
- normalized 100-ball advance factor: **0.770** (`0.723–0.816`).

The candidate's aggregate 100-ball CADQ/GLOBAL total ratio moved from approximately `1.101` to **`0.971`** in that run.

### Reversed-order replication

The larger replication deliberately ran the enabled candidate first and disabled baseline second, reversing the original process order. With 350 matched observations per ball count:

**100 balls**

- exact TOI median: `7,556 -> 6,754` (**-10.6%**);
- total-engine factor: **0.963** (`0.935–0.991`);
- advance factor: **0.748** (`0.724–0.772`).

**20 balls**

- exact TOI median: `293 -> 287` (**-2.0%**);
- advance factor: **0.944** (`0.898–0.996`).

The process-level 20-ball total measurement appeared worse because construction appeared 12.7% worse even though temporal pruning is deliberately not executed during construction. The first process ordering showed a different construction shift. That inconsistency motivated a tighter same-JVM experiment rather than attributing an impossible construction mechanism to the optimization.

### Same-JVM interleaved A/B

The final acceptance experiment alternated enabled/disabled order on every repetition inside one JVM. It used the same seven workloads, 20/100 balls, five seeds, three warmup pairs, and 20 measured pairs per seed: **700 paired measurements per ball count**.

| Ball count | Construction factor | Advance factor | Total factor |
|---:|---:|---:|---:|
| 20 | 0.996 (`0.982–1.009`) | **0.918 (`0.897–0.940`)** | **0.967 (`0.953–0.980`)** |
| 100 | 1.006 (`0.995–1.016`) | **0.733 (`0.721–0.746`)** | **0.905 (`0.895–0.915`)** |

Construction is therefore consistent with parity, as the implementation design predicts. The causal target improves at both sizes:

- 20-ball advance: about **8.2% faster**;
- 20-ball total: about **3.3% faster**;
- 100-ball advance: about **26.7% faster**;
- 100-ball total: about **9.5% faster**.

At 100 balls, splitting the samples by execution order produced advance factors of approximately `0.737` when enabled ran first and `0.730` when enabled ran second. The result is therefore not explained by the adjacent execution order.

## Interpretation

This milestone supports a causal chain that the earlier micro-optimization attempts did not establish:

1. advance profiling located dominant cost in candidate-selection regions;
2. temporal reachability removes exact candidate work from those regions;
3. deterministic counters show fewer exact TOI queries;
4. correctness remains unchanged against the all-pairs oracle;
5. the wall-clock improvement survives process-order reversal;
6. a same-JVM interleaved A/B removes the construction/process-order artifact and confirms both advance and total improvement.

These are still GitHub-hosted Ubuntu/Temurin results, not a universal hardware claim. Cross-machine replication remains required before publishing machine-independent speedup numbers.

## Next hypotheses

Temporal pruning is a useful bridge toward broader candidate reduction, but the workload behavior argues against one indiscriminate next step.

The next investigations should include:

1. **Cost-aware temporal pruning.** The benefit is much larger when exact candidates use accelerated quartic TOI. A policy could condition cheap broad-phase work on motion model, owner horizon, or system size while preserving the same conservative proof.
2. **Swept spatial indexing.** Use the owner event horizon to build conservative swept cells/AABBs rather than a current-position grid that can miss fast or accelerated collisions.
3. **Larger-N crossover campaigns.** Measure 300/1000+ bodies to determine how candidate pruning changes the CADQ/GLOBAL crossover and whether broad-phase bookkeeping becomes the next bottleneck.
4. **Memory/allocation evidence.** Measure allocations and GC explicitly rather than interpreting queue size as heap usage.

Any spatial extension must remain conservative under high velocity and acceleration. A naive static grid is not an acceptable replacement for exact event reachability.
