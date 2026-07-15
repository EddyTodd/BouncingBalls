package io.github.eddytodd.bouncingballs.scheduler;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** Heap of all predicted events; trajectory generations make old entries inert without mutable heap keys. */
public final class GlobalEventQueueScheduler implements EventScheduler {
 private final PriorityQueue<CollisionEvent> q=new PriorityQueue<>(); private double now;
 public void rebuild(List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){q.clear();for(Ball a:b)EventPredictions.addFor(a,b,x,p,s,q,true,now);s.queuePushes+=q.size();s.maxQueueSize=Math.max(s.maxQueueSize,q.size());}
 public List<CollisionEvent> nextBatch(NumericalPolicy p,SimulationStats s){CollisionEvent first=take(s);if(first==null)return List.of();List<CollisionEvent> r=new ArrayList<>();r.add(first);while(true){CollisionEvent e=peekValid(s);if(e==null||!p.sameTime(first.time(),e.time()))break;q.poll();s.queuePops++;s.validEvents++;r.add(e);}return r;}
 private CollisionEvent take(SimulationStats s){CollisionEvent e;while((e=q.poll())!=null){s.queuePops++;if(EventPredictions.valid(e)){s.validEvents++;return e;}s.staleEvents++;}return null;}
 private CollisionEvent peekValid(SimulationStats s){while(!q.isEmpty()&&!EventPredictions.valid(q.peek())){q.poll();s.queuePops++;s.staleEvents++;}return q.peek();}
 public void trajectoriesChanged(Set<Ball> changed,List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){for(Ball a:changed){for(Ball z:b)if(z!=a)EventPredictions.addPair(a,z,p,s,q,now);for(int w=0;w<4;w++)EventPredictions.addWall(a,x,w,p,s,q,now);}s.queuePushes+=changed.size()*(b.size()+3L);s.maxQueueSize=Math.max(s.maxQueueSize,q.size());}
 public void timeAdvanced(double dt){now+=dt;}
}
