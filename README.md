# Bouncing Balls Laboratory

Java 17 engine and experiment harness for frictionless 2D circular rigid bodies. The engine is independent of Swing: it predicts time of impact (TOI), schedules events, batches simultaneous contacts into islands, and resolves impulses. `legacy/` preserves the 2022 Swing prototype unchanged.

## Build and run

Requirements: Java 17 and Maven 3.9+. These verified commands work from a clean checkout:

```powershell
mvn test
mvn exec:java -Dexec.args="--algorithm GLOBAL_EVENT_QUEUE --workload NEWTON_CRADLE --balls 5 --duration 1"
mvn exec:java -Dexec.mainClass=io.github.eddytodd.bouncingballs.demo.SwingDemo
```

Use `--list` to list modes. The CLI accepts `--algorithm`, `--resolver`, `--workload`, `--balls`, `--seed`, `--restitution`, `--duration`, `--events`, `--step`, and `--out` (JSON Lines).

Examples:

```text
--algorithm ALL_PAIRS_CCD --resolver DIRECT --workload SYMMETRIC_IMPACT --duration 2
--algorithm COMPUTE_AHEAD_DEPENDENCY_QUEUE --workload ADVERSARIAL_INVALIDATION --balls 100 --out benchmarks/results/cadq.jsonl
--algorithm DISCRETE_BASELINE --step .01 --workload HIGH_VELOCITY --balls 100
--algorithm GLOBAL_EVENT_QUEUE --workload ACCELERATED --restitution .8 --balls 100
```

## Implemented modes

| Mode | Purpose | Caveat |
|---|---|---|
| `DISCRETE_BASELINE` | fixed-step overlap control | tunnels by design |
| `ALL_PAIRS_CCD` | complete rebuild reference | quadratic prediction work |
| `GLOBAL_EVENT_QUEUE` | generation-validated heap | stale entries grow under heavy invalidation |
| `COMPUTE_AHEAD_DEPENDENCY_QUEUE` | one retained earliest prediction per owner plus reverse links | currently uses full owner reselection after a change as a correctness safeguard |

Resolvers are `SEQUENTIAL`, deterministic pairwise impulses; `ITERATIVE`, symmetric projected Gauss-Seidel; and `DIRECT`, a coupled normal-impulse linear solve with iterative fallback for singular or negative-impulse systems.

The motion model is exact constant velocity or constant acceleration between trajectory changes. Constant-acceleration circle contact is a quartic in time; the implementation isolates real roots by recursively partitioning at derivative roots and bisecting, rather than relying on a fragile quartic formula. Floating point root error remains documented in [the research note](docs/COLLISION_ALGORITHM_RESEARCH.md).

Public API: create `Ball`s, construct `Simulation(balls, bounds, config)`, call `advance(seconds, maxEvents)`, then read `balls()` and `stats()`.

## Structure

- `src/main/java/.../core` — bodies, numerical policy, TOI, event simulation
- `scheduler` — CCD scheduling strategies
- `resolver` — simultaneous-island strategies
- `cli` — reproducible seeded workloads and JSONL runner
- `demo` — optional Swing state consumer
- `legacy` — preserved 2022 implementation
- `docs` — methodology and limitations

This is deliberately balls-only: no rotation, friction, polygons, or 3D. See the research note for current limitations and appropriate interpretation of benchmark results.
