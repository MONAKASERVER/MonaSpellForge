package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.model.SpellModel.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CircuitCompilerTest {
    private final CircuitCompiler compiler = new CircuitCompiler(FragmentCatalog.defaults());
    private final UUID owner = UUID.randomUUID();

    @Test void supportsRuntimeCostUpTo512() {
        assertEquals(512, CircuitCompiler.MAX_RUNTIME_COST);
    }

    @Test void compilesSimpleTypedCircuit() {
        CircuitNode trigger = node(0, "RIGHT_CLICK");
        CircuitNode element = node(1, "FIRE");
        CircuitNode shape = node(2, "PROJECTILE");
        Circuit circuit = circuit(List.of(trigger, element, shape), List.of(
                edge(trigger, "exec_out", element, "exec_in"),
                edge(element, "exec_out", shape, "exec_in"),
                edge(element, "element", shape, "element")
        ));
        CompilationResult result = compiler.compile(circuit);
        assertTrue(result.valid(), () -> result.issues().toString());
        assertEquals(3, result.spell().executionOrder().size());
        assertTrue(result.spell().manaCost() > 0);
    }

    @Test void rejectsTypeMismatchAndArbitraryCycle() {
        CircuitNode trigger = node(0, "RIGHT_CLICK");
        CircuitNode shape = node(1, "PROJECTILE");
        Circuit circuit = circuit(List.of(trigger, shape), List.of(
                edge(trigger, "exec_out", shape, "element"),
                edge(shape, "exec_out", shape, "exec_in")
        ));
        CompilationResult result = compiler.compile(circuit);
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("PORT_TYPE")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("CYCLE")));
    }

    @Test void enforcesNodeAndSlotLimits() {
        List<CircuitNode> nodes = new ArrayList<>();
        for (int i = 0; i < 26; i++) nodes.add(new CircuitNode(UUID.randomUUID(), i, UUID.randomUUID(), i == 0 ? "RIGHT_CLICK" : "PROJECTILE", 1, Map.of(), i, 0));
        CompilationResult result = compiler.compile(circuit(nodes, List.of()));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("NODE_LIMIT")));
    }

    @Test void arcSysCoreRequiresExecSignalAndKeepsTypedDataPorts() {
        CircuitNode trigger = node(0, "RIGHT_CLICK");
        CircuitNode core = new CircuitNode(UUID.randomUUID(), 1, UUID.randomUUID(), "ARCSYS_CORE", 5, Map.of(), 1, 0);
        CircuitNode element = node(2, "FIRE");
        CircuitNode shape = node(3, "PROJECTILE");
        CompilationResult valid = compiler.compile(circuit(List.of(trigger, core, element, shape), List.of(
                edge(trigger, "exec_out", core, "exec_in"), edge(core, "exec_out", element, "exec_in"),
                edge(element, "exec_out", shape, "exec_in"), edge(element, "element", shape, "element"))));
        assertTrue(valid.valid(), () -> valid.issues().toString());
        assertEquals(40, valid.spell().resolvedDefinitions().get(core.nodeId()).levels().get(5).runtimeCost());

        CompilationResult disconnected = compiler.compile(circuit(List.of(trigger, core, element, shape), List.of(
                edge(trigger, "exec_out", element, "exec_in"), edge(element, "exec_out", shape, "exec_in"),
                edge(element, "element", shape, "element"))));
        assertFalse(disconnected.valid());
        assertTrue(disconnected.issues().stream().anyMatch(issue -> issue.code().equals("REQUIRED_INPUT")
                && core.nodeId().toString().equals(issue.nodeOrEdgeId())));
    }

    @Test void arcSysCoreRejectsLevelSix() {
        CircuitNode trigger = node(0, "RIGHT_CLICK");
        CircuitNode core = new CircuitNode(UUID.randomUUID(), 1, UUID.randomUUID(), "ARCSYS_CORE", 6, Map.of(), 1, 0);
        CircuitNode shape = node(2, "BURST");
        CompilationResult result = compiler.compile(circuit(List.of(trigger, core, shape), List.of(
                edge(trigger, "exec_out", core, "exec_in"), edge(core, "exec_out", shape, "exec_in"))));
        assertFalse(result.valid());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("LEVEL_RANGE")));
    }

    private CircuitNode node(int slot, String id) { return new CircuitNode(UUID.randomUUID(), slot, UUID.randomUUID(), id, 1, Map.of(), slot, 0); }
    private CircuitEdge edge(CircuitNode from, String out, CircuitNode to, String in) { return new CircuitEdge(UUID.randomUUID(), from.nodeId(), out, to.nodeId(), in); }
    private Circuit circuit(List<CircuitNode> nodes, List<CircuitEdge> edges) { Instant now = Instant.now(); return new Circuit(UUID.randomUUID(), owner, "test", 0, 1, nodes, edges, now, now); }
}
