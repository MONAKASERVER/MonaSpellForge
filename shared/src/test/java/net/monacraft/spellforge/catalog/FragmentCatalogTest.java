package net.monacraft.spellforge.catalog;

import net.monacraft.spellforge.model.SpellModel.Category;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FragmentCatalogTest {
    @Test void catalogContainsAllUniqueDefinitions() {
        FragmentCatalog catalog = FragmentCatalog.defaults();
        assertEquals(72, catalog.all().size());
        assertEquals(6, catalog.all().stream().filter(d -> d.category() == Category.TRIGGER).count());
        assertEquals(12, catalog.all().stream().filter(d -> d.category() == Category.ELEMENT).count());
        assertEquals(8, catalog.all().stream().filter(d -> d.category() == Category.SHAPE).count());
        assertEquals(5, catalog.all().stream().filter(d -> d.category() == Category.TARGET).count());
        assertEquals(15, catalog.all().stream().filter(d -> d.category() == Category.MODIFIER).count());
        assertEquals(26, catalog.all().stream().filter(d -> d.category() == Category.LOGIC).count());
        catalog.all().forEach(definition -> {
            assertEquals(definition.maxLevel(), definition.levels().size());
            assertNotNull(definition.port(definition.ports().getFirst().id()));
        });
        assertEquals(4, catalog.get("CIRCUIT_SPEED").maxLevel());
        assertEquals(2.0, catalog.get("CIRCUIT_SPEED").levels().get(1).power());
        assertEquals(8.0, catalog.get("CIRCUIT_SPEED").levels().get(4).power());
        assertEquals(0.55, catalog.get("ARMOR_PENETRATION").levels().get(5).power(), 0.0001);
        assertEquals(300.0, catalog.get("TRUE_DAMAGE").levels().get(5).power(), 0.0001);
        assertTrue(catalog.get("TRUE_DAMAGE").levels().get(5).runtimeCost() >= 200);
    }
}
