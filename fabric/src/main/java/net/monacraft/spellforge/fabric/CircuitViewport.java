package net.monacraft.spellforge.fabric;

import net.monacraft.spellforge.model.SpellModel.CircuitNode;

import java.util.List;

/** Computes a recoverable editor viewport without mutating the saved circuit. */
final class CircuitViewport {
    private CircuitViewport() {}

    static View fit(List<CircuitNode> nodes, int canvasWidth, int canvasHeight,
                    double worldUnit, int nodeWidth, int nodeHeight,
                    double worldMin, double worldMax,
                    double minimumZoom, double maximumZoom, int panLimit) {
        if (nodes == null || nodes.isEmpty()) return new View(1.0, 0, 0, false);

        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        boolean repaired = false;

        for (CircuitNode node : nodes) {
            double fallbackX = Math.floorMod(node.slotIndex(), 5);
            double fallbackY = Math.floorDiv(Math.max(0, node.slotIndex()), 5);
            double uiX = coordinate(node.uiX(), fallbackX, worldMin, worldMax);
            double uiY = coordinate(node.uiY(), fallbackY, worldMin, worldMax);
            repaired |= !Double.isFinite(node.uiX()) || !Double.isFinite(node.uiY());

            double worldX = (uiX - 2.0) * worldUnit;
            double worldY = (uiY - 2.0) * worldUnit;
            minimumX = Math.min(minimumX, worldX);
            minimumY = Math.min(minimumY, worldY);
            maximumX = Math.max(maximumX, worldX + nodeWidth);
            maximumY = Math.max(maximumY, worldY + nodeHeight);
        }

        double availableWidth = Math.max(1.0, canvasWidth - 48.0);
        double availableHeight = Math.max(1.0, canvasHeight - 48.0);
        double contentWidth = Math.max(1.0, maximumX - minimumX);
        double contentHeight = Math.max(1.0, maximumY - minimumY);
        double fittedZoom = Math.min(availableWidth / contentWidth, availableHeight / contentHeight);
        double zoom = clamp(fittedZoom, minimumZoom, maximumZoom);
        double centerX = (minimumX + maximumX) / 2.0;
        double centerY = (minimumY + maximumY) / 2.0;
        int panX = (int) clamp(Math.round(-centerX * zoom), -panLimit, panLimit);
        int panY = (int) clamp(Math.round(-centerY * zoom), -panLimit, panLimit);
        return new View(zoom, panX, panY, repaired);
    }

    static double coordinate(double value, double fallback, double minimum, double maximum) {
        return clamp(Double.isFinite(value) ? value : fallback, minimum, maximum);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record View(double zoom, int panX, int panY, boolean repairedCoordinates) {}
}
