package io.github.eddytodd.bouncingballs.core;

/** A frictionless circular rigid body. Mass is independent of radius. */
public final class Ball {
    public final int id;
    public final double radius, mass, restitution;
    public final Vec2 position, velocity, acceleration;
    public long generation;
    public Ball(int id, double radius, double mass, double restitution, Vec2 position, Vec2 velocity, Vec2 acceleration) {
        if (!(radius > 0) || !(mass > 0) || restitution < 0 || restitution > 1) throw new IllegalArgumentException("radius/mass must be positive and restitution in [0,1]");
        this.id=id; this.radius=radius; this.mass=mass; this.restitution=restitution; this.position=position; this.velocity=velocity; this.acceleration=acceleration;
    }
    public static Ball withDensity(int id, double radius, double density, double restitution, Vec2 p, Vec2 v, Vec2 a) {
        return new Ball(id, radius, Math.PI * radius * radius * density, restitution, p, v, a);
    }
    public void advance(double dt) { position.x += velocity.x * dt + .5 * acceleration.x * dt * dt; position.y += velocity.y * dt + .5 * acceleration.y * dt * dt; velocity.x += acceleration.x * dt; velocity.y += acceleration.y * dt; }
}
