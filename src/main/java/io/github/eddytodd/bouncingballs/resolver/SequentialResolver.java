package io.github.eddytodd.bouncingballs.resolver;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** Deterministic ordered pair impulses: deliberately retained as a comparison baseline. */
public final class SequentialResolver implements ContactResolver {
 public void resolve(List<Contact> contacts, SimulationStats stats){contacts.stream().sorted(Comparator.comparingInt(Contact::stableId)).forEach(c->{double vn=c.normalVelocity();if(vn>=0)return;impulse(c,-(1+c.restitution())*vn/c.inverseMass());stats.resolvedContacts++;});}
 static void impulse(Contact c,double j){c.a().velocity.x+=j*c.nx()/c.a().mass;c.a().velocity.y+=j*c.ny()/c.a().mass;if(c.b()!=null){c.b().velocity.x-=j*c.nx()/c.b().mass;c.b().velocity.y-=j*c.ny()/c.b().mass;}}
}
