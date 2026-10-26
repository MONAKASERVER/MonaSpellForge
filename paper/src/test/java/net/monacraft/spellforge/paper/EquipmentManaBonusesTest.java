package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EquipmentManaBonusesTest {
    @Test void parsesOnlyBlueMpAndMpsLore() {
        var mp = EquipmentManaBonuses.parse(Component.text("MP: 250", NamedTextColor.BLUE));
        var mps = EquipmentManaBonuses.parse(Component.text("MPS: 7.5", NamedTextColor.BLUE));
        assertNotNull(mp);
        assertEquals("MP", mp.key());
        assertEquals(250.0, mp.value());
        assertNotNull(mps);
        assertEquals("MPS", mps.key());
        assertEquals(7.5, mps.value());
        assertNull(EquipmentManaBonuses.parse(Component.text("MP: 999", NamedTextColor.RED)));
        assertNull(EquipmentManaBonuses.parseLegacy("&9MP: -1"));
        assertNull(EquipmentManaBonuses.parseLegacy("&9MPS: NaN"));
    }

    @Test void acceptsLegacyBluePrefixAndFormattingInValue() {
        var parsed = EquipmentManaBonuses.parseLegacy("&9MP: &f1,000");
        assertNull(parsed, "Thousands separators are intentionally rejected to keep lore unambiguous");
        parsed = EquipmentManaBonuses.parseLegacy("&9MP: &f1000");
        assertNotNull(parsed);
        assertEquals(1000.0, parsed.value());
    }
}
