package io.github.eddytodd.bouncingballs.scheduler;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
public interface EventScheduler { void rebuild(List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats); List<CollisionEvent> nextBatch(NumericalPolicy policy, SimulationStats stats); void trajectoriesChanged(Set<Ball> changed, List<Ball> balls, Bounds bounds, NumericalPolicy policy, SimulationStats stats); void timeAdvanced(double dt); }
