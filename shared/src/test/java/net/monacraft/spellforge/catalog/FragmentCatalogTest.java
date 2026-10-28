package net.monacraft.spellforge.catalog;

import net.monacraft.spellforge.model.SpellModel.Category;
import net.monacraft.spellforge.model.SpellModel.LevelParameters;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FragmentCatalogTest {
    @Test void catalogContainsAllUniqueDefinitions() {
        FragmentCatalog catalog = FragmentCatalog.defaults();
        assertEquals(73, catalog.all().size());
        assertEquals(5, catalog.get("ARCSYS_CORE").maxLevel());
        assertEquals("LEGENDARY", catalog.get("ARCSYS_CORE").rarity());
        assertEquals(6, catalog.all().stream().filter(d -> d.category() == Category.TRIGGER).count());
        assertEquals(12, catalog.all().stream().filter(d -> d.category() == Category.ELEMENT).count());
        assertEquals(8, catalog.all().stream().filter(d -> d.category() == Category.SHAPE).count());
        assertEquals(5, catalog.all().stream().filter(d -> d.category() == Category.TARGET).count());
        assertEquals(15, catalog.all().stream().filter(d -> d.category() == Category.MODIFIER).count());
        assertEquals(27, catalog.all().stream().filter(d -> d.category() == Category.LOGIC).count());
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

    @Test void arcSysCoreBalanceCanBeConfiguredWithoutChangingAnyFragmentId() {
        FragmentCatalog base = FragmentCatalog.defaults();
        Map<Integer, LevelParameters> levels = new LinkedHashMap<>();
        for (int level = 1; level <= 5; level++) levels.put(level,
                new LevelParameters(10 * level, level, 100 - level, 20 * level, 5 * level));
        FragmentCatalog configured = base.withArcSysCoreLevels(levels);
        assertEquals(base.ids(), configured.ids());
        assertEquals(73, configured.all().size());
        assertEquals(100.0, configured.get("ARCSYS_CORE").levels().get(5).manaCost());
        assertEquals(25, configured.get("ARCSYS_CORE").levels().get(5).runtimeCost());
    }
}
