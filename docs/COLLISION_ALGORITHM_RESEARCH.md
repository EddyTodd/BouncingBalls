# Collision algorithm research note

## Model and numerical policy

Bodies are circles with independent radius, mass, restitution, position, velocity, and constant acceleration. For relative state `r,v,a`, contact solves `|r + vt + ½at²|² - (R1+R2)² = 0`. This is quartic; velocity-only motion reduces to quadratic. `PolynomialRoots` isolates real roots through derivative partitions and bisection with centralized scale-aware tolerances. This makes the trajectory model exact, but not the IEEE-754 root approximation.

## Scheduling and CADQ

All-pairs CCD is the small-system reference. The global heap stores absolute-time events and captures each participating body's generation; invalid events are discarded lazily. CADQ stores only each owner's earliest prediction in the global selection heap. It records reverse dependencies: if C's retained event is C-A, C is inbound to A. After A-B changes A, C is invalidated and recomputed. To prevent a missed newly-earlier C-A event where C had depended on another body, this initial research implementation reselects every owner after a change; its statistics make that fan-out visible. Thus CADQ is correct but is not yet an optimization claim.

## Simultaneous contacts

Events within `NumericalPolicy.sameTime` are advanced together, deduplicated, partitioned into ball-sharing islands, then solved deterministically. Sequential is the ordering-sensitive baseline. Iterative uses forward/reverse projected Gauss-Seidel. Direct constructs the coupled normal-impulse matrix and falls back when singular or nonphysical. A zero-time batch guard aborts rather than silently looping.

## Validation and initial result

`mvn test` validates velocity TOI, accelerated TOI, elastic head-on conservation across all resolvers, agreement of the three CCD schedulers on a small case, and stale-event observation. An initial five-ball elastic cradle run with `GLOBAL_EVENT_QUEUE`/`ITERATIVE` simulated 1 s and resolved four transfers (local Windows 11, Microsoft OpenJDK 17). Initial CLI observations: all-pairs sparse 10 balls/1 s performed 85 TOI queries with no contacts; global heap sparse 100 performed 5,865 queries and resolved 3 contacts in 20.6 ms; CADQ adversarial 100 performed 92,700 queries, 900 reselections, and 88.5% stale pops in 44.6 ms. This is a smoke result, not a performance conclusion, but it is evidence that the current CADQ safeguard destroys its hoped-for advantage on this workload.

## Prior work and limitations

Event-driven hard-sphere scheduling and invalid-event handling are established research areas; CADQ is not claimed novel. Useful references: Gerald Paul, *A Complexity O(1) Priority Queue for Event Driven Molecular Dynamics Simulations* (2007), DOI [10.1016/j.jcp.2006.06.042](https://doi.org/10.1016/j.jcp.2006.06.042); Bannerman et al., *DynamO* (2011), DOI [10.1002/jcc.21915](https://doi.org/10.1002/jcc.21915); and Johnson et al., *Reflections on Simultaneous Impact* ([paper index](https://www.cs.columbia.edu/cg/rosi/)).

This pass intentionally does not yet claim spatial broad phases, bucket calendars, JMH measurements, adaptive switching, property fuzzing, CSV aggregation, or million-ball scalability. Those require measured implementations rather than placeholders. JSONL output is the current machine-readable dataset format.
