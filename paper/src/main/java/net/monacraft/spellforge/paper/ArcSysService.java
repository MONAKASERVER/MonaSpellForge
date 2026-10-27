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
    private final Map<UUID, DebugSession> debugSessions = new HashMap<>();

    ArcSysService(SpellDatabase database, FragmentService fragments) { this.database = database; this.fragments = fragments; }

    JsonObject handle(Player player, String type, JsonObject payload) {
        return switch (type) {
            case "listSpells" -> list(player);
            case "openSpell" -> open(player, payload);
            case "validate", "compile" -> compile(payload);
            case "deploy" -> deploy(player, payload);
            case "simulate" -> simulate(player, payload);
            case "debugStart" -> debugStart(player, payload);
            case "debugContinue" -> debugContinue(player, payload);
            case "debugState" -> debugState(player, payload);
            case "debugStop" -> debugStop(player, payload);
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
        ArcVm vm = new ArcVm();
        ArcVm.Result result = vm.run(compiled.program(), entry, Map.of(), ArcVm.Limits.production(), new SimulationHost());
        long virtualTicks = 0;
        while (result.status() == ArcVm.Status.SUSPENDED && result.continuation().suspensionCount() <= ArcVm.MAX_SUSPENSIONS) {
            virtualTicks += result.resumeAfterTicks();
            result = vm.resume(compiled.program(), result.continuation(), ArcVm.Limits.production(), new SimulationHost());
        }
        response.addProperty("status", result.status().name()); response.addProperty("fuelUsed", result.fuelUsed());
        response.addProperty("virtualTicks", virtualTicks);
        response.addProperty("runtimeCode", result.code()); response.addProperty("runtimeMessage", result.message());
        response.add("effects", new com.google.gson.Gson().toJsonTree(result.effects()));
        return response;
    }

    private JsonObject debugStart(Player player, JsonObject payload) {
        requireDebug(player);
        var compiled = compiler.compile(source(payload));
        JsonObject response = diagnostics(compiled);
        if (!compiled.valid()) return response;
        if (debugSessions.size() >= 128 && !debugSessions.containsKey(player.getUniqueId()))
            throw new ArcRequestException("DEBUG_LIMIT", "デバッグセッション上限です");
        String entry = payload.has("entry") ? payload.get("entry").getAsString()
                : compiled.program().entryPoints().keySet().stream().filter(value -> !value.startsWith("task:"))
                .sorted().findFirst().orElseThrow();
        if (!compiled.program().entryPoints().containsKey(entry)) throw new ArcRequestException("ENTRY_NOT_FOUND", "開始地点がありません");
        DebugHost host = new DebugHost(intSet(payload, "breakpoints"), stringSet(payload, "watches"), true);
        ArcVm.Result result = new ArcVm().run(compiled.program(), entry, Map.of(), ArcVm.Limits.production(), host);
        UUID id = UUID.randomUUID();
        if (result.status() == ArcVm.Status.SUSPENDED)
            debugSessions.put(player.getUniqueId(), new DebugSession(id, compiled.program(), result.continuation(), host, System.currentTimeMillis()));
        response.addProperty("debugSessionId", id.toString());
        addDebugResult(response, result, host);
        return response;
    }

    private JsonObject debugContinue(Player player, JsonObject payload) {
        requireDebug(player);
        DebugSession session = session(player, payload);
        String mode = payload.has("mode") ? payload.get("mode").getAsString() : "continue";
        if (!mode.equals("continue") && !mode.equals("step")) throw new ArcRequestException("DEBUG_MODE", "modeはcontinueまたはstepです");
        session.host().arm(mode.equals("step"));
        ArcVm.Result result = new ArcVm().resume(session.program(), session.continuation(), ArcVm.Limits.production(), session.host());
        JsonObject response = new JsonObject();
        if (result.status() == ArcVm.Status.SUSPENDED)
            debugSessions.put(player.getUniqueId(), new DebugSession(session.id(), session.program(), result.continuation(), session.host(), System.currentTimeMillis()));
        else debugSessions.remove(player.getUniqueId());
        response.addProperty("debugSessionId", session.id().toString());
        addDebugResult(response, result, session.host());
        return response;
    }

    private JsonObject debugState(Player player, JsonObject payload) {
        requireDebug(player);
        DebugSession session = session(player, payload);
        JsonObject result = new JsonObject(); result.addProperty("debugSessionId", session.id().toString());
        result.addProperty("status", "PAUSED"); addSnapshot(result, session.host()); return result;
    }

    private JsonObject debugStop(Player player, JsonObject payload) {
        requireDebug(player);
        DebugSession session = session(player, payload); debugSessions.remove(player.getUniqueId());
        JsonObject result = new JsonObject(); result.addProperty("debugSessionId", session.id().toString());
        result.addProperty("status", "STOPPED"); return result;
    }

    private void addDebugResult(JsonObject response, ArcVm.Result result, DebugHost host) {
        String state = result.status() == ArcVm.Status.SUSPENDED && "ARC-DEBUG-BREAKPOINT".equals(result.code()) ? "PAUSED"
                : result.status() == ArcVm.Status.SUSPENDED ? "WAITING" : result.status().name();
        response.addProperty("status", state); response.addProperty("fuelUsed", result.fuelUsed());
        response.addProperty("runtimeCode", result.code()); response.addProperty("runtimeMessage", result.message());
        response.add("effects", new com.google.gson.Gson().toJsonTree(result.effects())); addSnapshot(response, host);
    }

    private void addSnapshot(JsonObject response, DebugHost host) {
        response.addProperty("pc", host.pausedPc); response.addProperty("op", host.pausedOp);
        response.addProperty("stackDepth", host.stackDepth); response.addProperty("fuelRemaining", host.fuelRemaining);
        response.add("watches", new com.google.gson.Gson().toJsonTree(host.watched));
        response.add("trace", new com.google.gson.Gson().toJsonTree(host.trace));
    }

    private DebugSession session(Player player, JsonObject payload) {
        DebugSession session = debugSessions.get(player.getUniqueId());
        if (session == null) throw new ArcRequestException("DEBUG_NOT_FOUND", "デバッグセッションがありません");
        if (payload.has("debugSessionId") && !session.id().toString().equals(payload.get("debugSessionId").getAsString()))
            throw new ArcRequestException("DEBUG_SESSION_MISMATCH", "デバッグセッションが一致しません");
        if (System.currentTimeMillis() - session.touchedAt() > 15 * 60_000L) {
            debugSessions.remove(player.getUniqueId()); throw new ArcRequestException("DEBUG_EXPIRED", "デバッグセッションの期限が切れました");
        }
        return session;
    }

    private static Set<Integer> intSet(JsonObject payload, String name) {
        if (!payload.has(name) || !payload.get(name).isJsonArray()) return Set.of();
        Set<Integer> values = new TreeSet<>(); payload.getAsJsonArray(name).forEach(value -> {
            int pc = value.getAsInt(); if (pc >= 0 && pc < 4096 && values.size() < 128) values.add(pc);
        }); return Set.copyOf(values);
    }
    private static Set<String> stringSet(JsonObject payload, String name) {
        if (!payload.has(name) || !payload.get(name).isJsonArray()) return Set.of();
        Set<String> values = new TreeSet<>(); payload.getAsJsonArray(name).forEach(value -> {
            String watch = value.getAsString(); if (watch.matches("[A-Za-z_$][A-Za-z0-9_$]{0,63}") && values.size() < 64) values.add(watch);
        }); return Set.copyOf(values);
    }
    private static void requireDebug(Player player) {
        if (!player.hasPermission("monaspellforge.arcsys.debug")) throw new ArcRequestException("NO_PERMISSION", "デバッグ権限がありません");
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

    private static class SimulationHost implements ArcVm.Host {
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

    private static final class DebugHost extends SimulationHost {
        private final Set<Integer> breakpoints;
        private final Set<String> watchNames;
        private final List<Map<String, Object>> trace = new ArrayList<>();
        private boolean initialPause;
        private boolean step;
        private int skipPc = -1;
        private int pausedPc = -1;
        private String pausedOp = "";
        private int stackDepth;
        private int fuelRemaining;
        private Map<String, Object> watched = Map.of();

        DebugHost(Set<Integer> breakpoints, Set<String> watchNames, boolean initialPause) {
            this.breakpoints = breakpoints; this.watchNames = watchNames; this.initialPause = initialPause;
        }

        void arm(boolean step) { this.step = step; this.skipPc = pausedPc; }

        @Override public ArcVm.DebugAction beforeInstruction(int pc, net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Instruction instruction,
                                                             Map<String, Object> variables, int stackDepth, int fuelRemaining) {
            if (trace.size() >= 256) trace.removeFirst();
            trace.add(Map.of("pc", pc, "op", instruction.op().name(), "stack", stackDepth, "fuel", fuelRemaining));
            if (pc == skipPc) { skipPc = -1; return ArcVm.DebugAction.CONTINUE; }
            boolean pause = initialPause || step || breakpoints.contains(pc);
            initialPause = false; step = false;
            if (!pause) return ArcVm.DebugAction.CONTINUE;
            this.pausedPc = pc; this.pausedOp = instruction.op().name(); this.stackDepth = stackDepth; this.fuelRemaining = fuelRemaining;
            Map<String, Object> selected = new TreeMap<>();
            for (String name : watchNames) if (variables.containsKey(name)) selected.put(name, variables.get(name));
            this.watched = Map.copyOf(selected);
            return ArcVm.DebugAction.PAUSE;
        }
    }

    private record DebugSession(UUID id, net.monacraft.spellforge.arcsys.compiler.ArcBytecode.Program program,
                                ArcVm.Continuation continuation, DebugHost host, long touchedAt) { }
}
