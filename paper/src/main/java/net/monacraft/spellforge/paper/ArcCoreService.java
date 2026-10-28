package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.arcsys.compiler.ArcBytecode;
import net.monacraft.spellforge.model.SpellModel.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Server-authoritative ARCSYS_CORE binding, validation and EXEC-flow runtime. */
final class ArcCoreService {
    enum BindingMode { PINNED, LATEST }
    record Operation(boolean ok, String code, String message, SpellDatabase.ArcCoreBinding binding) { }
    record CoreView(SpellDatabase.ArcCoreBinding binding, String circuitName, String spellName, long resolvedRevision,
                    int level, String status) { }
    record FlowResult(boolean success, String code, Map<String, Object> outputs) { }

    private final Plugin plugin;
    private final SpellDatabase database;
    private final ArcRuntimeService runtime;
    private final ManaService mana;
    private final boolean enabled;
    private final boolean productionEffects;
    private final boolean requireOwner;
    private final int perPlayerLimit;
    private final int globalLimit;
    private final double minimumTps;
    private final int[] fuel = new int[6], effects = new int[6], cooldown = new int[6];
    private final Map<UUID, Integer> activeByPlayer = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldownUntil = new ConcurrentHashMap<>();
    private int activeGlobal;

    ArcCoreService(Plugin plugin, SpellDatabase database, ArcRuntimeService runtime, ManaService mana) {
        this.plugin = plugin; this.database = database; this.runtime = runtime; this.mana = mana;
        String root = "arcsys.core.";
        enabled = plugin.getConfig().getBoolean(root + "enabled", false);
        productionEffects = plugin.getConfig().getBoolean(root + "allow-production-effects", false);
        requireOwner = plugin.getConfig().getBoolean(root + "require-owner", true);
        perPlayerLimit = Math.max(1, plugin.getConfig().getInt(root + "max-active-executions-per-player", 2));
        globalLimit = Math.max(1, plugin.getConfig().getInt(root + "max-active-executions-global", 64));
        minimumTps = plugin.getConfig().getDouble(root + "minimum-tps", 17.0);
        int[] defaultFuel = {0, 64, 96, 128, 192, 256};
        int[] defaultEffects = {0, 2, 3, 4, 6, 8};
        int[] defaultCooldown = {0, 80, 70, 60, 50, 40};
        for (int level = 1; level <= 5; level++) {
            fuel[level] = positive(root + "levels." + level + ".fuel", defaultFuel[level], 1, 1_000_000);
            effects[level] = positive(root + "levels." + level + ".max-effects", defaultEffects[level], 0, 1024);
            cooldown[level] = positive(root + "levels." + level + ".cooldown-ticks", defaultCooldown[level], 1, 72_000);
        }
    }

    boolean enabled() { return enabled; }

    Operation bind(Player player, UUID circuitId, UUID nodeId, UUID spellId, Long revision, BindingMode mode) {
        if (!enabled) return fail("DISABLED", "術式演算核は設定で無効です");
        if (!player.hasPermission("monaspellforge.arcsys.core.bind")) return fail("NO_PERMISSION", "紐付け権限がありません");
        Circuit circuit = database.circuit(circuitId).orElse(null);
        if (circuit == null || requireOwner && !circuit.ownerId().equals(player.getUniqueId())) return fail("CIRCUIT_NOT_FOUND", "自分の回路が見つかりません");
        CircuitNode node = circuit.nodes().stream().filter(value -> value.nodeId().equals(nodeId)).findFirst().orElse(null);
        if (node == null || !node.fragmentId().equals("ARCSYS_CORE")) return fail("CORE_NOT_FOUND", "術式演算核ノードが見つかりません");
        SpellDatabase.ArcSpellRevision spell = mode == BindingMode.PINNED
                ? revision == null ? null : database.arcSpell(spellId, player.getUniqueId(), revision).orElse(null)
                : database.arcSpell(spellId, player.getUniqueId()).orElse(null);
        if (spell == null) return fail("SPELL_NOT_FOUND", "所有する検証済み術式が見つかりません");
        long pinned = mode == BindingMode.PINNED ? spell.revision() : 0;
        SpellDatabase.ArcCoreBinding saved = database.saveArcCoreBinding(new SpellDatabase.ArcCoreBinding(
                UUID.randomUUID(), player.getUniqueId(), circuitId, nodeId, node.fragmentInstanceId(), spellId,
                mode == BindingMode.PINNED ? pinned : null, mode.name(), 1, System.currentTimeMillis(), System.currentTimeMillis()));
        return new Operation(true, "OK", "術式演算核へ紐付けました", saved);
    }

