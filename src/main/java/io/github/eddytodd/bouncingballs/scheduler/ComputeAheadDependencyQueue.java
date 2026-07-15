package io.github.eddytodd.bouncingballs.scheduler;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** CADQ: one earliest prediction per owner plus reverse links. Full reselection after a trajectory change is a correctness-first safeguard. */
public final class ComputeAheadDependencyQueue implements EventScheduler {
 private final PriorityQueue<CollisionEvent> q=new PriorityQueue<>(); private final Map<Ball,CollisionEvent> outbound=new HashMap<>(); private final Map<Ball,Set<Ball>> inbound=new HashMap<>(); private double now;
 public void rebuild(List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){q.clear();outbound.clear();inbound.clear();for(Ball a:b)recompute(a,b,x,p,s);s.maxQueueSize=Math.max(s.maxQueueSize,q.size());}
 private void recompute(Ball a,List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){CollisionEvent old=outbound.remove(a);if(old!=null&&old.b()!=null){Set<Ball> set=inbound.get(old.b());if(set!=null)set.remove(a);}CollisionEvent best=null;List<CollisionEvent> candidates=new ArrayList<>();for(Ball z:b)if(z!=a)EventPredictions.addPair(a,z,p,s,candidates,now);for(int w=0;w<4;w++)EventPredictions.addWall(a,x,w,p,s,candidates,now);for(CollisionEvent e:candidates)if(best==null||e.compareTo(best)<0)best=e;if(best!=null){outbound.put(a,best);q.add(best);s.queuePushes++;if(best.b()!=null)inbound.computeIfAbsent(best.b(),k->new HashSet<>()).add(a);}s.predictionRecomputations++;}
 public List<CollisionEvent> nextBatch(NumericalPolicy p,SimulationStats s){while(!q.isEmpty()){CollisionEvent e=q.poll();s.queuePops++;if(outbound.get(e.a())!=e||!EventPredictions.valid(e)){s.staleEvents++;continue;}s.validEvents++;return List.of(e);}return List.of();}
 public void trajectoriesChanged(Set<Ball> changed,List<Ball>b,Bounds x,NumericalPolicy p,SimulationStats s){Set<Ball> affected=new HashSet<>(changed);for(Ball z:changed)affected.addAll(inbound.getOrDefault(z,Set.of()));s.dependencyInvalidations+=affected.size();/* A third party can acquire a new earlier collision with a changed body even without an old reverse edge. Recompute every owner to preserve correctness. */for(Ball a:b)recompute(a,b,x,p,s);s.maxQueueSize=Math.max(s.maxQueueSize,q.size());}
 public void timeAdvanced(double dt){now+=dt;}
}
