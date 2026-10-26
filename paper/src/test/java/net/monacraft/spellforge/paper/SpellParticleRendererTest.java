package net.monacraft.spellforge.paper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SpellParticleRendererTest {
    @Test
    void circuitModifiersChangeVisualPlan() {
        var plan = SpellParticleRenderer.plan(Map.of(
                        "FIRE", 4, "PROJECTILE", 5, "SPLIT", 3, "HOMING", 2,
                        "PIERCE", 1, "CHAIN", 1, "DURATION", 2, "AMPLIFY_X4", 1),
                "FIRE", "PROJECTILE", 40, 5, 4, 240, 1.0);

        assertEquals("FIRE", plan.element());
        assertEquals("PROJECTILE", plan.shape());
        assertEquals(5, plan.splitBranches());
        assertTrue(plan.homing());
        assertTrue(plan.pierce());
        assertTrue(plan.chain());
        assertEquals(2, plan.durationRings());
        assertTrue(plan.dustSize() > 1.0f);
    }

    @Test
    void particleBudgetIsCappedAndReducedAtLowTps() {
        Map<String, Integer> levels = Map.of("ARCANE", 5, "BURST", 5, "POWER", 5, "AREA_SCALE", 5);
        var normal = SpellParticleRenderer.plan(levels, "ARCANE", "BURST", 64, 12, 4, 120, 1.0);
        var degraded = SpellParticleRenderer.plan(levels, "ARCANE", "BURST", 64, 12, 4, 120, 0.4);

        assertEquals(120, normal.particleBudget());
        assertEquals(48, degraded.particleBudget());
        assertTrue(degraded.particleBudget() < normal.particleBudget());
    }

    @Test
    void areaScaleChangesRenderedGeometryWithoutBreakingLimits() {
        var base = SpellParticleRenderer.plan(Map.of("ICE", 1, "RING", 1),
                "ICE", "RING", 16, 3, 1, 180, 1.0);
        var expanded = SpellParticleRenderer.plan(Map.of("ICE", 1, "RING", 1, "AREA_SCALE", 5),
                "ICE", "RING", 16, 3, 1, 180, 1.0);

        assertTrue(expanded.radius() > base.radius());
        assertTrue(expanded.radius() <= 14.0);
        assertTrue(expanded.particleBudget() <= 180);
    }
}
