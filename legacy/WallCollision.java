class WallCollision extends Collision {
    Ball ball;
    Wall wall = null;
    final int WIDTH, HEIGHT;

    public WallCollision(Ball ball, int width, int height) {
        this.ball = ball;
        this.WIDTH = width;
        this.HEIGHT = height;
        calculateTime();
    }

    @Override
    double calculateTime() {
        double t;
        time = Double.MAX_VALUE;
        if (ball.vx == 0 && ball.vy == 0) {
            wall = null;
            time = Double.NaN;
        }
        if (ball.vx < 0) {
            t = -ball.x / ball.vx;
            if (t < time) {
                time = t;
                wall = Wall.LEFT;
            }
        } else if (ball.vx > 0) {
            t = (WIDTH - (ball.x + ball.DIAMETER)) / ball.vx;
            if (t < time) {
                time = t;
                wall = Wall.RIGHT;
            }
        }
        if (ball.vy < 0) {
            t = -ball.y / ball.vy;
            if (t < time) {
                time = t;
                wall = Wall.TOP;
            }
        } else if (ball.vy > 0) {
            t = (HEIGHT - (ball.y + ball.DIAMETER)) / ball.vy;
            if (t < time) {
                time = t;
                wall = Wall.DOWN;
            }
        }
        return time;
    }

    @Override
    public void resolve() {
        switch (wall) {
            case TOP -> {
                ball.vy = -ball.vy;
                ball.y = -ball.y;
            }
            case DOWN -> {
                ball.vy = -ball.vy;
                ball.y -= 2 * (ball.y + ball.DIAMETER - HEIGHT);
            }
            case LEFT -> {
                ball.vx = -ball.vx;
                ball.x = -ball.x;
            }
            case RIGHT -> {
                ball.vx = -ball.vx;
                ball.x -= 2 * (ball.x + ball.DIAMETER - WIDTH);
            }
        }
        calculateTime();
    }

    @Override
    public int hashCode() {
        return ball.hashCode() * ((wall == null) ? 1 : wall.hashCode());
    }

    enum Wall {
        TOP, RIGHT, LEFT, DOWN
    }
}
