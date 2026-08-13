# SAP/CADQ duration crossover

## Decision

The SAP/CADQ crossover is real, reproducible, and strongly workload dependent. There is not enough evidence for a hard-coded adaptive scheduler rule yet.

`SWEEP_AND_PRUNE_CCD` pays very little initial cost but rebuilds after every trajectory-changing batch. CADQ pays substantially more during construction and then performs incremental invalidation. Simulation horizon therefore changes which architecture wins, but horizon alone does not determine the winner.

## Protocol

The duration campaign compared only SAP and CADQ in the timed pair. Each pair used the same deterministic initial state and alternated whether SAP or CADQ executed first.

Population:

- eight stochastic workloads;
- 100 and 300 bodies;
- durations `0.02, 0.05, 0.10, 0.20, 0.30, 0.50` simulated seconds;
- seeds 1-3;
- two warmups;
- eight measured pairs per scenario;
- iterative resolver;
- 200,000 physical-event cap.

The complete matrix contains 2,304 potential measured pairs.

Timing rows were accepted only when SAP and CADQ had identical physical contact history and final state inside the existing drift ceiling. The stricter state comparison remained visible separately.

## Drift-boundary finding

The first 0.50-second attempt exposed a validation boundary rather than a missed collision: 300-body `HIGH_VELOCITY` seed 2 preserved the physical contact history but exceeded the existing final-state drift ceiling. The ceiling was not widened.

The hardened campaign records such scenarios as excluded and contributes no timing rows from them. In both later complete runs, 300-body `HIGH_VELOCITY` seeds 2 and 3 at 0.50 seconds were excluded at the warmup gate for state drift while preserving physical history.

Thus each hardened run accepted **2,288 measured pairs**, recorded **8 strict-state warnings**, excluded **2 complete scenarios**, and had **0 measured-pair exclusions** and **0 physical-history failures**.

The 0.50-second 300-body aggregate is therefore partial and is not used as a complete-population claim.

## Replication

Two independent hosted runs used different runner images/JDK patch versions and reproduced the same crossover shape.

- run `31726532120`, Temurin 17.0.20, raw evidence digest `sha256:fc1d6ee998ec1ece9a64c7eab4123f9648afb7f5c19bc2e7917f85a1d80fb8d8`;
- run `31726693445`, Temurin 17.0.19, raw evidence digest `sha256:6335faeb87c8efab51ad2734aa0b1d2df2406d828ebc9d22aa64dfbf4b70afbb`.

Ratios below are SAP/CADQ. Bootstrap intervals use 20,000 deterministic resamples of complete `(workload, seed)` blocks, preserving the eight correlated repetitions inside one deterministic scenario.

### Fresh replication

| Bodies | Duration | Construction | Advance | Total |
|---:|---:|---:|---:|---:|
| 100 | 0.02 | 0.164 | 0.894 | **0.196** (`0.170-0.227`) |
| 100 | 0.05 | 0.157 | 1.578 | **0.269** (`0.221-0.328`) |
| 100 | 0.10 | 0.158 | 3.077 | **0.399** (`0.346-0.463`) |
| 100 | 0.20 | 0.154 | 3.244 | **0.529** (`0.441-0.629`) |
| 100 | 0.30 | 0.159 | 3.277 | **0.625** (`0.510-0.758`) |
| 100 | 0.50 | 0.159 | 3.457 | 0.876 (`0.737-1.035`) |
| 300 | 0.02 | 0.086 | 3.205 | **0.228** (`0.160-0.325`) |
| 300 | 0.05 | 0.088 | 4.717 | **0.423** (`0.303-0.594`) |
| 300 | 0.10 | 0.089 | 4.978 | 0.743 (`0.542-1.025`) |
| 300 | 0.20 | 0.090 | 4.631 | 1.139 (`0.809-1.620`) |
| 300 | 0.30 | 0.090 | 4.575 | **1.421** (`1.015-2.007`) |
| 300 | 0.50 | 0.096 | 4.858 | 1.887 (`1.350-2.651`), partial |

The earlier replication produced the same progression: 300-body total factors `0.214, 0.404, 0.709, 1.056, 1.330, 1.766(partial)` across the same durations.

