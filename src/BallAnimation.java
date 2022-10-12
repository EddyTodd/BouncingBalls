import javax.swing.*;
import javax.swing.Timer;

import java.awt.*;
import java.awt.geom.Ellipse2D;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

class BallAnimation extends JPanel {
    public static final int WIDTH = 500, HEIGHT = 420;
    protected Ball[] balls;

    private static final int TIME_STEP = 14;

    CollisionManager collisionManager;


    public static void main(String[] args) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Bouncing Balls");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(new BallAnimation(30));
            frame.setResizable(false);
            frame.pack();
            frame.setVisible(true);
        });
    }

    public BallAnimation() {
        this.setPreferredSize(new Dimension(WIDTH, HEIGHT));

        balls = new Ball[2];

        balls[0] = new Ball(50, 100, 50, 1, 1, new Color((int) (Math.random() * 0xFFFFFF)));
        balls[1] = new Ball(50, 110, 150, 1, -1, new Color((int) (Math.random() * 0xFFFFFF)));

        collisionManager = new CollisionManager(WIDTH, HEIGHT, balls);

        Timer timer = new Timer(TIME_STEP, v -> update());
        timer.start();
    }

    public BallAnimation(int numBalls) {
        this.setPreferredSize(new Dimension(WIDTH, HEIGHT));

        balls = new Ball[numBalls];
        for (int i = 0; i < numBalls; i++) {
            double diameter = Math.random() * 40 + 20;
            balls[i] = new Ball(
                    diameter,
                    Math.random() * (WIDTH - diameter),
                    Math.random() * (HEIGHT - diameter),
                    Math.random() * 4 - 2,
                    Math.random() * 4 - 2,
                    new Color((int) (Math.random() * 0xFFFFFF)));
        }
        collisionManager = new CollisionManager(WIDTH, HEIGHT, balls);

        Timer timer = new Timer(TIME_STEP, v -> update());
        timer.start();
    }

    void update() {
        collisionManager.update();
//        displayEnergy();
        repaint();
    }

    void displayEnergy() {
        double e = 0;
        for (Ball b : balls)
            e += b.m * pow(sqrt(b.vx * b.vx + b.vy * b.vy), 2) / 2;
        System.out.println(e);
    }

    @Override
    public void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Clear the screen
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, WIDTH, HEIGHT);

        // Draw the balls
        for (Ball ball : balls) {
            g2.setColor(ball.color);
            g2.fill(new Ellipse2D.Double(ball.x, ball.y, ball.DIAMETER, ball.DIAMETER));
        }
    }
}