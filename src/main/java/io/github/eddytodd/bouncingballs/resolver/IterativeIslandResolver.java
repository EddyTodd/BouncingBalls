package io.github.eddytodd.bouncingballs.resolver;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** Projected Gauss-Seidel normal-impulse solver with symmetric forward/reverse sweeps. */
public final class IterativeIslandResolver implements ContactResolver {
 private final int iterations; private final double threshold;
 public IterativeIslandResolver(int iterations,double threshold){this.iterations=iterations;this.threshold=threshold;}
 public void resolve(List<Contact> cs,SimulationStats stats){List<Contact> c=new ArrayList<>(cs);c.sort(Comparator.comparingInt(Contact::stableId));double[] applied=new double[c.size()];for(int it=0;it<iterations;it++){double max=0;for(int pass=0;pass<2;pass++)for(int k=0;k<c.size();k++){int i=pass==0?k:c.size()-1-k;Contact x=c.get(i);double target=x.normalVelocity()<0?-(1+x.restitution())*x.normalVelocity():0;double delta=Math.max(0,applied[i]+target/x.inverseMass())-applied[i];if(delta!=0){SequentialResolver.impulse(x,delta);applied[i]+=delta;max=Math.max(max,Math.abs(delta));}}if(max<threshold)break;}for(double j:applied)if(j>0)stats.resolvedContacts++;}
}
