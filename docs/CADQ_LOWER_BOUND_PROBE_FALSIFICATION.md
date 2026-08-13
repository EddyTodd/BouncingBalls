# CADQ lower-bound probe falsification

## Hypothesis

After the retained-target ordering experiment failed, the next candidate-ordering hypothesis used a physically motivated score instead of collision history.

For one axis, define the optimistic excess gap

`g = max(0, |d| - R - slack)`,

with relative speed magnitude `v = |dv|` and relative acceleration magnitude `a = |da|`. Any contact by time `t` must satisfy

`g <= v t + 0.5 a t^2`.

The earliest time permitted by this envelope is

- `g / v` when `a = 0` and `v > 0`;
- `2g / (v + sqrt(v^2 + 2ag))` when `a > 0`;
- positive infinity for a positive gap with zero relative speed and acceleration.

Because contact must satisfy both coordinate axes, the pair ordering score was `max(t_x, t_y)`. Numerical slack made the score optimistic; it was used only for ordering, never as an exact collision test.

The proposed CADQ strategy was to keep a fixed-size top-`k` set of candidates with the smallest lower bounds, exact-test those first, then run the existing canonical scan. If a probe found a pair earlier than the wall event, the smaller horizon could allow the accepted axis/radial temporal proof to prune more later exact TOI solves.

Tested `k`: 1, 2, 4, 8.

## Mechanism gate

No production scheduler code was modified. A construction-only harness replayed current CADQ full-owner selection on deterministic initial workload states and compared it with the top-`k` variants.

Population:

- eight stochastic workloads: sparse, dense, clustered, high velocity, wall dominated, accelerated, differential acceleration, and adversarial invalidation;
- 100 and 300 bodies;
- seeds 1, 2, 3;
- baseline `k=0` plus `k=1,2,4,8`.

That is 48 workload/size/seed scenarios and 240 result rows. Every candidate owner retained the same earliest event time as the baseline under the repository's numerical policy.

## Result

The hypothesis failed completely.

| Bodies | k | Pair TOI reduction | Quadratic reduction | Quartic reduction | Probe horizon tighten rate |
|---:|---:|---:|---:|---:|---:|
| 100 | 1 | 0.000% | 0.000% | 0.000% | 0.000% |
| 100 | 2 | 0.000% | 0.000% | 0.000% | 0.000% |
| 100 | 4 | 0.000% | 0.000% | 0.000% | 0.000% |
| 100 | 8 | 0.000% | 0.000% | 0.000% | 0.000% |
| 300 | 1 | 0.000% | 0.000% | 0.000% | 0.000% |
| 300 | 2 | 0.000% | 0.000% | 0.000% | 0.000% |
| 300 | 4 | 0.000% | 0.000% | 0.000% | 0.000% |
| 300 | 8 | 0.000% | 0.000% | 0.000% | 0.000% |

The result was also 0.000% exact-pair reduction for every individual 300-body workload at every tested `k`.

At 100 bodies, the 24 workload/seed scenarios contained 118,800 canonically owned pair candidates in aggregate. Every top-`k` variant still executed exactly 118,800 pair TOI solves while additionally evaluating 118,800 lower bounds.

At 300 bodies, every variant still executed all 1,076,400 pair TOI solves while additionally evaluating 1,076,400 lower bounds.

The probes did find real future collisions. At 300 bodies with `k=4`, 28,560 candidates were early-probed and 2,010 had finite future pair TOIs. At 100 bodies with `k=4`, 419 of 9,360 early probes were finite. **Not one probe produced an event earlier than the existing wall horizon.**

The accepted temporal proof itself also produced zero construction pair prunes on this stochastic population. Therefore ordering could only help by finding a pair earlier than the wall event, and the analytical lower-bound ranking never did so.

## Conclusion

There is no reason to run a timing campaign. In the measured construction scope the mechanism adds an O(N^2) set of lower-bound evaluations, including square roots, while eliminating zero exact TOI work.

The experimental lower-bound primitive, top-`k` harness, tests, summarizer, and temporary CI wiring are not retained in production.

This does not prove that lower-bound ordering can never help during post-collision full reselection, but it provides no evidence strong enough to justify adding that complexity. Together with the earlier retained-target falsification, candidate ordering is no longer the preferred optimization direction.

## Provenance

- GitHub Actions run: `31714984143`
- experiment commit: `3561b7893107b0c2b7b30892413e99bd689c0b9e`
- artifact: `9186845557`
- artifact digest: `sha256:ed35d91f726532cffcca3507bf2e7c118ff987736f728bf328cde553342d50f3`
- Ubuntu 24.04 / Temurin 17.0.19+10
- 32 tests passed; 0 failures/errors/skips

The raw 240-row CSV remains in the workflow artifact rather than being committed as a universal performance result.

## Next direction

Return to the measured CADQ advance-time structure. The current `trajectoriesChanged` path visits every owner not already scheduled for full recomputation and calls `refreshAgainstChanged`, even though only owners that canonically own a pair involving a changed target can need local refresh work.

The next hypothesis should directly enumerate those affected owner/changed-target pairs and avoid the all-owner local-refresh traversal while preserving the exact same pair candidates and event-selection semantics. That attacks deterministic hot-path bookkeeping rather than adding another speculative predictor.
