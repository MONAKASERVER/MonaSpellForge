package net.monacraft.spellforge.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;
import net.monacraft.spellforge.arcsys.vm.ArcVm;
import org.bukkit.entity.Player;
import net.monacraft.spellforge.api.FragmentService;

import java.time.Instant;
import java.util.*;

/** Paper-authoritative ArcSys validation, revision persistence and safe dry-run service. */
final class ArcSysService {
    private final SpellDatabase database;
    private final FragmentService fragments;
    private final ArcCompiler compiler = new ArcCompiler();

    ArcSysService(SpellDatabase database, FragmentService fragments) { this.database = database; this.fragments = fragments; }

    JsonObject handle(Player player, String type, JsonObject payload) {
        return switch (type) {
            case "listSpells" -> list(player);
            case "openSpell" -> open(player, payload);
            case "validate", "compile" -> compile(payload);
            case "deploy" -> deploy(player, payload);
            case "simulate", "debugStart" -> simulate(player, payload);
            case "nodeGraph" -> graph(payload);
            case "logs" -> logs(player);
            case "studioSnapshot" -> studio(player);
            default -> throw new ArcRequestException("UNKNOWN_REQUEST", "未知のArcSys要求です");
        };
    }

    private JsonObject compile(JsonObject payload) {
        String source = source(payload);
        var result = compiler.compile(source);
        JsonObject response = diagnostics(result);
        if (result.valid()) {
            response.addProperty("spell", result.program().spellName());
            response.addProperty("instructions", result.program().instructions().size());
            response.addProperty("fuel", result.program().estimatedFuel());
            response.addProperty("hash", result.program().sha256());
            response.add("capabilities", strings(result.program().capabilities()));
            response.add("requirements", strings(result.program().requirements()));
        }
        return response;
    }

    private JsonObject deploy(Player player, JsonObject payload) {
        if (!player.hasPermission("monaspellforge.arcsys.deploy")) throw new ArcRequestException("NO_PERMISSION", "デプロイ権限がありません");
        String source = source(payload);
        var result = compiler.compile(source);
        if (!result.valid()) return diagnostics(result);
        verifyRequirements(player, result.program().requirements());
        if (result.program().capabilities().stream().anyMatch(capability -> capability.startsWith("operator."))
                && !player.hasPermission("monaspellforge.arcsys.operator"))
            throw new ArcRequestException("OPERATOR_CAPABILITY", "運営専用Capabilityを使用しています");
        UUID spellId = payload.has("spellId") && !payload.get("spellId").isJsonNull() ? UUID.fromString(payload.get("spellId").getAsString()) : null;
        long baseRevision = payload.has("baseRevision") ? payload.get("baseRevision").getAsLong() : 0;
        try {
            var saved = database.deployArcSpell(player.getUniqueId(), spellId, baseRevision, result.program().spellName(),
                    source, result.program().sha256(), result.program().encode(), String.join(",", result.program().capabilities()),
                    String.join(",", result.program().requirements()));
            JsonObject response = diagnostics(result);
            response.addProperty("spellId", saved.spellId().toString()); response.addProperty("revision", saved.revision());
            response.addProperty("activatedFromNextCast", true);
            return response;
        } catch (SpellDatabase.OptimisticConflict conflict) {
            throw new ArcRequestException("REVISION_CONFLICT", "サーバー上の最新版は revision " + conflict.revision + " です");
        }
    }

    private JsonObject simulate(Player player, JsonObject payload) {
        if (!player.hasPermission("monaspellforge.arcsys.debug")) throw new ArcRequestException("NO_PERMISSION", "デバッグ権限がありません");
        var compiled = compiler.compile(source(payload));
        JsonObject response = diagnostics(compiled);
        if (!compiled.valid()) return response;
        String entry = compiled.program().entryPoints().keySet().stream().sorted().findFirst().orElseThrow();
        ArcVm.Result result = new ArcVm().run(compiled.program(), entry, Map.of(), ArcVm.Limits.production(), new SimulationHost());
        response.addProperty("status", result.status().name()); response.addProperty("fuelUsed", result.fuelUsed());
        response.addProperty("runtimeCode", result.code()); response.addProperty("runtimeMessage", result.message());
        response.add("effects", new com.google.gson.Gson().toJsonTree(result.effects()));
        return response;
    }

