package io.github.eddytodd.bouncingballs.core;

/** Mutable two-dimensional vector used only at the engine boundary. */
public final class Vec2 {
    public double x, y;
    public Vec2(double x, double y) { this.x = x; this.y = y; }
    public Vec2 copy() { return new Vec2(x, y); }
    public double dot(Vec2 other) { return x * other.x + y * other.y; }
    public double normSquared() { return x * x + y * y; }
}