    Operation unbind(Player player, UUID circuitId, UUID nodeId) {
        if (!player.hasPermission("monaspellforge.arcsys.core.bind")) return fail("NO_PERMISSION", "解除権限がありません");
        return database.deleteArcCoreBinding(player.getUniqueId(), circuitId, nodeId)
                ? new Operation(true, "OK", "紐付けを解除しました", null)
                : fail("NOT_FOUND", "自分の紐付けが見つかりません");
    }

    List<CoreView> list(UUID ownerId) {
        List<CoreView> views = new ArrayList<>();
        Map<String, SpellDatabase.ArcCoreBinding> bindings = new HashMap<>();
        for (SpellDatabase.ArcCoreBinding binding : database.arcCoreBindings(ownerId))
            bindings.put(binding.circuitId() + ":" + binding.nodeId(), binding);
        UUID zero = new UUID(0, 0);
        for (Circuit circuit : database.circuits(ownerId)) for (CircuitNode node : circuit.nodes()) {
            if (!node.fragmentId().equals("ARCSYS_CORE")) continue;
            SpellDatabase.ArcCoreBinding binding = bindings.get(circuit.circuitId() + ":" + node.nodeId());
            if (binding == null) {
                binding = new SpellDatabase.ArcCoreBinding(zero, ownerId, circuit.circuitId(), node.nodeId(),
                        node.fragmentInstanceId(), zero, 1L, "PINNED", 1, 0, 0);
                views.add(new CoreView(binding, circuit.name(), "<未設定>", 0, node.level(), enabled ? "UNBOUND" : "DISABLED"));
                continue;
            }
            SpellDatabase.ArcSpellRevision spell = resolve(binding);
            String status = !node.fragmentInstanceId().equals(binding.fragmentInstanceId()) ? "INVALID_NODE"
                    : spell == null ? "REVISION_CONFLICT" : enabled ? "READY" : "DISABLED";
            views.add(new CoreView(binding, circuit.name(), spell == null ? "<missing>" : spell.name(),
                    spell == null ? 0 : spell.revision(), node.level(), status));
        }
        return List.copyOf(views);
    }

    Optional<CoreView> inspect(UUID ownerId, UUID circuitId, UUID nodeId) {
        return list(ownerId).stream().filter(view -> view.binding().circuitId().equals(circuitId)
                && view.binding().nodeId().equals(nodeId)).findFirst();
    }

    /**
     * Executes only cores reachable from the selected trigger over EXEC edges. Cores are chained in graph order;
     * a failed core follows no success edge and aborts the aggregate spell effects.
     */
    boolean executeFlow(Player player, Circuit circuit, CompiledSpell compiled, String trigger,
                        Consumer<FlowResult> completion) {
        List<CircuitNode> cores = reachableCores(circuit, compiled, trigger);
        if (cores.isEmpty()) return false;
        if (!enabled) { completion.accept(new FlowResult(false, "DISABLED", Map.of())); return true; }
        executeNext(player, circuit, cores, 0, Map.of(), completion);
        return true;
    }

