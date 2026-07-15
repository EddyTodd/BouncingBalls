package io.github.eddytodd.bouncingballs.core;
public final class SimulationStats {
    public long toiQueries,candidateChecks,queuePushes,queuePops,validEvents,staleEvents,resolvedContacts,zeroTimeBatches,predictionRecomputations,dependencyInvalidations,maxQueueSize;
    public double stalePercent(){long n=validEvents+staleEvents;return n==0?0:100.0*staleEvents/n;}
}
