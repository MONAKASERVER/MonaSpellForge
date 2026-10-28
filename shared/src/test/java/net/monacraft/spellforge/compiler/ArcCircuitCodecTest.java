package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitEdge;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArcCircuitCodecTest {
    @Test void roundTripsNodesEdgesCoordinatesAndParametersDeterministically() {
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        Circuit circuit = new Circuit(UUID.randomUUID(), owner, "試験回路", 4, 1, List.of(
                new CircuitNode(first, 0, UUID.randomUUID(), "TRIGGER_RIGHT_CLICK", 1, Map.of("z", "2", "a", "1"), -42.5, 88),
                new CircuitNode(second, 1, UUID.randomUUID(), "FIRE", 3, Map.of(), 501, -900)
        ), List.of(new CircuitEdge(UUID.randomUUID(), first, "exec_out", second, "exec_in")), Instant.EPOCH, Instant.EPOCH);
        String firstSource = ArcCircuitCodec.toArcSource(circuit);
        String secondSource = ArcCircuitCodec.toArcSource(circuit);
        assertEquals(firstSource, secondSource);
        var decoded = ArcCircuitCodec.fromArcSource(firstSource);
        assertEquals("試験回路", decoded.name());
        assertEquals(2, decoded.nodes().size());
        assertEquals(Map.of("a", "1", "z", "2"), decoded.nodes().getFirst().parameters());
        assertEquals(-42.5, decoded.nodes().getFirst().x());
        assertEquals("exec_in", decoded.edges().getFirst().targetPort());
    }

    @Test void rejectsTamperedPayload() {
        String source = ArcCircuitCodec.toArcSource(Circuit.empty(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> ArcCircuitCodec.fromArcSource(
                source.replace(ArcCircuitCodec.MARKER, ArcCircuitCodec.MARKER + "A")));
    }
}
