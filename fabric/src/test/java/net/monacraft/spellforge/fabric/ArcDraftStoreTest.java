package net.monacraft.spellforge.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArcDraftStoreTest {
    @TempDir Path directory;

    @Test
    void savesRestoresAndDeletesDraft() throws Exception {
        UUID circuit = UUID.randomUUID(), node = UUID.randomUUID();
        assertTrue(ArcDraftStore.load(directory, circuit, node).isEmpty());
        ArcDraftStore.save(directory, circuit, node, "spell Draft { on cast { return true; } }");
        assertEquals("spell Draft { on cast { return true; } }", ArcDraftStore.load(directory, circuit, node).orElseThrow());
        ArcDraftStore.delete(directory, circuit, node);
        assertTrue(ArcDraftStore.load(directory, circuit, node).isEmpty());
    }

    @Test
    void rejectsEmptyDraft() {
        assertThrows(Exception.class, () -> ArcDraftStore.save(directory, UUID.randomUUID(), UUID.randomUUID(), ""));
    }
}
