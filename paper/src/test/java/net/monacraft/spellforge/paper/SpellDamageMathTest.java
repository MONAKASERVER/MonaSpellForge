package net.monacraft.spellforge.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpellDamageMathTest {
    @Test void armorMultiplierMatchesVanillaBounds() {
        assertEquals(1.0, DefaultSpellService.armorMultiplier(20, 0, 0), 0.0001);
        assertTrue(DefaultSpellService.armorMultiplier(20, 20, 8) < 1.0);
        assertTrue(DefaultSpellService.armorMultiplier(20, 20, 8) >= 0.2);
    }

    @Test void reducingArmorAlwaysIncreasesDeliveredDamage() {
        double fullArmor = DefaultSpellService.armorMultiplier(30, 20, 8);
        double penetratedArmor = DefaultSpellService.armorMultiplier(30, 9, 8);
        assertTrue(penetratedArmor > fullArmor);
    }
}
