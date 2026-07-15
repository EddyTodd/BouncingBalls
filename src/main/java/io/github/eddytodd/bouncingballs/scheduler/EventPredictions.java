package io.github.eddytodd.bouncingballs.scheduler;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
final class EventPredictions {
 private EventPredictions(){}
 static long sequence;
 static void addFor(Ball a,List<Ball> balls,Bounds bounds,NumericalPolicy p,SimulationStats s,Collection<CollisionEvent> out,boolean pairs,double now){for(Ball b:balls)if(pairs&&b.id>a.id)addPair(a,b,p,s,out,now);for(int w=0;w<4;w++)addWall(a,bounds,w,p,s,out,now);}
 static void addPair(Ball a,Ball b,NumericalPolicy p,SimulationStats s,Collection<CollisionEvent> out,double now){s.candidateChecks++;s.toiQueries++;double t=TimeOfImpact.ballBall(a,b,p);if(Double.isFinite(t))out.add(new CollisionEvent(now+t,a,b,CollisionEvent.NONE,a.generation,b.generation,sequence++));}
 static void addWall(Ball a,Bounds q,int w,NumericalPolicy p,SimulationStats s,Collection<CollisionEvent> out,double now){s.toiQueries++;double t=TimeOfImpact.wall(a,q,w,p);if(Double.isFinite(t))out.add(new CollisionEvent(now+t,a,null,w,a.generation,-1,sequence++));}
 static boolean valid(CollisionEvent e){return e.a().generation==e.generationA()&&(e.b()==null||e.b().generation==e.generationB());}
}
