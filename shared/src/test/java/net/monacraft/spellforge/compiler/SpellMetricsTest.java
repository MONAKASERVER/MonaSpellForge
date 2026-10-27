package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.model.SpellModel.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SpellMetricsTest {
    private final FragmentCatalog catalog = FragmentCatalog.defaults();
    private final CircuitCompiler compiler = new CircuitCompiler(catalog);

    @Test void exposesArmorPenetrationAndTrueDamageInCombatEstimate() {
        CircuitNode trigger = node(0, "RIGHT_CLICK", 1);
        CircuitNode element = node(1, "FIRE", 1);
        CircuitNode shape = node(2, "PROJECTILE", 1);
        CircuitNode penetration = node(3, "ARMOR_PENETRATION", 5);
        CircuitNode truth = node(4, "TRUE_DAMAGE", 5);
        Circuit circuit = circuit(List.of(trigger, element, shape, penetration, truth), List.of(
                edge(trigger, "exec_out", element, "exec_in"),
                edge(element, "exec_out", penetration, "exec_in"),
                edge(penetration, "exec_out", truth, "exec_in"),
                edge(truth, "exec_out", shape, "exec_in"),
                edge(element, "element", shape, "element")
        ));
        CompilationResult result = compiler.compile(circuit);
        assertTrue(result.valid(), () -> result.issues().toString());
        SpellMetrics.CombatMetrics metrics = SpellMetrics.calculate(result.spell());
        assertTrue(metrics.trueDamage());
        assertEquals(55.0, metrics.armorPenetrationPercent(), 0.001);
        assertTrue(metrics.attackPower() >= 300.0);
        assertEquals(result.spell().manaCost(), metrics.manaCost());
    }

    private CircuitNode node(int slot, String id, int level) {
        return new CircuitNode(UUID.randomUUID(), slot, UUID.randomUUID(), id, level, Map.of(), slot, 0);
    }

    private CircuitEdge edge(CircuitNode from, String out, CircuitNode to, String in) {
        return new CircuitEdge(UUID.randomUUID(), from.nodeId(), out, to.nodeId(), in);
    }

    private Circuit circuit(List<CircuitNode> nodes, List<CircuitEdge> edges) {
        Instant now = Instant.now();
        return new Circuit(UUID.randomUUID(), UUID.randomUUID(), "metrics", 0, 1, nodes, edges, now, now);
    }
}
