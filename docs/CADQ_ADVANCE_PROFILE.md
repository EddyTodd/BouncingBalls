# CADQ advance-phase profile and falsification log

This note records the milestone that followed the accepted dense-bookkeeping CADQ implementation. Its purpose was not to assume another optimization, but to locate the remaining `advance()` cost, test narrowly targeted hypotheses, and retain negative results when the target metric did not confirm the mechanism.

## Profiling method

`CadqProfileCli` is a collision-specific diagnostic runner. It enables coarse CADQ phase timing through `-Dbouncingballs.cadqProfile=true` and keeps one JVM alive across warmups and measured repetitions.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CadqProfileCli \
  -Dexec.args="--balls 100 --seeds 3 --warmups 1 --repetitions 5 --duration 1 --out benchmarks/results/cadq-profile.jsonl --overwrite"
```

The profiler separates four non-overlapping scheduler regions inside `advance()`:

- queue polling, stale removal, and retained-owner validation;
- changed-body slot mapping and reverse-dependency discovery;
- full owner reselection;
- local changed-pair refresh traversal.

It also records mechanism counters such as queue validation checks, dependency batches, full/local owners visited, local owners modified, retained installs/removals, reverse-dependency mutations, TOI queries, queue operations, predicted-event materializations, and temporal broad-phase checks/prunes.

**The phase timings are observer-instrumented diagnostics, not benchmark timings.** `System.nanoTime()` probes perturb the measured code path. Uninstrumented `CampaignCli` runs remain the acceptance gate for performance claims.

## Initial 100-ball phase profile

The first bounded profile used the same seven randomized workload families as the scheduler campaign, 100 requested balls, three seeds, one warmup, five measured repetitions, and one simulated second: 105 measured CADQ profile trials.

Across all workloads, median values were approximately:

| Measurement | Median | Share of profiled CADQ time |
|---|---:|---:|
| `advanceNanos` | 0.625 ms | — |
| four profiled scheduler phases | 0.453 ms | 100% |
| queue work | 0.0209 ms | 4.6% |
| dependency discovery | 0.0159 ms | 3.5% |
| full reselection | 0.218 ms | 48.2% |
| local refresh | 0.172 ms | 37.9% |

The four probes covered about 72% of the median whole `advance()` time. The unprofiled remainder includes simulation/resolver work and measurement effects, so phase percentages must not be interpreted as percentages of the entire engine.

Representative median mechanism counts were:

| Counter | Median |
|---|---:|
| queue validation checks | 21 |
| dependency batches | 9 |
| full owners visited | 24 |
| local owners visited | 877 |
| local owners modified | 4 |
| retained installs | 128 |
| retained removals | 28 |
| inbound dependency sets | 48 |
| inbound dependency clears | 18 |
| TOI queries | 7,556 |

Full reselection plus local refresh accounted for roughly 86% of the profiled scheduler regions. Queue validation plus dependency discovery accounted for only about 8%. Sparse workloads shifted more weight toward local refresh and queue work, but the broad conclusion was unchanged: the next hypothesis needed to attack selection/reselection work rather than the priority queue itself.

## Three hypotheses tested and rejected

Each candidate below was first made correctness-preserving, regression-tested, then run through the same uninstrumented differential campaign used by the previous milestone: seven workload families, 20/100 balls, three seeds, one warmup, five measured repetitions, and one simulated second. Every campaign contained 42 scenarios and 630 measured trials.

All three candidates produced:

- 0 physical correctness failures;
- 0 execution failures;
- the same 30 strict numerical-drift warnings already known from the accepted dense implementation;
- identical physical collision histories for those warnings, with final-state drift below the explicit ceiling.

Performance was compared with the accepted dense baseline on exact matched `(workload, requestedBalls, seed, repetition)` observations. Each campaign was normalized internally through `CADQ / GLOBAL`; candidate-versus-baseline factors and deterministic 20,000-resample bootstrap intervals were then computed with `benchmarks/compare_campaigns.py`.

### 1. Reuse retained owner buffers

Hypothesis: repeated exact-size `CollisionEvent[]` and target-array allocation during owner reinstall was a material part of the remaining gap.

Experimental mechanism: retain per-owner capacity and reuse buffers across same-shape reselections instead of allocating two exact-size arrays for each install.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.038 | 0.974–1.107 |
| advance | 1.000 | 0.931–1.070 |

The advance point estimate was effectively unchanged and total time trended worse. The intervals span `1`; the implementation was reverted.

### 2. Bound local-owner traversal by canonical ownership

Hypothesis: the local refresh loop wasted meaningful time visiting owners that could not canonically own a pair with any changed body.

Experimental mechanism: because pairs are owned by the lower-id/dense slot endpoint, owners at or above the highest changed slot were skipped unless already selected for full recomputation. The mechanism was provably safe and regression-tested.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.042 | 0.982–1.108 |
| advance | 1.001 | 0.942–1.067 |

This removed provably useless loop visits but did not measurably improve the target metric. It was reverted rather than retained as an assumed optimization.

### 3. Defer `CollisionEvent` materialization

Hypothesis: CADQ paid for many finite `CollisionEvent` allocations that were immediately discarded because only the owner's earliest prediction/tie set survives.

Experimental mechanism: evaluate TOI as a primitive `double`, select the earliest/tied predictions, then materialize events only for the survivors. A targeted regression demonstrated the intended mechanism: in a four-body setup with 10 finite predictions, GLOBAL materialized all 10 while experimental CADQ materialized only the four retained owner predictions.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.033 | 0.969–1.102 |
| advance | 0.979 | 0.905–1.051 |

Allocation was deterministically reduced, but the speed evidence remained inconclusive and total time trended worse. The optimization was reverted. The generic `predictedEventMaterializations` mechanism counter remains because it is useful for future experiments.

## Interpretation and subsequent validation

These negative results mattered. The profiler correctly identified full reselection/local refresh as the dominant *regions*, but simple bookkeeping and allocation reductions inside those regions did not explain the remaining CADQ/GLOBAL gap. Three independently plausible micro-optimizations all preserved physics yet failed the same target-metric acceptance test.

That evidence led to a qualitatively different hypothesis: reduce the expensive candidate-selection work itself with a **conservative temporal broad phase**. For current center offset `r`, combined radius `R`, relative speed magnitude `|v|`, and relative acceleration magnitude `|a|`, contact by time `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implemented bound uses still-more-conservative L1 velocity/acceleration norms plus numerical and tie-time slack. Once an owner has an exact best event at time `T`, a pair proven unable to reach contact by that horizon skips the exact quadratic/quartic TOI calculation.