At 300 bodies the aggregate point estimate crosses parity between 0.10 and 0.20 seconds. At 0.05 SAP is clearly favored; at 0.30 both replications favor CADQ, although only one of the two 0.30 block-bootstrap intervals excludes parity. At 100 bodies the aggregate remains SAP-favored through the tested range, with 0.50 seconds close enough to parity that the two run intervals differ on whether parity is excluded.

Pair execution order is not driving the result. SAP-first and CADQ-first aggregate factors were nearly identical in every duration cell.

## Workload-specific crossover

The aggregate hides large architecture-specific differences. Fresh-replication crossover brackets are:

| Bodies | Workload | First observed SAP-to-CADQ bracket |
|---:|---|---|
| 100 | accelerated | about `0.20-0.30`, boundary-sensitive |
| 100 | high velocity | `0.20-0.30` |
| 100 | wall dominated | `0.30-0.50` |
| 100 | adversarial invalidation | no crossover through 0.50 |
| 100 | clustered | no crossover through 0.50 |
| 100 | dense uniform | no crossover through 0.50 |
| 100 | differential acceleration | no crossover through 0.50 |
| 100 | sparse uniform | no crossover through 0.50 |
| 300 | adversarial invalidation | `0.02-0.05` |
| 300 | clustered | `0.02-0.05` |
| 300 | accelerated | `0.05-0.10` |
| 300 | high velocity | `0.10-0.20` |
| 300 | wall dominated | `0.20-0.30` |
| 300 | differential acceleration | `0.30-0.50` |
| 300 | dense uniform | near `0.30-0.50` |
| 300 | sparse uniform | no crossover through 0.50 |

This directly falsifies body count plus duration as a sufficient selector.

## Event-batch hypothesis: insufficient

A natural next hypothesis was that the crossover might collapse onto resolved event-batch count. It does not.

At 300 bodies:

- clustered/adversarial systems already favor CADQ around **5** median physical batches;
- accelerated approaches parity around **4** batches;
- high velocity crosses around **38-69** batches;
- wall dominated crosses around **46-62** batches;
- sparse uniform still strongly favors SAP at about **23** batches.

Event count is mechanistically important but is not a standalone scheduler-selection variable.

## Initial SAP probe: useful but insufficient

Because SAP construction is much cheaper than CADQ construction, a promising adaptive design is to build SAP first and use its initial broad-phase statistics as cheap features.

A deterministic three-seed construction probe measured initial exact-candidate and X-overlap fractions.

At 300 bodies:

| Workload class | Median exact SAP candidate fraction | Median X-overlap fraction |
|---|---:|---:|
| clustered / adversarial | **4.32%** | **16.68%** |
| dense uniform | 0.029% | 3.21% |
| sparse / high velocity / wall / accelerated / differential acceleration | about 0.0045% | about 1.25% |

The probe clearly identifies the clustered/adversarial family, which is exactly the family with the earliest observed 300-body crossover. However it cannot distinguish sparse, high-velocity, wall-dominated, accelerated, and differential-acceleration cases even though their duration crossovers differ substantially.

Initial SAP candidate fraction is therefore useful but **not sufficient by itself**.

## Interpretation

The current evidence supports a cost model, not a fixed threshold:

`SAP total ~= cheap construction + event batches * rebuild cost`

`CADQ total ~= expensive construction + event batches * incremental refresh cost`

Both the event rate and the per-event SAP rebuild cost depend on motion and geometry. That is why neither elapsed duration, event count, nor initial candidate fraction alone produces a universal boundary.

## Next falsifiable question

Do not implement an adaptive scheduler from workload names or hard-coded duration thresholds.

The next experiment should build a generic predictor from features available without executing both schedulers, for example:

- body count;
- requested simulation horizon;
- initial SAP exact-candidate and X-overlap fractions;
- fill/density measures;
- mean/RMS/max speed;
- acceleration magnitude and acceleration variance;
- wall proximity or earliest wall TOI.

Evaluate the predictor out of sample, preferably by holding out complete seeds and workload families. Report classification accuracy **and performance regret** relative to an offline oracle that always selects the faster measured scheduler.

There is also an API constraint: the current `Simulation` constructor builds the scheduler before `advance(duration, ...)` supplies the requested horizon. Even a successful duration-aware predictor therefore requires either lazy scheduler initialization, an explicit expected-horizon hint, or a carefully measured online switching design. That API change should follow evidence rather than precede it.
