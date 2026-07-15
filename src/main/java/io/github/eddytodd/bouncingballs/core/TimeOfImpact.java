package io.github.eddytodd.bouncingballs.core;

import java.util.*;

/** Exact constant-acceleration trajectory model; roots are floating-point approximations isolated from its contact polynomial. */
public final class TimeOfImpact {
    private TimeOfImpact() {}
    public static double ballBall(Ball a, Ball b, NumericalPolicy p) {
        double rx=a.position.x-b.position.x, ry=a.position.y-b.position.y;
        double vx=a.velocity.x-b.velocity.x, vy=a.velocity.y-b.velocity.y;
        double ax=.5*(a.acceleration.x-b.acceleration.x), ay=.5*(a.acceleration.y-b.acceleration.y), r=a.radius+b.radius;
        double[] c={rx*rx+ry*ry-r*r,2*(rx*vx+ry*vy),vx*vx+vy*vy+2*(rx*ax+ry*ay),2*(vx*ax+vy*ay),ax*ax+ay*ay};
        return firstApproaching(c,rx,ry,vx,vy,ax,ay,p);
    }
    private static double firstApproaching(double[] c,double rx,double ry,double vx,double vy,double ax,double ay,NumericalPolicy p) {
        for(double t:PolynomialRoots.roots(c,p)) if(t>=-p.tolerance(t)) { double u=Math.max(0,t); double x=rx+vx*u+ax*u*u,y=ry+vy*u+ay*u*u; double dx=vx+2*ax*u,dy=vy+2*ay*u; if(x*dx+y*dy<=p.tolerance(Math.abs(x*dx)+Math.abs(y*dy))) return u; }
        return Double.POSITIVE_INFINITY;
    }
    public static double wall(Ball b, Bounds q, int wall, NumericalPolicy p) {
        double target, pos, vel, acc;
        switch(wall){case CollisionEvent.LEFT -> {target=q.minX()+b.radius;pos=b.position.x;vel=b.velocity.x;acc=.5*b.acceleration.x;}case CollisionEvent.RIGHT -> {target=q.maxX()-b.radius;pos=b.position.x;vel=b.velocity.x;acc=.5*b.acceleration.x;}case CollisionEvent.BOTTOM -> {target=q.minY()+b.radius;pos=b.position.y;vel=b.velocity.y;acc=.5*b.acceleration.y;}case CollisionEvent.TOP -> {target=q.maxY()-b.radius;pos=b.position.y;vel=b.velocity.y;acc=.5*b.acceleration.y;}default -> throw new IllegalArgumentException("wall");}
        double[] c={pos-target,vel,acc}; double best=Double.POSITIVE_INFINITY;
        for(double t:PolynomialRoots.roots(c,p)) if(t>=-p.tolerance(t)){double u=Math.max(0,t), d=vel+2*acc*u; boolean outward=(wall==CollisionEvent.LEFT||wall==CollisionEvent.BOTTOM)?d<0:d>0; if(outward) best=Math.min(best,u);}
        return best;
    }
}
