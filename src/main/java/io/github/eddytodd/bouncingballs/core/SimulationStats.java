package io.github.eddytodd.bouncingballs.core;

import java.util.*;

/** Operation counters and deterministic structural diagnostics for one simulation. */
public final class SimulationStats {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long BATCH_MARKER = 0x9e3779b97f4a7c15L;

    public long toiQueries, candidateChecks, predictedEventMaterializations, queuePushes, queuePops,
            validEvents, staleEvents, resolvedContacts, zeroTimeBatches, predictionRecomputations,
            dependencyInvalidations, cadqFullReselections, cadqLocalPairRefreshes, maxQueueSize;

    /**
     * Exact TOI work by mechanism. Pair queries are quadratic when relative acceleration is exactly zero and
     * quartic otherwise; wall trajectories remain at most quadratic under the constant-acceleration model.
     */
    public long pairToiQueries, quadraticPairToiQueries, quarticPairToiQueries, wallToiQueries;

    /** Opt-in CADQ coarse phase timings. Zero when -Dbouncingballs.cadqProfile=true is not supplied. */
    public long cadqQueueNanos, cadqDependencyDiscoveryNanos, cadqFullReselectionNanos, cadqLocalRefreshNanos;

    /** CADQ mechanism counts used to interpret coarse timings without adding nested timer overhead. */
    public long cadqQueueValidationChecks, cadqDependencyBatches, cadqFullOwnersVisited,
            cadqLocalOwnersVisited, cadqLocalOwnersModified, cadqRetainedInstalls, cadqRetainedRemovals,
            cadqInboundSets, cadqInboundClears, cadqTemporalBoundChecks, cadqTemporalPrunes;

    /**
     * Full-reselection candidate-ordering diagnostics. A retained-target opportunity means an owner had a previously
     * retained canonical pair target available before its outbound selection was invalidated. The target is either
     * conservatively pruned under the wall-seeded horizon or exact-probed once before the normal canonical scan.
     */
    public long cadqWarmStartOpportunities, cadqWarmStartTemporalPrunes, cadqWarmStartExactProbes,
            cadqWarmStartFiniteHits, cadqWarmStartHorizonTightens;

    /** Number of non-empty deduplicated physical-contact batches presented to the resolver. */
    public long physicalContactBatches;

    /** Number of deduplicated physical contacts presented to the resolver. */
    public long physicalContactsObserved;

    /**
     * Order-sensitive hash of physical contact batches, but order-insensitive within each simultaneous batch.
     * This is a diagnostic fingerprint, not a cryptographic proof of identical histories.
     */
    public long physicalContactHash = FNV_OFFSET_BASIS;

    public double stalePercent() {
        long n = validEvents + staleEvents;
        return n == 0 ? 0 : 100.0 * staleEvents / n;
    }

    public double cadqTemporalPrunePercent() {
        return cadqTemporalBoundChecks == 0 ? 0 : 100.0 * cadqTemporalPrunes / cadqTemporalBoundChecks;
    }

    /** Sum of the non-overlapping coarse CADQ scheduler phases measured during advance. */
    public long cadqProfiledAdvanceNanos() {
        return cadqQueueNanos + cadqDependencyDiscoveryNanos + cadqFullReselectionNanos + cadqLocalRefreshNanos;
    }

    void observePhysicalBatch(List<Contact> contacts) {
        if (contacts.isEmpty()) return;
        int[] ids = new int[contacts.size()];
        for (int i = 0; i < contacts.size(); i++) ids[i] = contacts.get(i).stableId();
        Arrays.sort(ids);

        physicalContactBatches++;
        physicalContactsObserved += ids.length;
        mix(BATCH_MARKER);
        mix(ids.length);
        for (int id : ids) mix(Integer.toUnsignedLong(id));
        mix(~BATCH_MARKER);
    }

    private void mix(long value) {
        physicalContactHash ^= value;
        physicalContactHash *= FNV_PRIME;
    }
}
