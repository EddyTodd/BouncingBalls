package io.github.eddytodd.bouncingballs.core;

import java.util.*;

/** Operation counters and deterministic structural diagnostics for one simulation. */
public final class SimulationStats {
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final long BATCH_MARKER = 0x9e3779b97f4a7c15L;

    public long toiQueries, candidateChecks, queuePushes, queuePops, validEvents, staleEvents,
            resolvedContacts, zeroTimeBatches, predictionRecomputations, dependencyInvalidations,
            cadqFullReselections, cadqLocalPairRefreshes, maxQueueSize;

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
