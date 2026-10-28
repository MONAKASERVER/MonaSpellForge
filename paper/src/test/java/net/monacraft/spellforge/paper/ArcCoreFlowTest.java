package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitEdge;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArcCoreFlowTest {
    @Test void manualTriggerStartsFromConfiguredTriggerNodesInsteadOfBypassingTheCore() {
        CircuitNode trigger = node(0, "RIGHT_CLICK");
        CircuitNode core = node(1, "ARCSYS_CORE");
        CircuitNode fire = node(2, "FIRE");
        CircuitNode shape = node(3, "PROJECTILE");
        Instant now = Instant.now();
        Circuit circuit = new Circuit(UUID.randomUUID(), UUID.randomUUID(), "core-flow", 1, 1,
                List.of(trigger, core, fire, shape), List.of(
                edge(trigger, "exec_out", core, "exec_in"),
                edge(core, "exec_out", fire, "exec_in"),
                edge(fire, "exec_out", shape, "exec_in"),
                edge(fire, "element", shape, "element")), now, now);
        var compilation = new CircuitCompiler(FragmentCatalog.defaults()).compile(circuit);
        assertEquals(1, ArcCoreService.reachableCores(circuit, compilation.spell(), "RIGHT_CLICK").size());
        assertEquals(1, ArcCoreService.reachableCores(circuit, compilation.spell(), "MANUAL").size());
    }

    private static CircuitNode node(int slot, String id) {
        return new CircuitNode(UUID.randomUUID(), slot, UUID.randomUUID(), id, 1, Map.of(), slot, 0);
    }
    private static CircuitEdge edge(CircuitNode from, String out, CircuitNode to, String in) {
        return new CircuitEdge(UUID.randomUUID(), from.nodeId(), out, to.nodeId(), in);
    }
}
