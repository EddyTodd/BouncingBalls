# Bouncing Balls Laboratory

Java 17 engine and research harness for frictionless 2D circular rigid bodies. The engine is independent of Swing: it predicts time of impact (TOI), schedules events, batches simultaneous contacts into islands, and resolves impulses. `legacy/` preserves the 2022 Swing prototype unchanged.

## Build and run

Requirements: Java 17 and Maven 3.9+.

```bash
mvn test
mvn exec:java -Dexec.args="--algorithm GLOBAL_EVENT_QUEUE --workload NEWTON_CRADLE --balls 5 --duration 1"
mvn exec:java -Dexec.mainClass=io.github.eddytodd.bouncingballs.demo.SwingDemo
```

Use `--list` to list modes. The single-run CLI accepts `--algorithm`, `--resolver`, `--workload`, `--balls`, `--seed`, `--restitution`, `--duration`, `--events`, `--step`, and `--out` (JSON Lines).

Examples:

```text
--algorithm ALL_PAIRS_CCD --resolver DIRECT --workload SYMMETRIC_IMPACT --duration 2
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload ADVERSARIAL_INVALIDATION --balls 100 --out benchmarks/results/cadq.jsonl
--algorithm DISCRETE_BASELINE --step .01 --workload HIGH_VELOCITY --balls 100
--algorithm GLOBAL_EVENT_QUEUE --workload ACCELERATED --restitution .8 --balls 100
```

The single-run output now separates workload generation, simulation/scheduler construction, and `advance()` timing. `totalEngineNanos` includes scheduler initialization plus simulation advance; workload generation is deliberately excluded from engine timing.

## Differential research campaign

Performance claims are gated by differential correctness against `ALL_PAIRS_CCD`. `CampaignCli` regenerates the exact seeded initial state for every scheduler, runs a reference simulation, performs configurable JVM warmups and interleaved repetitions, and compares final simulation time, positions, and velocities with a scale-aware state oracle.

```bash
mvn exec:java \
  -Dbouncingballs.commit="$(git rev-parse HEAD)" \
  -Dexec.mainClass=io.github.eddytodd.bouncingballs.cli.CampaignCli \
  -Dexec.args="--workloads ALL --balls 10,100 --seeds 5 --warmups 2 --repetitions 10 --duration 1 --out benchmarks/results/cadq-validation.jsonl"
```

A campaign fails if any measured scheduler throws or diverges from the all-pairs final state. Raw JSONL includes environment provenance, construction/advance/total timings, TOI work, queue operations, stale-event behavior, CADQ reselections/local refreshes, and maximum queue size. See [`benchmarks/README.md`](benchmarks/README.md) for the evidence protocol and interpretation rules.

Randomized workloads are now rejection-sampled so bodies start finite, inside the domain, and without penetration. Constructed contact topologies such as `NEWTON_CRADLE` may intentionally start touching; large cradles automatically receive a sufficiently large domain. This sanitation means historical pre-campaign measurements must not be mixed with new results as though they came from an identical workload generator.

## Implemented modes

| Mode | Purpose | Caveat |
|---|---|---|
| `DISCRETE_BASELINE` | fixed-step overlap control | tunnels by design |
| `ALL_PAIRS_CCD` | complete rebuild correctness reference | quadratic prediction work |
| `GLOBAL_EVENT_QUEUE` | generation-validated heap | stale entries grow under heavy invalidation |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | retained earliest-time tie sets plus reverse dependencies and local invalidation | worst-case dependency fan-out can still approach full reselection |

Resolvers are `SEQUENTIAL`, deterministic pairwise impulses; `ITERATIVE`, symmetric projected Gauss-Seidel; and `DIRECT`, a coupled normal-impulse linear solve with iterative fallback for singular or negative-impulse systems.

The CADQ milestone removes the original correctness-first safeguard that fully reselected every owner after every event. Changed owners and owners whose retained predictions depend on them are fully recomputed; unaffected owners only test the changed bodies for a newly earlier event. An owner retains every event tied for its earliest time, rather than a single edge, so multi-contact simultaneous collision graphs are not silently truncated. Output exposes `cadqFullReselections` and `cadqLocalPairRefreshes` so the optimization can be measured instead of inferred. See [the research note](docs/COLLISION_ALGORITHM_RESEARCH.md) for the invariant and falsification criteria.

The motion model is exact constant velocity or constant acceleration between trajectory changes. Constant-acceleration circle contact is a quartic in time; the implementation isolates real roots by recursively partitioning at derivative roots and bisecting, rather than relying on a fragile quartic formula. Floating-point root error remains documented in [the research note](docs/COLLISION_ALGORITHM_RESEARCH.md).

Public API: create `Ball`s, construct `Simulation(balls, bounds, config)`, call `advance(seconds, maxEvents)`, then read `balls()` and `stats()`.

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

This is deliberately balls-only: no rotation, friction, polygons, or 3D. See the research note for current limitations and appropriate interpretation of benchmark results.
