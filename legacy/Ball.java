import java.awt.*;

class Ball {
    final double DIAMETER, RADIUS;       // diameter and radius
    double x, y;    // position
    double vx, vy;  // velocity
    final double m; // mass
    Color color;    // color

//    Ball() {
//        //this.D = 40;
//        this.DIAMETER = Math.random() * 40 + 20;
//        this.RADIUS = DIAMETER / 2;
//        this.x = Math.random() * (WIDTH - DIAMETER);
//        this.y = Math.random() * (HEIGHT - DIAMETER);
//        this.vx = Math.random() * 4 - 2;
//        this.vy = Math.random() * 4 - 2;
//        this.color = new Color((int) (Math.random() * 0xFFFFFF));
//        this.m = Math.PI * RADIUS * RADIUS;
//    }

    Ball(double DIAMETER, double x, double y, double vx, double vy, Color color) {
        this.DIAMETER = DIAMETER;
        this.RADIUS = DIAMETER / 2;
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.color = color;
        this.m = Math.PI * RADIUS * RADIUS;
    }
}