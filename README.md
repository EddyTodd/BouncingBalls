# Bouncing Balls Laboratory

Java 17 engine and research harness for frictionless 2D circular rigid bodies. The engine is independent of Swing: it predicts time of impact (TOI), schedules events, batches simultaneous contacts into islands, and resolves impulses. `legacy/` preserves the 2022 Swing prototype unchanged.

## Build and run

Requirements: Java 17 and Maven 3.9+.

```bash
mvn test
mvn exec:java -Dexec.args="--algorithm GLOBAL_EVENT_QUEUE --workload NEWTON_CRADLE --balls 5 --duration 1"
mvn exec:java -Dexec.mainClass=io.github.eddytodd.bouncingballs.demo.SwingDemo
```

Use `--list` to list modes. The single-run CLI accepts `--algorithm`, `--resolver`, `--workload`, `--balls`, `--seed`, `--restitution`, `--duration`, `--events`, `--step`, and `--out`.

Examples:

```text
--algorithm ALL_PAIRS_CCD --resolver DIRECT --workload SYMMETRIC_IMPACT --duration 2
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload ADVERSARIAL_INVALIDATION --balls 100 --out benchmarks/results/cadq.jsonl
--algorithm DISCRETE_BASELINE --step .01 --workload HIGH_VELOCITY --balls 100
--algorithm GLOBAL_EVENT_QUEUE --workload ACCELERATED --restitution .8 --balls 100
```

Single-run output separates workload generation, simulation/scheduler construction, and `advance()` timing. `totalEngineNanos` includes scheduler initialization plus simulation advance; workload generation is excluded from engine comparisons.

## Differential research campaign

`CampaignCli` regenerates the same seeded initial state for every scheduler, uses `ALL_PAIRS_CCD` as a reference, performs configurable warmups and interleaved repetitions, and emits raw JSONL evidence.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

Correctness is structural as well as numerical. Runs record a deterministic physical contact-history fingerprint. A measured scheduler must reproduce the reference collision history and remain inside an explicit final-state drift ceiling. A tighter state comparison remains visible separately as `numericalDriftWarning`, so floating-point path dependence is not mislabeled as a missed collision or silently ignored.

The first bounded post-optimization hosted campaign produced **630 measured trials with 0 physical correctness failures and 0 execution failures**. It also retained 30 strict numerical-drift warnings in high-speed/wall-heavy 100-ball cases whose physical collision histories were identical.

See [`benchmarks/README.md`](benchmarks/README.md) for the campaign protocol and [`docs/COLLISION_ALGORITHM_RESEARCH.md`](docs/COLLISION_ALGORITHM_RESEARCH.md) for the algorithmic evidence and interpretation.

## Implemented modes

| Mode | Purpose | Caveat |
|---|---|---|
| `DISCRETE_BASELINE` | fixed-step overlap control | tunnels by design |
| `ALL_PAIRS_CCD` | complete rebuild correctness reference | quadratic prediction work |
| `GLOBAL_EVENT_QUEUE` | generation-validated global heap | stale entries and queue growth under invalidation |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | canonical pair ownership, earliest tie sets, reverse dependencies, local invalidation | bookkeeping overhead remains measurable |

Resolvers are `SEQUENTIAL`, deterministic pairwise impulses; `ITERATIVE`, symmetric projected Gauss-Seidel; and `DIRECT`, a coupled normal-impulse linear solve with iterative fallback for singular or negative-impulse systems.

## CADQ status

CADQ has evolved through measured falsification rather than assumed optimization:

1. an initial correctness safeguard fully reselected every owner and empirically destroyed the intended advantage;
2. dependency-local invalidation removed that safeguard and preserved simultaneous-contact tie sets;
3. the first serious campaign showed CADQ still performed almost twice as many TOI queries as the global heap;
4. canonical lower-id ownership now evaluates each unordered ball pair once per full selection.

After canonical ownership, 100-ball CADQ TOI counts are approximately equal to GLOBAL while CADQ keeps a substantially smaller global queue. In the same hosted campaign CADQ was still generally about 2–20% slower than GLOBAL in most 100-ball workloads (with a larger sparse-case gap), so the current bottleneck is no longer primarily collision prediction. The next research target is dense/indexed dependency bookkeeping before introducing a spatial broad phase.

`Simulation` requires unique body ids because canonical pair ownership uses stable ids to choose the single owner of each unordered pair.

The motion model is exact constant velocity or constant acceleration between trajectory changes. Constant-acceleration circle contact is quartic in time; the implementation isolates real roots by recursively partitioning at derivative roots and bisecting rather than relying on a fragile closed-form quartic implementation.

Public API: create `Ball`s with unique ids, construct `Simulation(balls, bounds, config)`, call `advance(seconds, maxEvents)`, then read `balls()` and `stats()`.

## Structure

- `src/main/java/.../core` — bodies, numerical policy, TOI, event simulation
- `scheduler` — CCD scheduling strategies
- `resolver` — simultaneous-island strategies
- `research` — canonical differential state oracle
- `cli` — seeded workloads, single-run experiments, and campaign runner
- `benchmarks` — empirical protocol; machine-local raw results are ignored by Git
- `demo` — optional Swing state consumer
- `legacy` — preserved 2022 implementation
- `docs` — methodology, invariants, evidence, and limitations

This is deliberately balls-only: no rotation, friction, polygons, or 3D. Those extensions should not obscure the current objective of understanding and empirically improving collision scheduling.
