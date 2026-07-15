package io.github.eddytodd.bouncingballs.core;
import io.github.eddytodd.bouncingballs.resolver.*;
import io.github.eddytodd.bouncingballs.scheduler.*;
public record SimulationConfig(SchedulerKind scheduler, ResolverKind resolver, NumericalPolicy numericalPolicy, double discreteStep) {
 public static final SimulationConfig DEFAULT=new SimulationConfig(SchedulerKind.GLOBAL_EVENT_QUEUE,ResolverKind.ITERATIVE,NumericalPolicy.DEFAULT,1e-3);
}
