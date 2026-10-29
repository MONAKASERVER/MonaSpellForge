package net.monacraft.spellforge.fabric;

import net.monacraft.spellforge.compiler.ArcCircuitCodec;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcFileTransferTest {
    @TempDir Path directory;

    @Test
    void exportsUtf8ArcAndKeepsExistingFiles() throws Exception {
        Circuit circuit = new Circuit(UUID.randomUUID(), UUID.randomUUID(), "炎/回路:*?", 0, 1,
                List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);

        Path first = ArcFileTransfer.exportToDirectory(circuit, directory);
        Path second = ArcFileTransfer.exportToDirectory(circuit, directory);

        assertEquals("炎_回路___.arc", first.getFileName().toString());
        assertTrue(second.getFileName().toString().endsWith("-2.arc"));
        assertEquals(ArcCircuitCodec.toArcSource(circuit), Files.readString(first));
    }

    @Test
    void rejectsWrongExtensionAndOversizedInput() throws Exception {
        Path text = Files.writeString(directory.resolve("spell.txt"), "not arc");
        assertThrows(Exception.class, () -> ArcFileTransfer.readArc(text));

        Path huge = directory.resolve("huge.arc");
        Files.write(huge, new byte[1_048_577]);
        assertThrows(Exception.class, () -> ArcFileTransfer.readArc(huge));
    }
}
