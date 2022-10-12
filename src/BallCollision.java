import static java.lang.Math.sqrt;

class BallCollision extends Collision {
    Ball a, b;

    public BallCollision(Ball a, Ball b) {
        this.a = a;
        this.b = b;
        calculateTime();
    }

    /**
     * Checks if the provided Ball objects' x and y positions are getting closer.
     *
     * @return true if the Ball objects are getting closer
     */
    private boolean canCollide() {
        return !((a.x + a.DIAMETER < b.x && a.vx < b.vx) || (a.y + a.DIAMETER < b.y && a.vy < b.vy) || (b.x + b.DIAMETER < a.x && b.vx < a.vx) || (b.y + b.DIAMETER < a.y && b.vy < a.vy));
    }

    /**
     * Computes the amount of steps (frames) left until the provided balls
     * collide. The decimal part indicates the fraction of the step where
     * the Balls will collide.
     */
    @Override
    double calculateTime() {
        if (!canCollide()) return time = Double.NaN;

        double d = a.RADIUS - b.RADIUS;
        double dvx = a.vx - b.vx;
        double dvy = a.vy - b.vy;
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double p = dvx * dvx + dvy * dvy;
        double q = 2 * (d * (dvx + dvy) + dx * dvx + dy * dvy);

        time = (-sqrt(q * q - 4 * p * (d * d - 4 * a.RADIUS * b.RADIUS + 2 * d * (dx + dy) + dx * dx + dy * dy)) - q) / (2 * p);
        return time = time < -1 ? Double.NaN : time;
    }

    @Override
    public void resolve() {
        // Set ball positions at collision time
        a.x += a.vx * time;
        a.y += a.vy * time;
        b.x += b.vx * time;
        b.y += b.vy * time;

        // Normal line
        double m = a.x - b.x + a.RADIUS - b.RADIUS;
        double n = a.y - b.y + a.RADIUS - b.RADIUS;

        double r = 2 * ((m * a.vx + n * a.vy - m * b.vx - n * b.vy) / (m * m + n * n)) / (a.m + b.m);

        // New velocities
        a.vx -= r * m * b.m;
        a.vy -= r * n * b.m;
        b.vx += r * m * a.m;
        b.vy += r * n * a.m;

        // New positions
        a.x -= a.vx * time;
        a.y -= a.vy * time;
        b.x -= b.vx * time;
        b.y -= b.vy * time;
    }

    @Override
    public int hashCode() {
        return a.hashCode() * b.hashCode();
    }
}