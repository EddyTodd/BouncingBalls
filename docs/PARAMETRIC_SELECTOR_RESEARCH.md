# Parametric scheduler-selector research

## Decision

**Do not implement a pre-run SAP/CADQ adaptive scheduler from the tested model.**

The SAP/CADQ duration campaign established that neither body count, requested horizon, resolved event count, nor the initial SAP candidate fraction is sufficient by itself to select the faster scheduler. This milestone asked a stronger question: can a generic multivariate predictor, evaluated out of sample and without workload names, choose between SAP and CADQ with low performance regret?

The answer for the tested predictor is **no**. Aggregate results improve only slightly over always choosing SAP, and one deliberately held design stratum fails badly. The model therefore does not meet the predeclared generalization gate.

The continuous parametric workload generator developed for this experiment is retained as useful research infrastructure. The temporary model-fitting and audit harnesses are not retained.

## Why a continuous workload space was necessary

The established named workloads are valuable adversarial and regression cases, but training an adaptive selector directly on labels such as `CLUSTERED`, `HIGH_VELOCITY`, or `ADVERSARIAL_INVALIDATION` would make it too easy to memorize the benchmark taxonomy.

The selector experiment therefore uses a deterministic six-dimensional Halton design over generic physical parameters:

- geometric fill fraction;
- clustering strength;
- speed scale;
- wall-directed velocity bias;
- shared acceleration magnitude;
- differential acceleration magnitude.

Body count and requested simulation horizon vary separately.

No workload name or hidden generator parameter is supplied to the predictor. Predictor features are derived from the actual generated state or from the already-paid initial SAP construction probe.

## First manifold design: rejected before interpretation

The first continuous generator used a box whose size changed with target fill and covered speeds only through approximately 25 units/second.

A 20-design-point selector run was dominated by SAP: 346 of 360 valid scenarios favored SAP after accounting for the initial SAP probe cost. More importantly, review against the established named workloads exposed two generator defects:

1. the speed range did not reach the repository's established high-velocity/wall regimes, which use speeds around 300/180;
2. changing box size to vary fill unintentionally coupled density to wall horizon.

Although that preliminary model run produced valid timing data, it was rejected as a selector population because the manifold itself underrepresented important CADQ-favorable regimes. The generator was redesigned rather than interpreting the imbalance as evidence that SAP was generally superior.

## Revised parametric manifold

The retained `ParametricWorkloads` design fixes the domain at `1000 x 1000`, so wall scale is independent of density. Fill changes body radius instead of box size.

The six Halton dimensions cover:

| Parameter | Range |
|---|---:|
| fill fraction | `0.001` to `0.08` (log scale) |
| cluster strength | `0` to `0.95` |
| speed scale | `0.5` to `300` (log scale) |
| wall bias | `0` to `1` |
| shared acceleration | `0` to `12` |
| differential acceleration | `0` to `12` |

Clustering is distributed around six fixed centers, with cluster probability and spread controlled continuously. Velocity direction blends a random unit direction with the direction of the nearest wall. Shared downward acceleration and body-specific differential acceleration vary independently.

The generator uses deterministic rejection sampling and permanent tests verify deterministic reproduction, finite in-bounds state, unique ids, and nonpenetration across the first 24 design points, 100/300 bodies, and three seeds.

## Selector dataset and target

The revised selector campaign used:

- 20 Halton design points;
- 100 and 300 bodies;
- durations `0.03`, `0.10`, and `0.30` simulated seconds;
- three seeds;
- one warmup SAP/CADQ pair;
- five measured interleaved repetitions per scenario;
- the iterative resolver;
- the existing physical-history and final-state drift gates.

All **360 scenarios** passed the physical and drift gates. In the probe-aware population, SAP won 317 scenarios and CADQ won 43.

The selector target is deliberately **probe-aware**. Because the proposed adaptive design constructs SAP first to obtain its inexpensive broad-phase features, choosing CADQ is charged both CADQ's measured total cost and the already-spent SAP construction cost. Thus the target ratio is:

`SAP total / (SAP construction probe + CADQ total)`.

