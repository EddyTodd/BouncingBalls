package io.github.eddytodd.bouncingballs.resolver;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.List;
public interface ContactResolver { void resolve(List<Contact> contacts, SimulationStats stats); }
