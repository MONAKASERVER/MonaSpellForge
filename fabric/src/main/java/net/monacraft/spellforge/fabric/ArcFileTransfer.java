package net.monacraft.spellforge.fabric;

import net.monacraft.spellforge.compiler.ArcCircuitCodec;
import net.monacraft.spellforge.model.SpellModel.Circuit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Safe local .arc import/export support for the Fabric editor. */
final class ArcFileTransfer {
    private static final int MAX_IMPORT_BYTES = 1_048_576;

    private ArcFileTransfer() { }

    static Path exportToDownloads(Circuit circuit) throws IOException {
        return exportToDirectory(circuit, downloadsDirectory());
    }

    static Path exportToDirectory(Circuit circuit, Path directory) throws IOException {
        Files.createDirectories(directory);
        String baseName = safeBaseName(circuit.name());
        Path destination = directory.resolve(baseName + ".arc");
        for (int suffix = 2; Files.exists(destination); suffix++) {
            destination = directory.resolve(baseName + "-" + suffix + ".arc");
        }
        return Files.writeString(destination, ArcCircuitCodec.toArcSource(circuit), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static String readArc(Path source) throws IOException {
        if (source == null || !source.getFileName().toString().toLowerCase().endsWith(".arc")) {
            throw new IOException(".arcファイルを選択してください");
        }
        long size = Files.size(source);
        if (size <= 0 || size > MAX_IMPORT_BYTES) {
            throw new IOException("ファイルサイズが不正です（最大1 MiB）");
        }
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    static Path downloadsDirectory() {
        String profile = System.getenv("USERPROFILE");
        Path home = Path.of(profile == null || profile.isBlank() ? System.getProperty("user.home") : profile);
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) ? downloads : home;
    }

    static String safeBaseName(String name) {
        String sanitized = name == null ? "" : name
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "")
                .strip();
        if (sanitized.isBlank()) sanitized = "MonaSpellForge回路";
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80).stripTrailing();
    }
}
