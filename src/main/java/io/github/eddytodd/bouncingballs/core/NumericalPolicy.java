package io.github.eddytodd.bouncingballs.core;
/** Centralized, scale-aware numerical policy. Times are never compared with a scattered magic epsilon. */
public record NumericalPolicy(double relativeTolerance, double absoluteTolerance, int rootIterations, int maxZeroTimeBatches) {
    public static final NumericalPolicy DEFAULT = new NumericalPolicy(64 * Math.ulp(1.0), 1e-12, 80, 128);
    public double tolerance(double scale) { return absoluteTolerance + relativeTolerance * Math.max(1.0, Math.abs(scale)); }
    public boolean nearZero(double v, double scale) { return Math.abs(v) <= tolerance(scale); }
    public boolean sameTime(double a, double b) { return Math.abs(a-b) <= tolerance(Math.max(Math.abs(a), Math.abs(b))); }
}
