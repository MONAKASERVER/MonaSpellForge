package net.monacraft.spellforge.arcsys.language;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Build-time exporter for the VS Code extension. Never used by the ArcVM. */
public final class ArcMagicApiExporter {
    private ArcMagicApiExporter() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("output path required");
        StringBuilder output = new StringBuilder("""
                // GENERATED from ArcMagicApiCatalog. Do not edit by hand.
                export interface MagicApiParameter { name: string; type: string; optional: boolean; description: string }
                export interface MagicApi { detail: string; mana: number; capability: string; signature: string; effectful: boolean; parameters: MagicApiParameter[] }
                export const magicApis: Record<string, MagicApi> = {
                """);
        for (var entry : ArcMagicApiCatalog.all().entrySet()) {
            var api = entry.getValue();
            output.append("  '").append(js(entry.getKey())).append("': { detail: '").append(js(api.description()))
                    .append("', mana: ").append(api.baseMana()).append(", capability: '").append(js(api.capability()))
                    .append("', signature: '").append(js(api.signature())).append("', effectful: ").append(api.effectful())
                    .append(", parameters: [");
            for (int i = 0; i < api.parameters().size(); i++) {
                var parameter = api.parameters().get(i);
                if (i > 0) output.append(", ");
                output.append("{ name: '").append(js(parameter.name())).append("', type: '")
                        .append(js(parameter.type().displayName())).append("', optional: ").append(parameter.optional())
                        .append(", description: '").append(js(parameter.description())).append("' }");
            }
            output.append("] },\n");
        }
        output.append("};\n");
        Path path = Path.of(args[0]); Files.createDirectories(path.getParent());
        Files.writeString(path, output.toString(), StandardCharsets.UTF_8);
    }

    private static String js(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n");
    }
}
