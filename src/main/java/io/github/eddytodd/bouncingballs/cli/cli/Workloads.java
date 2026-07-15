package io.github.eddytodd.bouncingballs.cli;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
public final class Workloads {
 public enum Kind { SPARSE_UNIFORM, DENSE_UNIFORM, CLUSTERED, HIGH_VELOCITY, NEWTON_CRADLE, SYMMETRIC_IMPACT, WALL_DOMINATED, ACCELERATED, ADVERSARIAL_INVALIDATION }
 public record Setup(List<Ball> balls, Bounds bounds) {}
 private Workloads(){}
 public static Setup create(Kind kind,int count,long seed,double restitution){Random r=new Random(seed);Bounds q=new Bounds(0,0,1000,1000);List<Ball>b=new ArrayList<>();if(kind==Kind.NEWTON_CRADLE){int n=Math.max(2,count);for(int i=0;i<n;i++)b.add(new Ball(i,10,1,restitution,new Vec2(300+i*20,500),new Vec2(i==0?200:0,0),new Vec2(0,0)));return new Setup(b,q);}if(kind==Kind.SYMMETRIC_IMPACT){b.add(new Ball(0,10,1,restitution,new Vec2(450,500),new Vec2(100,0),new Vec2(0,0)));b.add(new Ball(1,10,1,restitution,new Vec2(550,500),new Vec2(-100,0),new Vec2(0,0)));b.add(new Ball(2,10,1,restitution,new Vec2(500,450),new Vec2(0,100),new Vec2(0,0)));return new Setup(b,q);}double radius=kind==Kind.DENSE_UNIFORM?8:3;for(int i=0;i<count;i++){double x,y;if(kind==Kind.CLUSTERED||kind==Kind.ADVERSARIAL_INVALIDATION){double cx=i%3*300+200,cy=i%2*300+300;x=cx+r.nextGaussian()*50;y=cy+r.nextGaussian()*50;}else{x=radius+r.nextDouble()*(1000-2*radius);y=radius+r.nextDouble()*(1000-2*radius);}double speed=kind==Kind.HIGH_VELOCITY?300:kind==Kind.WALL_DOMINATED?180:30;double angle=r.nextDouble()*Math.PI*2;Vec2 a=kind==Kind.ACCELERATED?new Vec2(0,-9.81):new Vec2(0,0);b.add(new Ball(i,radius,1,restitution,new Vec2(x,y),new Vec2(Math.cos(angle)*speed,Math.sin(angle)*speed),a));}return new Setup(b,q);}
}
