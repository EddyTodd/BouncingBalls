package io.github.eddytodd.bouncingballs.core;

/** A ball-ball or ball-wall normal contact. Wall contacts have b == null. */
public record Contact(Ball a, Ball b, double nx, double ny, double restitution, int stableId) {
    public double inverseMass() { return 1.0 / a.mass + (b == null ? 0 : 1.0 / b.mass); }
    public double normalVelocity() { return (a.velocity.x - (b == null ? 0 : b.velocity.x)) * nx + (a.velocity.y - (b == null ? 0 : b.velocity.y)) * ny; }
}
