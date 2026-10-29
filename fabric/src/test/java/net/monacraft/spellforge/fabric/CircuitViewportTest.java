package net.monacraft.spellforge.fabric;

import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitViewportTest {
    @Test
    void centersNodesSavedFarFromTheOrigin() {
        CircuitNode node = node(0, 60, -45);
        CircuitViewport.View view = fit(List.of(node));

        assertEquals(1.0, view.zoom());
        assertTrue(Math.abs(view.panX()) > 1_000);
        assertTrue(Math.abs(view.panY()) > 1_000);
        assertFalse(view.repairedCoordinates());
        assertVisible(node, view);
    }

    @Test
    void zoomsOutToKeepASpreadOutCircuitRecoverable() {
        CircuitNode first = node(0, -30, -20);
        CircuitNode second = node(1, 35, 25);
        CircuitViewport.View view = fit(List.of(first, second));

        assertTrue(view.zoom() >= 0.02 && view.zoom() < 1.0);
        assertTrue(Math.abs(view.panX()) <= 24_000);
        assertTrue(Math.abs(view.panY()) <= 24_000);
        assertVisible(first, view);
        assertVisible(second, view);
    }

    @Test
    void repairsNonFiniteCoordinatesUsingTheLegacySlot() {
        CircuitViewport.View view = fit(List.of(node(7, Double.NaN, Double.POSITIVE_INFINITY)));

        assertEquals(1.0, view.zoom());
        assertTrue(view.repairedCoordinates());
        assertTrue(Double.isFinite(CircuitViewport.coordinate(Double.NaN, 2, -64, 68)));
    }

    @Test
    void emptyCircuitUsesTheDefaultViewport() {
        CircuitViewport.View view = fit(List.of());

        assertEquals(1.0, view.zoom());
        assertEquals(0, view.panX());
        assertEquals(0, view.panY());
    }

    private static CircuitViewport.View fit(List<CircuitNode> nodes) {
        return CircuitViewport.fit(nodes, 760, 440, 150, 118, 76,
                -64, 68, 0.02, 1.0, 24_000);
    }

    private static CircuitNode node(int slot, double x, double y) {
        return new CircuitNode(UUID.randomUUID(), slot, UUID.randomUUID(), "FIRE", 1, Map.of(), x, y);
    }

    private static void assertVisible(CircuitNode node, CircuitViewport.View view) {
        double x = 380 + (node.uiX() - 2) * 150 * view.zoom() + view.panX();
        double y = 220 + (node.uiY() - 2) * 150 * view.zoom() + view.panY();
        assertTrue(x >= 0 && x + 118 * view.zoom() <= 760, "node x bounds");
        assertTrue(y >= 0 && y + 76 * view.zoom() <= 440, "node y bounds");
    }
}
