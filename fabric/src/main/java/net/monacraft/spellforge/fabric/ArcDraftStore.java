package net.monacraft.spellforge.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Crash-safe local drafts for the in-game ArcSys editor. */
final class ArcDraftStore {
    private static final int MAX_SOURCE_BYTES = 96_000;

    private ArcDraftStore() { }

    static Optional<String> load(Path gameDirectory, UUID circuitId, UUID nodeId) throws IOException {
        Path path = path(gameDirectory, circuitId, nodeId);
        if (!Files.isRegularFile(path)) return Optional.empty();
        long size = Files.size(path);
        if (size <= 0 || size > MAX_SOURCE_BYTES) throw new IOException("draft size");
        return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    }

    static void save(Path gameDirectory, UUID circuitId, UUID nodeId, String source) throws IOException {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) throw new IOException("draft size");
        Path target = path(gameDirectory, circuitId, nodeId);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void delete(Path gameDirectory, UUID circuitId, UUID nodeId) throws IOException {
        Files.deleteIfExists(path(gameDirectory, circuitId, nodeId));
    }

    static Path path(Path gameDirectory, UUID circuitId, UUID nodeId) {
        return gameDirectory.resolve("config").resolve("monaspellforge").resolve("drafts")
                .resolve(circuitId + "_" + nodeId + ".arc");
    }
}
