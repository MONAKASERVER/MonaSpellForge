package net.monacraft.spellforge.catalog;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FragmentPresentationTest {
    @Test
    void coversEveryCatalogFragmentWithAColoredUniqueName() {
        FragmentCatalog catalog = FragmentCatalog.defaults();
        assertEquals(69, FragmentPresentation.all().size());
        assertEquals(catalog.ids(), FragmentPresentation.all().keySet());

        Set<String> names = FragmentPresentation.all().values().stream()
                .map(FragmentPresentation.Entry::plainName)
                .collect(Collectors.toSet());
        assertEquals(69, names.size());
        FragmentPresentation.all().values().forEach(entry -> {
            assertTrue(entry.legacyName().matches(".*&[0-9a-f].*"), entry.id());
            assertFalse(entry.description().isBlank(), entry.id());
            assertFalse(entry.keywords().isBlank(), entry.id());
        });
    }

    @Test
    void searchableTextContainsNameCategoryAndFunction() {
        var definition = FragmentCatalog.defaults().get("FIRE");
        String text = FragmentPresentation.get("FIRE").searchText(definition.category());
        assertTrue(text.contains("煉獄焔核"));
        assertTrue(text.contains("属性核"));
        assertTrue(text.contains("燃焼"));
    }
}