    private JsonObject list(Player player) {
        JsonArray spells = new JsonArray();
        for (var spell : database.arcSpells(player.getUniqueId())) {
            JsonObject item = new JsonObject(); item.addProperty("id", spell.spellId().toString()); item.addProperty("name", spell.name());
            item.addProperty("revision", spell.revision()); item.addProperty("active", spell.enabled()); spells.add(item);
        }
        JsonObject result = new JsonObject(); result.add("spells", spells); return result;
    }

    private JsonObject open(Player player, JsonObject payload) {
        UUID id = UUID.fromString(payload.get("spellId").getAsString());
        var spell = database.arcSpell(id, player.getUniqueId()).orElseThrow(() -> new ArcRequestException("NOT_FOUND", "術式が見つかりません"));
        JsonObject result = new JsonObject(); result.addProperty("spellId", id.toString()); result.addProperty("name", spell.name());
        result.addProperty("source", spell.source()); result.addProperty("revision", spell.revision()); result.addProperty("hash", spell.sourceHash()); return result;
    }

    private JsonObject graph(JsonObject payload) {
        var compiled = compiler.compile(source(payload)); JsonObject result = diagnostics(compiled);
        if (!compiled.valid()) return result;
        JsonArray nodes = new JsonArray(); int index = 0;
        for (var instruction : compiled.program().instructions()) {
            JsonObject node = new JsonObject(); node.addProperty("id", index++); node.addProperty("op", instruction.op().name()); node.addProperty("label", instruction.text()); nodes.add(node);
        }
        result.add("nodes", nodes); return result;
    }

    private JsonObject logs(Player player) {
        JsonArray history = new JsonArray();
        for (var audit : database.arcDeployHistory(player.getUniqueId(), 50)) {
            JsonObject item = new JsonObject(); item.addProperty("spellId", audit.spellId() == null ? "" : audit.spellId().toString());
            item.addProperty("revision", audit.requestedRevision()); item.addProperty("status", audit.resultCode());
            item.addProperty("hash", audit.sourceHash()); item.addProperty("at", Instant.ofEpochMilli(audit.createdAt()).toString()); history.add(item);
        }
        JsonObject result = new JsonObject(); result.add("history", history); return result;
    }

