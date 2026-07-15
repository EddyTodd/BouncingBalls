import java.util.*;

public class CollisionManager {

    final int WIDTH, HEIGHT;
    private final Ball[] balls;
    private final Map<Ball, List<Collision>> collisions = new HashMap<>();
    private final Set<Collision> activeCollisions = new HashSet<>();
    private final List<Collision> willAdd;
    private final List<Collision> willRemove;

    CollisionManager(int width, int height, Ball[] balls) {
        this.WIDTH = width;
        this.HEIGHT = height;
        this.balls = balls;
        int willLength = ((balls.length - 1) * balls.length) >>> 1;
        willAdd = new ArrayList<>(willLength);
        willRemove = new ArrayList<>(willLength);

        for (Ball b : balls)
            collisions.put(b, new ArrayList<>(balls.length));

        for (int i = 0; i < balls.length - 1; i++) {
            for (int j = i + 1; j < balls.length; j++) {
                Collision c = new BallCollision(balls[i], balls[j]);
                collisions.get(balls[i]).add(c);
                collisions.get(balls[j]).add(c);
                if (!Double.isNaN(c.time)) {
                    activeCollisions.add(c);
                }
            }
        }

        for (Ball b : balls) {
            Collision c = new WallCollision(b, width, height);
            collisions.get(b).add(c);
            activeCollisions.add(c);
        }
    }

    public void update() {
        for (Ball ball : balls) {
            ball.x += ball.vx;
            ball.y += ball.vy;
        }
        for (Collision c : activeCollisions) {
            c.update();
            if (0 < c.time && c.time < 1) c.calculateTime(); // for increased precision
            if (c.hasCollided()) {
                c.resolve();
                if (c instanceof WallCollision){
                    recalculateBall(((WallCollision) c).ball);
                } else if (c instanceof BallCollision) {
                    recalculateBall(((BallCollision) c).a);
                    recalculateBall(((BallCollision) c).b);
                }
            }
        }
        activeCollisions.removeAll(willRemove);
        willRemove.clear();
        activeCollisions.addAll(willAdd);
        willAdd.clear();
    }

    void recalculateBall(Ball ball) {
        for (Collision c : collisions.get(ball)) {
            double time = c.time;
            double newTime = c.calculateTime();
            if (Double.isNaN(time) && newTime >= 0) willAdd.add(c);
            if (time >= 0 && Double.isNaN(newTime)) willRemove.add(c);
        }
    }
}