Unlike the three micro-optimization candidates above, this hypothesis passed the empirical acceptance gate. A reversed-order process replication retained zero physical correctness failures while reducing 100-ball median exact TOI work by about **10.6%** and improving normalized `advance()` by about **25.2%** (`0.724–0.772` bootstrap factor interval).

A subsequent same-JVM interleaved A/B removed the impossible process-level construction shifts from the causal comparison. Across 700 paired measurements per size:

- 20-ball construction remained at parity (`0.982–1.009`), advance improved about **8.2%** (`0.897–0.940`), and total improved about **3.3%** (`0.953–0.980`);
- 100-ball construction remained at parity (`0.995–1.016`), advance improved about **26.7%** (`0.721–0.746`), and total improved about **9.5%** (`0.895–0.915`).

The temporal hypothesis is therefore no longer merely the proposed next step; it is the accepted follow-on optimization. See [`CADQ_TEMPORAL_PRUNING.md`](CADQ_TEMPORAL_PRUNING.md) for the conservative proof, mechanism counters, process-order replication, same-JVM A/B design, and next hypotheses.

A conventional current-position grid remains unsafe as an automatic next step for future event-driven collisions, especially under high velocity or acceleration. Any spatial broad phase should use swept/temporal bounds or otherwise prove that it cannot discard a valid earlier event.

## Evidence policy

The raw hosted-runner JSONL and diagnostic artifacts from these milestones remain attached to their completed GitHub Actions runs rather than being committed as universal benchmark truth. The repository records the experiment shape, mechanism, matched comparison method, and conclusions. Cross-machine replication is still required before making machine-independent performance claims.
