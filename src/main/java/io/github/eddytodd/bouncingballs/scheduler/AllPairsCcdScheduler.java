package io.github.eddytodd.bouncingballs.scheduler;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** High-confidence continuous reference scheduler: rebuilds every prediction after each event batch. */
public final class AllPairsCcdScheduler implements EventScheduler {
 private final PriorityQueue<CollisionEvent> q=new PriorityQueue<>(); private double now;
 public void rebuild(List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){q.clear();for(Ball a:b)EventPredictions.addFor(a,b,x,p,s,q,true,now);s.maxQueueSize=Math.max(s.maxQueueSize,q.size());}
 public List<CollisionEvent> nextBatch(NumericalPolicy p,SimulationStats s){if(q.isEmpty())return List.of();CollisionEvent first=q.poll();s.validEvents++;List<CollisionEvent> r=new ArrayList<>();r.add(first);while(!q.isEmpty()&&p.sameTime(first.time(),q.peek().time())){r.add(q.poll());s.validEvents++;}return r;}
 public void trajectoriesChanged(Set<Ball> c,List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){rebuild(b,x,p,s);}
 public void timeAdvanced(double dt){now+=dt;}
}