    private void executeNext(Player player, Circuit circuit, List<CircuitNode> cores, int index,
                             Map<UUID, Map<String, Object>> nodeOutputs, Consumer<FlowResult> completion) {
        if (index >= cores.size()) {
            Map<String, Object> last = cores.isEmpty() ? Map.of() : nodeOutputs.getOrDefault(cores.getLast().nodeId(), Map.of());
            completion.accept(new FlowResult(true, "SUCCESS", last)); return;
        }
        CircuitNode node = cores.get(index);
        SpellDatabase.ArcCoreBinding binding = database.arcCoreBinding(circuit.circuitId(), node.nodeId()).orElse(null);
        if (binding == null || !binding.ownerId().equals(player.getUniqueId())
                || !binding.fragmentInstanceId().equals(node.fragmentInstanceId())) {
            completeFailure(binding, circuit, node, "BINDING_INVALID", completion); return;
        }
        SpellDatabase.ArcSpellRevision revision = resolve(binding);
        if (revision == null) { completeFailure(binding, circuit, node, "REVISION_CONFLICT", completion); return; }
        String key = player.getUniqueId() + ":" + circuit.circuitId() + ":" + node.nodeId();
        long now = System.currentTimeMillis();
        if (cooldownUntil.getOrDefault(key, 0L) > now) { completeFailure(binding, circuit, node, "COOLDOWN", completion); return; }
        if (activeGlobal >= globalLimit || activeByPlayer.getOrDefault(player.getUniqueId(), 0) >= perPlayerLimit) {
            completeFailure(binding, circuit, node, "CONCURRENCY_LIMIT", completion); return;
        }
        double tps = Bukkit.getTPS().length == 0 ? 20.0 : Bukkit.getTPS()[0];
        if (tps < minimumTps) { completeFailure(binding, circuit, node, "TPS_GUARD", completion); return; }
        int level = Math.max(1, Math.min(5, node.level()));
        cooldownUntil.put(key, now + cooldown[level] * 50L);
        activeGlobal++; activeByPlayer.merge(player.getUniqueId(), 1, Integer::sum);
        Map<String, Object> inputs = inputs(player, circuit, node, nodeOutputs);
        ArcRuntimeService.CoreCastOutcome result = runtime.castCore(player, revision, fuel[level], effects[level], inputs,
                productionEffects, (success, code, value) -> {
                    activeGlobal = Math.max(0, activeGlobal - 1);
                    activeByPlayer.compute(player.getUniqueId(), (id, count) -> count == null || count <= 1 ? null : count - 1);
                    database.auditArcCore(player.getUniqueId(), binding.bindingId(), circuit.circuitId(), node.nodeId(),
                            "EXECUTE", success ? "SUCCESS" : code, "revision=" + revision.revision());
                    if (!success) { completion.accept(new FlowResult(false, code, Map.of())); return; }
                    Map<String, Object> outputs = outputs(value);
                    Map<UUID, Map<String, Object>> updated = new LinkedHashMap<>(nodeOutputs);
                    updated.put(node.nodeId(), outputs);
                    executeNext(player, circuit, cores, index + 1, Map.copyOf(updated), completion);
                });
        if (!result.accepted()) {
            activeGlobal = Math.max(0, activeGlobal - 1);
            activeByPlayer.compute(player.getUniqueId(), (id, count) -> count == null || count <= 1 ? null : count - 1);
            completeFailure(binding, circuit, node, result.code(), completion);
        }
    }

    private SpellDatabase.ArcSpellRevision resolve(SpellDatabase.ArcCoreBinding binding) {
        return binding.mode().equals("LATEST")
                ? database.arcSpell(binding.spellId(), binding.ownerId()).orElse(null)
                : database.arcSpell(binding.spellId(), binding.ownerId(), binding.pinnedRevision()).orElse(null);
    }

    private void completeFailure(SpellDatabase.ArcCoreBinding binding, Circuit circuit, CircuitNode node,
                                 String code, Consumer<FlowResult> completion) {
        database.auditArcCore(circuit.ownerId(), binding == null ? null : binding.bindingId(), circuit.circuitId(), node.nodeId(),
                "EXECUTE", code, "");
        completion.accept(new FlowResult(false, code, Map.of()));
    }

