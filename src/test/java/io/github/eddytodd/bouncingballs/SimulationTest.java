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
}