    private JsonObject studio(Player player) {
        JsonObject result = list(player); result.addProperty("connectedServer", player.getServer().getName()); result.addProperty("player", player.getName());
        Map<String, Integer> owned = new TreeMap<>();
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
            var validation = fragments.validateItem(item, player);
            if (validation.valid()) owned.merge(validation.instance().fragmentId(), 1, Integer::sum);
        }
        for (var stored : database.storedFragments(player.getUniqueId())) owned.merge(stored.fragmentId(), 1, Integer::sum);
        JsonArray fragmentList = new JsonArray();
        owned.forEach((id, count) -> { JsonObject item = new JsonObject(); item.addProperty("id", id); item.addProperty("count", count); fragmentList.add(item); });
        result.add("fragments", fragmentList);
        JsonArray apis = new JsonArray();
        ArcCompiler.availableApis().forEach((name, def) -> { JsonObject item = new JsonObject(); item.addProperty("name", name); item.addProperty("mana", def.fuel()); item.addProperty("capability", def.capability()); apis.add(item); });
        result.add("apis", apis); result.add("deployHistory", logs(player).get("history")); return result;
    }

    private JsonObject diagnostics(ArcCompiler.CompileResult compiled) {
        JsonObject response = new JsonObject(); response.addProperty("valid", compiled.valid());
        JsonArray diagnostics = new JsonArray(); compiled.diagnostics().forEach(d -> { JsonObject item = new JsonObject(); item.addProperty("code", d.code()); item.addProperty("severity", d.severity().name()); item.addProperty("message", d.message()); item.addProperty("line", d.span().start().line()); item.addProperty("column", d.span().start().column()); item.addProperty("suggestion", d.suggestion()); diagnostics.add(item); });
        response.add("diagnostics", diagnostics); return response;
    }

    private String source(JsonObject payload) {
        if (!payload.has("source")) throw new ArcRequestException("SOURCE_REQUIRED", "ArcSysソースがありません");
        String source = payload.get("source").getAsString();
        if (source.isBlank() || source.length() > 23_000) throw new ArcRequestException("SOURCE_SIZE", "ArcSysソースのサイズが不正です");
        return source;
    }

    private void verifyRequirements(Player player, Set<String> requirements) {
        for (String requirement : requirements) {
            if (requirement.startsWith("permission:")) {
                String permission = requirement.substring("permission:".length());
                if (!player.hasPermission(permission))
                    throw new ArcRequestException("MISSING_PERMISSION", "必要権限を所持していません: " + permission);
                continue;
            }
            if (!requirement.startsWith("fragment:")) continue;
            String[] parts = requirement.split(":", 3);
            String fragmentId = parts[1]; int minimumLevel = Integer.parseInt(parts[2]);
            boolean found = false;
            for (org.bukkit.inventory.ItemStack item : player.getInventory().getContents()) {
                var validation = fragments.validateItem(item, player);
                if (validation.valid() && validation.instance().fragmentId().equals(fragmentId)
                        && validation.instance().level() >= minimumLevel) { found = true; break; }
            }
            if (!found) found = database.storedFragments(player.getUniqueId()).stream()
                    .anyMatch(stored -> stored.fragmentId().equals(fragmentId) && stored.level() >= minimumLevel);
            if (!found) throw new ArcRequestException("MISSING_FRAGMENT",
                    "必要な欠片を所持していません: " + fragmentId + " Lv." + minimumLevel);
        }
    }
    private static JsonArray strings(Collection<String> values) { JsonArray result = new JsonArray(); values.forEach(result::add); return result; }

    static final class ArcRequestException extends RuntimeException {
        final String code;
        ArcRequestException(String code, String message) { super(message); this.code = code; }
    }

    private static final class SimulationHost implements ArcVm.Host {
        public Object invoke(String api, List<Object> arguments, List<String> names, ArcVm.Execution execution) {
            return switch (api) {
                case "mobs.hostile", "entities.hostile" -> List.of(Map.of("id", "simulation-target", "hp_ratio", 0.5));
                case "target.nearest" -> Map.of("id", "simulation-target", "hp_ratio", 0.5);
                case "combat.can_attack" -> true;
                case "entity.distance", "mana.current" -> 1.0;
                case "raycast.entities" -> List.of();
                case "raycast.blocks", "blocks.query", "items.inspect", "pdc.get" -> null;
                case "spell.cast", "spell.effect", "world.particle", "particles.spiral", "particles.ring", "particles.sphere",
                     "display.magic_circle", "display.item", "display.text", "virtual_structure.show", "combat.damage", "combat.apply",
                     "mana.consume", "pdc.set", "time.after", "event.cancel", "skript.emit" -> {
                    execution.request(new ArcVm.EffectRequest(api, Map.of("arguments", arguments))); yield true;
                }
                case "math.clamp" -> Math.max(((Number) arguments.get(1)).doubleValue(), Math.min(((Number) arguments.get(2)).doubleValue(), ((Number) arguments.get(0)).doubleValue()));
                case "random.chance" -> false;
                case "time.wait", "event.emit", "state.set" -> {
                    execution.request(new ArcVm.EffectRequest(api, Map.of("arguments", arguments))); yield true;
                }
                case "state.get" -> arguments.getFirst();
                default -> null;
            };
        }
        public Object member(Object receiver, String name) { return receiver instanceof Map<?, ?> map ? map.get(name) : null; }
    }
}