    private Map<String, Object> inputs(Player player, Circuit circuit, CircuitNode node,
                                       Map<UUID, Map<String, Object>> nodeOutputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caster", entity(player)); result.put("world", player.getWorld().getUID().toString());
        result.put("trigger_type", "CIRCUIT"); result.put("circuit_id", circuit.circuitId().toString());
        result.put("node_id", node.nodeId().toString()); result.put("execution_id", UUID.randomUUID().toString());
        result.put("mana_available", mana.current(player));
        result.put("number_in", number(node.parameters().get("number_in"), 0.0));
        result.put("boolean_in", Boolean.parseBoolean(node.parameters().getOrDefault("boolean_in", "false")));
        Object entity = null;
        for (CircuitEdge edge : circuit.edges()) {
            if (!edge.targetNodeId().equals(node.nodeId())) continue;
            Object value = nodeOutputs.getOrDefault(edge.sourceNodeId(), Map.of()).get(edge.sourcePortId());
            if (value != null) result.put(edge.targetPortId(), value);
        }
        entity = result.get("entity_in");
        if (entity == null) {
            RayTraceResult trace = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), 64, 0.35,
                    candidate -> candidate instanceof LivingEntity && candidate != player);
            entity = trace != null && trace.getHitEntity() instanceof LivingEntity living ? entity(living) : entity(player);
        }
        result.put("target", entity); result.put("entity_in", entity);
        Location location = player.getLocation();
        result.put("target_location", Map.of("world", location.getWorld().getUID().toString(), "x", location.getX(), "y", location.getY(), "z", location.getZ()));
        result.put("location_in", result.get("target_location"));
        return Map.copyOf(result);
    }

    private static Map<String, Object> outputs(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        while (value instanceof ArcBytecode.Variant variant
                && variant.hasValue() && (variant.tag().equals("Some") || variant.tag().equals("Ok"))) {
            value = variant.value();
        }
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("entity_out", "number_out", "boolean_out"))
                if (map.containsKey(key)) result.put(key, map.get(key));
        }
        if (value instanceof Boolean bool) result.put("boolean_out", bool);
        else if (value instanceof Number number) result.put("number_out", number.doubleValue());
        else if (value instanceof Map<?, ?> map && map.containsKey("uuid")) result.put("entity_out", value);
        return Map.copyOf(result);
    }

    static List<CircuitNode> reachableCores(Circuit circuit, CompiledSpell compiled, String trigger) {
        Map<UUID, CircuitNode> nodes = new HashMap<>(); circuit.nodes().forEach(node -> nodes.put(node.nodeId(), node));
        Set<UUID> reachable = new HashSet<>(); ArrayDeque<UUID> queue = new ArrayDeque<>();
        boolean manual = "MANUAL".equalsIgnoreCase(trigger);
        for (CircuitNode node : circuit.nodes()) {
            FragmentDefinition definition = compiled.resolvedDefinitions().get(node.nodeId());
            if (node.fragmentId().equalsIgnoreCase(trigger) || manual && definition != null && definition.category() == Category.TRIGGER) {
                reachable.add(node.nodeId()); queue.add(node.nodeId());
            }
        }
        while (!queue.isEmpty()) {
            UUID source = queue.removeFirst();
            for (CircuitEdge edge : circuit.edges()) {
                if (!edge.sourceNodeId().equals(source)) continue;
                FragmentDefinition sourceDefinition = compiled.resolvedDefinitions().get(edge.sourceNodeId());
                FragmentDefinition targetDefinition = compiled.resolvedDefinitions().get(edge.targetNodeId());
                PortDefinition sourcePort = sourceDefinition == null ? null : sourceDefinition.port(edge.sourcePortId());
                PortDefinition targetPort = targetDefinition == null ? null : targetDefinition.port(edge.targetPortId());
                if (sourcePort == null || targetPort == null || sourcePort.type() != PortType.EXEC || targetPort.type() != PortType.EXEC) continue;
                if (reachable.add(edge.targetNodeId())) queue.addLast(edge.targetNodeId());
            }
        }
        return compiled.executionOrder().stream().filter(node -> reachable.contains(node.nodeId()) && node.fragmentId().equals("ARCSYS_CORE")).toList();
    }

    private int positive(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(path, fallback)));
    }
    private static double number(String value, double fallback) { try { return Double.parseDouble(value); } catch (Exception ignored) { return fallback; } }
    private static Map<String, Object> entity(Entity entity) { return Map.of("uuid", entity.getUniqueId().toString(), "type", entity.getType().name()); }
    private static Operation fail(String code, String message) { return new Operation(false, code, message, null); }
}
