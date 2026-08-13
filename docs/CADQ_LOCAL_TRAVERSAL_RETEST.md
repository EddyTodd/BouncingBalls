# CADQ canonical local traversal retest

## Decision

**Accepted on the current CADQ implementation.**

This is a re-test of an optimization that was correctly rejected earlier. The original advance-profile milestone bounded local-owner traversal by canonical pair ownership but found no reproducible 100-body timing gain: total factor `1.042` (`0.982–1.108`) and advance factor `1.001` (`0.942–1.067`). The change was reverted. See `CADQ_ADVANCE_PROFILE.md`.

Later radial and axis temporal pruning removed substantial exact pair-TOI work, changing the scheduler's cost structure. This milestone therefore tested whether previously insignificant empty traversal had become measurable.

## Invariant

Bodies use dense slots sorted by stable id, and CADQ owns a ball pair only when

```text
ownerSlot < targetSlot.
```

Let `m` be the highest changed slot in a trajectory-change batch. Any owner that can locally refresh a pair involving the changed set must satisfy `ownerSlot < m`. Owners at or above `m` are provably unable to own a changed pair.

The accepted path therefore changes the local loop from all body slots to `[0, m)`. The historical path remains available for causal research with:

```text
-Dbouncingballs.cadqCanonicalLocalTraversal=false
```

No pair ordering, temporal proof, reverse dependency, exact TOI, or event-selection semantics change.

## Deterministic mechanism gate

Population: eight stochastic workloads, 100/300 bodies, seeds 1–3, 0.25 simulated seconds: 48 baseline/candidate scenario pairs.

Every pair had identical:

- final `StateSnapshot`;
- physical contact batches/count/hash;
- pair, quadratic-pair, and quartic-pair TOI counts;
- local pair refreshes and modified-owner counts;
- full reselections.

Only local-owner visits changed:

| Bodies | Historical | Bounded | Reduction |
|---:|---:|---:|---:|
| 100 | 12,869 | 7,037 | **45.3%** |
| 300 | 231,432 | 143,280 | **38.1%** |

Every 300-body workload reduced owner visits. Mechanism run: `31716435402`, commit `79bd2a18e94e9f0cd6ebb5c68a99c65d924fff58`.

## All-pairs-validated process campaign

A conventional two-process A/B used eight workloads, 100/300 bodies, three seeds, one warmup, four measured repetitions, and the `ALL_PAIRS_CCD` physical-history oracle. Each side produced 576 measured scheduler trials with **0 physical correctness failures, 0 execution failures, and 0 drift warnings**. Exact CADQ pair work was identical.

The timing result was intentionally treated as inconclusive because unchanged construction moved by the same few-percent scale as the target effect:

| Bodies | Total factor | Construction factor | Advance factor |
|---:|---:|---:|---:|
| 100 | 1.009 (`0.959–1.063`) | 1.030 (`0.975–1.088`) | 0.939 (`0.855–1.033`) |
| 300 | 1.023 (`0.991–1.058`) | 1.024 (`0.992–1.060`) | 1.021 (`0.985–1.061`) |

Provenance: run `31716561761`, artifact `9187567653`, digest `sha256:221df75953fb465bd55c70a69bb337867135457e7f63d4180d0288ed2f674652`.

## Same-JVM interleaved acceptance test

A more sensitive harness kept one JVM alive, alternated baseline/candidate order, used three warmups and 20 measured paired repetitions for each of 24 workload/seed scenarios per body count, and checked state/contact/mechanism equality for every pair. There were 480 paired observations per size.

Candidate/baseline ratios were analyzed in log space. Bootstrap intervals resampled complete `(workload, seed)` scenarios as blocks, preserving the 20 correlated repetitions within each deterministic initial state; 20,000 resamples used seed 42.

### First run

| Bodies | Construction | Advance | Total |
|---:|---:|---:|---:|
| 100 | 0.9989 (`0.9883–1.0091`) | **0.9711** (`0.9437–0.9986`) | 0.9925 (`0.9820–1.0032`) |
| 300 | 0.9994 (`0.9918–1.0079`) | **0.9620** (`0.9513–0.9737`) | **0.9873** (`0.9799–0.9950`) |

Artifact `9187689098`, digest `sha256:d3dfe118db00f66411b8226cfb743903963def5c70d1fe83725ea8a9d7722ce6`.

### Fresh replication

| Bodies | Construction | Advance | Total |
|---:|---:|---:|---:|
| 100 | 0.9978 (`0.9830–1.0126`) | **0.9574** (`0.9323–0.9809`) | 0.9928 (`0.9793–1.0069`) |
| 300 | 1.0016 (`0.9914–1.0120`) | **0.9602** (`0.9525–0.9676`) | **0.9878** (`0.9787–0.9971`) |

Run `31717038782`, attempt 2; artifact `9187817094`, digest `sha256:01beee51317354ccce48f5452e742f8d0cfd5ec7098138cc0cd54ad453e2e53f`.

The 300-body conclusion survives excluding the first two measured repetitions in both runs: construction remains at parity, advance remains about 3.8–4.1% lower, and total remains about 1.2–1.3% lower.

## Interpretation

The accepted claim is deliberately narrow:

- the structural bound removes about 38–45% of local-owner visits;
- two same-JVM runs reproduce about a **3.8–4.0% 300-body `advance()` reduction**;
- both reproduce a small **1.2–1.3% 300-body total-engine reduction**;
- 100-body total engine remains compatible with parity;
- unchanged construction is at parity in the same-JVM runs, providing a useful negative control.

The earlier rejection was not a mistake. Optimization value is conditional on the surrounding cost structure: after temporal pruning removed a major exact-TOI cost center, previously negligible bookkeeping crossed the measurement threshold.

## Next question

The bound only removes owners at or above the highest changed slot. Some lower owners may still have no changed target above them. A future sparse traversal should be tested only if it can eliminate materially more deterministic visits without adding a more expensive indexing/cross-product mechanism.
