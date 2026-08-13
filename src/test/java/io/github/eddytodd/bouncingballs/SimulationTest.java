package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.core.*;
import io.github.eddytodd.bouncingballs.resolver.*;
import io.github.eddytodd.bouncingballs.scheduler.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTest {
 private static Ball ball(int id,double x,double vx){return new Ball(id,1,1,1,new Vec2(x,0),new Vec2(vx,0),new Vec2(0,0));}
 @Test void velocityToiIsExact(){assertEquals(4,TimeOfImpact.ballBall(ball(0,0,1),ball(1,10,-1),NumericalPolicy.DEFAULT),1e-10);}
 @Test void accelerationToiUsesQuarticModel(){Ball a=new Ball(0,1,1,1,new Vec2(0,0),new Vec2(0,0),new Vec2(2,0));Ball b=ball(1,10,0);assertEquals(Math.sqrt(8),TimeOfImpact.ballBall(a,b,NumericalPolicy.DEFAULT),1e-9);}
 @ParameterizedTest @EnumSource(ResolverKind.class) void equalMassHeadOnConservesAndExchanges(ResolverKind resolver){Ball a=ball(0,10,3),b=ball(1,20,-1);Simulation s=new Simulation(List.of(a,b),new Bounds(0,-10,100,10),new SimulationConfig(SchedulerKind.GLOBAL_EVENT_QUEUE,resolver,NumericalPolicy.DEFAULT,.001));s.advance(3,100);assertEquals(-1,a.velocity.x,1e-8);assertEquals(3,b.velocity.x,1e-8);}
 @Test void schedulersAgreeOnSmallHeadOnCase(){for(SchedulerKind k:List.of(SchedulerKind.ALL_PAIRS_CCD,SchedulerKind.GLOBAL_EVENT_QUEUE,SchedulerKind.COMPUTE_AHEAD_DEPENDENCY_QUEUE)){Ball a=ball(0,10,3),b=ball(1,20,-1);Simulation s=new Simulation(List.of(a,b),new Bounds(0,-10,100,10),new SimulationConfig(k,ResolverKind.ITERATIVE,NumericalPolicy.DEFAULT,.001));s.advance(3,100);assertEquals(-1,a.velocity.x,1e-8,k.toString());assertEquals(3,b.velocity.x,1e-8,k.toString());}}
 @Test void staleQueueEntriesAreObserved(){Ball a=ball(0,10,3),b=ball(1,20,-1),c=ball(2,40,-2);Simulation s=new Simulation(List.of(a,b,c),new Bounds(0,-10,100,10),SimulationConfig.DEFAULT);s.advance(5,100);assertTrue(s.stats().staleEvents>0);}

 @Test void cadqBatchesAllEqualTimeOwners(){
  Ball left=ball(0,0,1),middle=ball(1,10,0),right=ball(2,20,-1);
  ComputeAheadDependencyQueue q=new ComputeAheadDependencyQueue();SimulationStats stats=new SimulationStats();
  q.rebuild(List.of(left,middle,right),new Bounds(-100,-100,100,100),NumericalPolicy.DEFAULT,stats);
  List<CollisionEvent> batch=q.nextBatch(NumericalPolicy.DEFAULT,stats);
  assertTrue(batch.size()>=3,"both physical simultaneous contacts, including duplicate ownership, must be in one batch");
  assertTrue(batch.stream().allMatch(e->NumericalPolicy.DEFAULT.sameTime(batch.get(0).time(),e.time())));
  Set<String> physical=new HashSet<>();
  for(CollisionEvent e:batch)if(e.b()!=null)physical.add(Math.min(e.a().id,e.b().id)+":"+Math.max(e.a().id,e.b().id));
  assertEquals(Set.of("0:1","1:2"),physical);
 }

 @Test void cadqInvalidationDoesNotFullReselectEveryOwner(){
  List<Ball> balls=new ArrayList<>();
  balls.add(ball(0,10,1));balls.add(ball(1,14,0));
  for(int i=2;i<8;i++)balls.add(ball(i,300+i*70,10));
  Bounds bounds=new Bounds(0,-10,1000,10);ComputeAheadDependencyQueue q=new ComputeAheadDependencyQueue();SimulationStats stats=new SimulationStats();
  q.rebuild(balls,bounds,NumericalPolicy.DEFAULT,stats);
  long initialFull=stats.cadqFullReselections;
  balls.get(0).generation++;balls.get(1).generation++;
  q.trajectoriesChanged(Set.of(balls.get(0),balls.get(1)),balls,bounds,NumericalPolicy.DEFAULT,stats);
  long updateFull=stats.cadqFullReselections-initialFull;
  assertTrue(updateFull<balls.size(),"a local two-body change must not trigger the old all-owner full reselection");
  assertTrue(stats.cadqLocalPairRefreshes>0,"unaffected owners should only test changed bodies");
 }
}
