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

It also records mechanism counters such as queue validation checks, dependency batches, full/local owners visited, local owners modified, retained installs/removals, reverse-dependency mutations, TOI queries, queue operations, predicted-event materializations, and temporal broad-phase checks/prunes. Later revisions also split exact pair TOI queries into quadratic versus quartic relative-motion cases.

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

The four probes covered about 72% of median whole `advance()` time. The unprofiled remainder includes simulation/resolver work and measurement effects, so phase percentages must not be interpreted as percentages of the entire engine.

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

Full reselection plus local refresh accounted for roughly 86% of the profiled scheduler regions. Queue validation plus dependency discovery accounted for only about 8%. The next hypothesis therefore needed to attack selection/reselection work rather than the priority queue itself.

## Three hypotheses tested and rejected

Each candidate below was first made correctness-preserving, regression-tested, then run through the same uninstrumented differential campaign used by the previous milestone: seven workload families, 20/100 balls, three seeds, one warmup, five measured repetitions, and one simulated second. Every campaign contained 42 scenarios and 630 measured trials.

All three candidates produced:

- 0 physical correctness failures;
- 0 execution failures;
- the same 30 strict numerical-drift warnings already known from the accepted dense implementation;
- identical physical collision histories for those warnings, with final-state drift below the explicit ceiling.

Performance was compared with the accepted dense baseline on exact matched `(workload, requestedBalls, seed, repetition)` observations. Each campaign was normalized internally through `CADQ / GLOBAL`; candidate-versus-baseline factors and deterministic 20,000-resample bootstrap intervals were computed with `benchmarks/compare_campaigns.py`.

### 1. Reuse retained owner buffers

Hypothesis: repeated exact-size retained arrays materially explained the remaining gap.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.038 | 0.974–1.107 |
| advance | 1.000 | 0.931–1.070 |

The implementation was reverted.

### 2. Bound local-owner traversal by canonical ownership

Hypothesis: local refresh spent meaningful time visiting owners that could not canonically own a pair with any changed body.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.042 | 0.982–1.108 |
| advance | 1.001 | 0.942–1.067 |

The implementation was reverted.

### 3. Defer `CollisionEvent` materialization

Hypothesis: finite pair-event allocation before owner selection materially explained the remaining gap.

100-ball result:

| Metric | Candidate/baseline factor | Approx. 95% bootstrap interval |
|---|---:|---:|
| total engine | 1.033 | 0.969–1.102 |
| advance | 0.979 | 0.905–1.051 |

Allocation was deterministically reduced, but timing evidence remained inconclusive. The implementation was reverted; `predictedEventMaterializations` remains as a mechanism counter.

## Interpretation and subsequent validation

These negative results mattered. The profiler correctly identified full reselection/local refresh as the dominant *regions*, but simple bookkeeping and allocation reductions inside those regions did not explain the remaining gap. That evidence led to a qualitatively different hypothesis: reduce expensive exact candidate work with a conservative temporal broad phase.

For current center offset `r`, combined radius `R`, relative velocity `v`, relative acceleration `a`, and horizon `t`, contact by `t` requires

`|r| <= R + |v| t + 0.5 |a| t^2`.

The implemented bound uses conservative L1 norms plus numerical/tie-time slack. Once an owner has an exact best event, a pair proven unable to reach contact by that horizon skips exact TOI.

Unlike the three micro-optimization candidates, temporal pruning passed acceptance. A reversed-order process replication retained zero physical correctness failures while reducing 100-ball median exact TOI work by about **10.6%** and improving normalized `advance()` by about **25.2%** (`0.724–0.772`).

A same-JVM interleaved A/B then produced 700 paired measurements per size:

- 20-ball construction remained at parity (`0.982–1.009`), advance improved about **8.2%** (`0.897–0.940`), total about **3.3%** (`0.953–0.980`);
- 100-ball construction remained at parity (`0.995–1.016`), advance improved about **26.7%** (`0.721–0.746`), total about **9.5%** (`0.895–0.915`).

After this causal advance result was established, the same conservative horizon was enabled during initial construction. In the subsequent scale experiment, normalized construction improved about **26.3% at 100**, **43.5% at 300**, and **56.2% at 1000** bodies, while the unchanged advance phase remained compatible with parity. At 1000 bodies median exact TOI queries fell **538,147 -> 152,575 (-71.6%)**. See [`CADQ_TEMPORAL_PRUNING.md`](CADQ_TEMPORAL_PRUNING.md) for the full scale design and provenance.

### Correction: world acceleration is not pair polynomial degree

The original profile discussion loosely associated the `ACCELERATED` workload with quartic pair solving. That was imprecise. `ACCELERATED` applies the same gravity vector to every body, so pair-relative acceleration cancels and ball-ball TOI is quadratic.

The repository now contains `DIFFERENTIAL_ACCELERATION`, which assigns a distinct bounded horizontal acceleration to each body and therefore guarantees genuine quartic pair equations. New counters record quadratic versus quartic exact pair queries.

A 108-trial validation across `ACCELERATED` and `DIFFERENTIAL_ACCELERATION` produced zero correctness failures, execution failures, or drift warnings. At 100 balls, CADQ median pair queries were 1,839 all-quadratic under shared gravity and 2,351 all-quartic under differential acceleration, with 3,610 and 3,171 temporal prunes respectively. This establishes true quartic mechanism coverage without retroactively overstating what the original profile demonstrated.

A conventional current-position grid remains unsafe as an automatic next step for future event-driven collisions. Any spatial broad phase should use swept/temporal bounds or otherwise prove that it cannot discard a valid earlier event.

## Evidence policy

Raw hosted-runner JSONL and diagnostic artifacts remain attached to completed GitHub Actions runs rather than being committed as universal benchmark truth. The repository records experiment shape, mechanism, matched comparison method, corrections, and conclusions. Cross-machine replication is still required before machine-independent performance claims.
