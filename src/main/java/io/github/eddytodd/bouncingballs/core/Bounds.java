package io.github.eddytodd.bouncingballs.core;
public record Bounds(double minX, double minY, double maxX, double maxY) {
    public Bounds { if (!(maxX > minX && maxY > minY)) throw new IllegalArgumentException("empty bounds"); }
}
