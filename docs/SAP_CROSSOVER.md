# Sweep-and-prune scheduler crossover

`SWEEP_AND_PRUNE_CCD` is a standalone rebuild-on-change CCD architecture. It uses the earliest exact wall event as a conservative horizon, builds radius-expanded swept intervals under constant acceleration, sweeps X, filters on Y, and exact-tests only surviving pairs. Ambiguous cases fail open.

## Evidence against rebuild-all-pairs

A same-JVM campaign over eight workloads, 100/300 bodies and three seeds produced 288 measured pairs with strict state equivalence and matching physical histories.

| Bodies | Construction | Advance | Total | Exact candidates |
|---:|---:|---:|---:|---:|
| 100 | 0.1000 | 0.0926 | **0.0922** | 0.85% |
| 300 | 0.0429 | 0.0325 | **0.0330** | 1.06% |

This establishes a large causal improvement over `ALL_PAIRS_CCD`, not over incremental schedulers.

Evidence is preserved in Actions run `31723084150`, artifact `9190163771`.

## Evidence against GLOBAL and CADQ

A second experiment used all-pairs only as the correctness oracle, then interleaved SAP separately against GLOBAL and CADQ. The population was eight workloads, 100/300 bodies, three seeds, two warmups and ten measured pairs. Two complete runs produced nearly identical conclusions.

Fresh replication factors are SAP/comparator:

| Comparator | Bodies | Construction | Advance | Total |
|---|---:|---:|---:|---:|
| CADQ | 100 | 0.1585 | 3.0030 | **0.5830** |
| CADQ | 300 | 0.0844 | 4.3356 | 1.2501 |
| GLOBAL | 100 | 0.0814 | 1.9910 | **0.3087** |
| GLOBAL | 300 | 0.0347 | 2.4357 | **0.5577** |

The 300-body SAP/CADQ result is workload dependent. Replicated total factors ranged from about 0.38 for sparse uniform and 0.59 for dense uniform to about 3.95 for clustered and 4.07 for adversarial invalidation. Wall-dominated motion was near parity.

The first complete run is `31723877679`. The fresh artifact-preserved replication is run `31724018682`, artifact `9190503606`.

## Interpretation

SAP and CADQ make opposite cost trades. SAP has extremely cheap initialization but rebuilds after every trajectory-changing batch. CADQ pays much more initialization cost and then performs cheaper incremental updates.

At 0.25 simulated seconds, SAP beats GLOBAL at both tested sizes and beats CADQ at 100 bodies. At 300 bodies there is no single SAP/CADQ winner across workloads.

Body count alone is therefore insufficient for scheduler selection. Density, event rate, motion model and simulation horizon materially affect the winner.

Within each measured pair, alternating whether SAP executed first produced essentially unchanged aggregate factors. The two completed campaign runs did not reverse comparator-family order, so these remain hosted-runner population results rather than machine-independent rankings.

## Duration crossover map

The follow-up duration campaign directly swept `0.02-0.50` simulated seconds at 100/300 bodies. It reproduced the expected cost crossover while falsifying several tempting one-variable selectors:

- at 300 bodies the aggregate SAP/CADQ point estimate crosses between 0.10 and 0.20 seconds;
- individual workloads cross anywhere from before 0.05 seconds to beyond 0.50 seconds;
- event-batch count alone does not collapse those boundaries;
- initial SAP candidate fraction identifies clustered/adversarial geometry but cannot distinguish several other workload families with very different crossover horizons.

Two hardened runs accepted 2,288 measured timing pairs each, with zero physical-history failures. Two 300-body high-velocity 0.50-second scenarios were excluded for exceeding the unchanged final-state drift ceiling while preserving physical history.

See [`SAP_CADQ_DURATION_CROSSOVER.md`](SAP_CADQ_DURATION_CROSSOVER.md) for the complete protocol, block-bootstrap intervals, workload crossover brackets, drift exclusions, and construction-feature probe.

## Next experiment

Do not implement a hard-coded adaptive scheduler yet. The next research step is an out-of-sample multivariate selector using only generic features available without executing both schedulers, with performance regret measured against an offline best-scheduler oracle.

The current API also constructs the scheduler before `advance(duration, ...)` supplies its horizon. A successful duration-aware selector therefore needs a separately justified lazy-initialization or expected-horizon design.
