package io.github.eddytodd.bouncingballs;

import io.github.eddytodd.bouncingballs.cli.ParametricWorkloads;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParametricWorkloadsTest {
    @Test
    void designAndGenerationRemainValid() {
        Set<ParametricWorkloads.Parameters> unique = new HashSet<>();
        for (int index = 1; index <= 24; index++) {
            ParametricWorkloads.Parameters p = ParametricWorkloads.halton(index);
            assertTrue(p.fillFraction() >= 0.001 && p.fillFraction() <= 0.08);
            assertTrue(p.clusterStrength() >= 0 && p.clusterStrength() <= 0.95);
            assertTrue(p.speedScale() >= 0.5 && p.speedScale() <= 300.0);
            assertTrue(unique.add(p));
            for (int bodies : List.of(100, 300)) {
                for (long seed = 1; seed <= 3; seed++) {
                    ParametricWorkloads.Setup a = ParametricWorkloads.create(bodies, seed, p);
                    ParametricWorkloads.Setup b = ParametricWorkloads.create(bodies, seed, p);
                    assertDoesNotThrow(() -> ParametricWorkloads.validateInitialState(a));
                    assertEquals(a.balls().size(), b.balls().size());
                    for (int i = 0; i < bodies; i++) {
                        assertEquals(a.balls().get(i).position.x, b.balls().get(i).position.x, 0);
                        assertEquals(a.balls().get(i).position.y, b.balls().get(i).position.y, 0);
                        assertEquals(a.balls().get(i).velocity.x, b.balls().get(i).velocity.x, 0);
                        assertEquals(a.balls().get(i).velocity.y, b.balls().get(i).velocity.y, 0);
                    }
                }
            }
        }
    }
}