This is the appropriate regret target for the tested "construct SAP, then possibly switch" design. It is not a statement about the cost of choosing fixed CADQ without a probe.

## Generic predictor features

The full predictor received only generic state/probe features:

- log body count;
- log requested duration;
- realized fill fraction;
- mean and maximum speed;
- mean, standard deviation, and maximum acceleration magnitude;
- mean and minimum wall clearance;
- earliest exact wall TOI;
- initial SAP exact-candidate count;
- initial SAP X-overlap count;
- SAP predicted-event materializations per body.

A simple comparison model used only body count and requested duration.

Both were standardized ridge regressions predicting log SAP/CADQ probe-aware cost ratio. The experiment used five held-design-point folds: a complete Halton design point, including all body counts, seeds, and durations, was absent from training.

Regret is measured relative to an offline oracle that chooses the faster probe-aware architecture for each measured scenario. A selector that is merely accurate but makes its mistakes on expensive scenarios is therefore penalized.

## Result: aggregate improvement is too small and not robust

Hosted CI run `31729451579`, job `94545893888`, reproduced the revised manifold and emitted the audited decision rows.

| Selector | Accuracy | Geometric regret | p95 regret | Maximum regret |
|---|---:|---:|---:|---:|
| always SAP | 88.06% | **1.0667x** | 1.6856x | 4.6888x |
| probe then always CADQ | 11.94% | 4.6580x | 17.4866x | 30.5718x |
| body count + duration ridge | 88.06% | **1.0667x** | 1.6856x | 4.6888x |
| generic physics ridge | 88.89% | **1.0589x** | 1.6797x | 4.5967x |

The generic model is directionally better in aggregate, but the improvement is small and does not satisfy the generalization requirement.

### Held-design-point folds

| Fold | Accuracy | Geometric regret | p95 regret | Maximum regret |
|---:|---:|---:|---:|---:|
| 0 | 100.0% | 1.0000x | 1.0000x | 1.0000x |
| 1 | 100.0% | 1.0000x | 1.0000x | 1.0000x |
| 2 | 95.83% | 1.0084x | 1.0000x | 1.4299x |
| 3 | 88.89% | 1.0260x | 1.2439x | 1.6972x |
| 4 | **59.72%** | **1.2866x** | **3.0788x** | **4.5967x** |

The failure is not random cross-validation noise. Speed is the Halton base-5 dimension, while the fold partition is `designIndex mod 5`. Fold 4 therefore withholds the high-speed stratum. For example, held design point 19 produces mean speeds around 172-190 and includes 300-body scenarios where the probe-aware SAP/CADQ ratio reaches approximately 4.6x at 0.30 seconds.

The model interpolates ordinary parts of the manifold well but extrapolates poorly into unseen high-speed physics. A pre-run adaptive scheduler intended to generalize cannot be accepted on that evidence.

## What is falsified

This milestone falsifies the claim that the tested **linear pre-run predictor over the current generic features is sufficiently robust to ship as an adaptive scheduler**.

It does **not** establish that adaptive scheduling is impossible. In particular:

- the revised population remains SAP-heavy (317/43), although materially less imbalanced than the rejected first manifold (346/14);
- a nonlinear predictor might represent the boundary better;
- an online selector could observe actual event/rebuild cost rather than extrapolating entirely from initial state;
- additional independent scheduler architectures may change the selection problem.

Those possibilities are future hypotheses, not justification for merging speculative adaptive logic now.

## Research consequence

The repository should retain the continuous workload generator because it provides a reusable, label-free parameter space for future scheduler and resolver studies.

The adaptive-scheduler line should pause here. The next major work should return to the broader collection objective: add and rigorously compare another materially different collision architecture (for example a standalone BVH/AABB-tree or spatial-hash broad phase) or advance the independent simultaneous-contact resolver track.

If adaptation is revisited later, a stronger protocol should require either:

1. nonlinear/online selection with explicit high-speed and other region holdouts, or
2. a larger multi-architecture decision problem where adaptation has enough potential benefit to justify its complexity.

No API change for lazy scheduler initialization or expected-horizon hints is justified by the current selector result.
