package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.arcsys.compiler.ArcBytecode;
import net.monacraft.spellforge.arcsys.compiler.ArcCompiler;
import net.monacraft.spellforge.arcsys.vm.ArcScheduler;
import net.monacraft.spellforge.arcsys.vm.ArcVm;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.*;

/** Paper-authoritative ArcVM activation and effect application on the main server thread. */
final class ArcRuntimeService implements Listener, AutoCloseable, ArcSkriptBridge {
    private final Plugin plugin;
    private final SpellDatabase database;
    private final FragmentService fragments;
    private final ManaService mana;
    private final ArcCompiler compiler = new ArcCompiler();
    private final ArcScheduler scheduler;
    private final ArcEffectContract effectContract;
    private final ArcProtectionAdapters protections = new ArcProtectionAdapters();
    private final Map<ArcScheduler.TaskId, UUID> owners = new HashMap<>();
    private final Map<StateKey, Object> sessionState = new HashMap<>();
    private final double maxDamage;
    private final double maxRange;
    private final int maxPotionTicks;
    private final int maxDisplayTicks;
    private final Set<String> skriptSignals;
    private long tick;
    private final Map<UUID, Integer> inboundSignalsThisTick = new HashMap<>();

    ArcRuntimeService(Plugin plugin, SpellDatabase database, FragmentService fragments, ManaService mana) {
        this.plugin = plugin; this.database = database; this.fragments = fragments; this.mana = mana;
        this.scheduler = new ArcScheduler(new ArcVm(),
                plugin.getConfig().getInt("arc-runtime.max-active-tasks", 1024),
                plugin.getConfig().getInt("arc-runtime.max-resumes-per-tick", 128));
        this.effectContract = new ArcEffectContract(plugin.getConfig());
        this.maxDamage = plugin.getConfig().getDouble("arc-runtime.max-damage-per-effect", 200.0);
        this.maxRange = plugin.getConfig().getDouble("arc-runtime.max-target-range", 64.0);
        this.maxPotionTicks = plugin.getConfig().getInt("arc-runtime.max-potion-duration-ticks", 600);
        this.maxDisplayTicks = plugin.getConfig().getInt("arc-runtime.max-display-duration-ticks", 200);
        this.skriptSignals = plugin.getConfig().getStringList("arc-runtime.skript-allowlist").stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    CastOutcome cast(Player player, UUID spellId) {
        if (!player.hasPermission("monaspellforge.arcsys.cast")) return new CastOutcome(false, "NO_PERMISSION", "ArcSys発動権限がありません", null);
        var revision = database.arcSpell(spellId, player.getUniqueId()).orElse(null);
        if (revision == null) return new CastOutcome(false, "NOT_FOUND", "ArcSys術式が見つかりません", null);
        var compiled = compiler.compile(revision.source());
        if (!compiled.valid()) return new CastOutcome(false, "COMPILE_ERROR", "保存済み術式を再コンパイルできません", null);
        String requirementFailure = requirementFailure(player, compiled.program().requirements());
        if (requirementFailure != null) return new CastOutcome(false, "REQUIREMENT", requirementFailure, null);
        if (!compiled.program().entryPoints().containsKey("on:cast")) return new CastOutcome(false, "NO_CAST_HANDLER", "on castがありません", null);

        RuntimeHost host = new RuntimeHost(player, spellId, compiled.program().capabilities());
        RuntimeListener listener = new RuntimeListener(player, host);
        try {
            ArcScheduler.TaskId id = scheduler.start(compiled.program(), "on:cast",
                    Map.of("caster", entityRef(player)), ArcVm.Limits.production(), host, tick, listener);
            owners.put(id, player.getUniqueId());
            if (!scheduler.isActive(id)) owners.remove(id);
            return new CastOutcome(true, scheduler.isActive(id) ? "SCHEDULED" : "COMPLETED",
                    scheduler.isActive(id) ? "ArcSys術式を起動しました" : "ArcSys術式を発動しました", id);
        } catch (RuntimeException error) {
            return new CastOutcome(false, "RUNTIME_START", "ArcSys術式を開始できません", null);
        }
    }

    void tick() { tick++; inboundSignalsThisTick.clear(); scheduler.tick(tick); }
    int activeTasks() { return scheduler.activeTasks(); }

    int cancel(UUID owner, String reason) {
        return scheduler.cancelWhere(id -> owner.equals(owners.get(id)), reason);
    }

    @Override public boolean isAllowed(String signal) {
        return signal != null && skriptSignals.contains(signal.toLowerCase(Locale.ROOT));
    }

    @Override public int emitSignal(Player player, String signal, Object payload) {
        if (!Bukkit.isPrimaryThread() || player == null || !player.isOnline() || !isAllowed(signal)) return 0;
        int used = inboundSignalsThisTick.merge(player.getUniqueId(), 1, Integer::sum);
        if (used > 4) return 0;
        Object safePayload = boundedBridgeValue(payload);
        int started = 0;
        for (var summary : database.arcSpells(player.getUniqueId())) {
            if (!summary.enabled() || started >= 8) continue;
            var revision = database.arcSpell(summary.spellId(), player.getUniqueId()).orElse(null);
            if (revision == null) continue;
            var compiled = compiler.compile(revision.source());
            if (!compiled.valid() || !compiled.program().entryPoints().containsKey("on:signal")) continue;
            if (requirementFailure(player, compiled.program().requirements()) != null) continue;
            RuntimeHost host = new RuntimeHost(player, revision.spellId(), compiled.program().capabilities());
            RuntimeListener listener = new RuntimeListener(player, host);
            try {
                ArcScheduler.TaskId id = scheduler.start(compiled.program(), "on:signal", Map.of(
                        "caster", entityRef(player), "signal", signal.toLowerCase(Locale.ROOT), "payload", safePayload),
                        ArcVm.Limits.production(), host, tick, listener);
                owners.put(id, player.getUniqueId()); if (!scheduler.isActive(id)) owners.remove(id); started++;
            } catch (RuntimeException error) {
                plugin.getLogger().warning("ArcSys inbound signal rejected for " + player.getName());
            }
        }
        return started;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId(), "logout");
        sessionState.keySet().removeIf(key -> key.ownerId().equals(event.getPlayer().getUniqueId()));
    }

    @Override public void close() {
        scheduler.cancelWhere(id -> true, "plugin-disable"); owners.clear(); sessionState.clear(); inboundSignalsThisTick.clear();
    }

    private String requirementFailure(Player player, Set<String> requirements) {
        for (String requirement : requirements) {
            if (requirement.startsWith("permission:") && !player.hasPermission(requirement.substring(11)))
                return "必要権限を所持していません";
            if (!requirement.startsWith("fragment:")) continue;
            String[] parts = requirement.split(":", 3); int level = Integer.parseInt(parts[2]);
            boolean found = Arrays.stream(player.getInventory().getContents()).filter(Objects::nonNull).anyMatch(item -> {
                var validation = fragments.validateItem(item, player);
                return validation.valid() && validation.instance().fragmentId().equals(parts[1]) && validation.instance().level() >= level;
            });
            if (!found) found = database.storedFragments(player.getUniqueId()).stream()
                    .anyMatch(stored -> stored.fragmentId().equals(parts[1]) && stored.level() >= level);
            if (!found) return "必要な欠片を所持していません: " + parts[1];
        }
        return null;
    }

    private final class RuntimeListener implements ArcScheduler.Listener {
        private final Player player; private final RuntimeHost host; private int applied;
        private RuntimeListener(Player player, RuntimeHost host) { this.player = player; this.host = host; }
        public void suspended(ArcScheduler.TaskId id, ArcVm.Result result) { applyNew(result); }
        public void completed(ArcScheduler.TaskId id, ArcVm.Result result) {
            applyNew(result); owners.remove(id);
        }
        public void trapped(ArcScheduler.TaskId id, ArcVm.Result result) {
            owners.remove(id);
            if (player.isOnline()) player.sendMessage(Component.text("ArcSys停止: " + result.code(), NamedTextColor.RED));
        }
        public void cancelled(ArcScheduler.TaskId id, String reason) { owners.remove(id); }
        private void applyNew(ArcVm.Result result) {
            List<ArcVm.EffectRequest> effects = result.effects();
            List<ArcVm.EffectRequest> segment = effects.subList(applied, effects.size());
            ArcEffectContract.Decision decision = effectContract.validate(segment, host.capabilities);
            if (!decision.allowed()) {
                host.revoked = true;
                if (player.isOnline()) player.sendMessage(Component.text("ArcSys契約違反: " + decision.code(), NamedTextColor.RED));
                applied = effects.size();
                return;
            }
            for (int i = applied; i < effects.size(); i++) {
                try { applyEffect(player, host, effects.get(i)); }
                catch (RuntimeException error) { plugin.getLogger().warning("ArcSys effect rejected: " + effects.get(i).type()); }
            }
            applied = effects.size();
        }
    }

    private final class RuntimeHost implements ArcVm.Host {
        private final Player player; private final UUID spellId;
        private final Set<String> capabilities;
        private final SplittableRandom random;
        private final Map<StateKey, Object> pendingState = new HashMap<>();
        private double reservedMana;
        private boolean revoked;
        private RuntimeHost(Player player, UUID spellId, Set<String> capabilities) {
            this.player = player; this.spellId = spellId; this.capabilities = Set.copyOf(capabilities);
            this.random = new SplittableRandom(player.getUniqueId().getMostSignificantBits() ^ spellId.getLeastSignificantBits() ^ tick);
        }

        public Object invoke(String api, List<Object> args, List<String> names, ArcVm.Execution execution) {
            if (!player.isOnline()) return ArcBytecode.Variant.of("Err", "PLAYER_OFFLINE");
            if (revoked) return ArcBytecode.Variant.of("Err", "CONTRACT_REVOKED");
            return switch (api) {
                case "mobs.hostile", "entities.hostile" -> hostile(args, names);
                case "target.nearest" -> hostile(args, names).stream().findFirst()
                        .<Object>map(value -> ArcBytecode.Variant.of("Some", value)).orElseGet(() -> ArcBytecode.Variant.empty("None"));
                case "combat.can_attack" -> canAttack(args.isEmpty() ? null : args.getFirst());
                case "entity.distance" -> distance(args);
                case "raycast.entities" -> raycastEntity(args);
                case "raycast.blocks", "blocks.query" -> raycastBlock(args);
                case "mana.current" -> mana.current(player) - reservedMana;
                case "mana.consume" -> reserveMana(args, execution);
                case "combat.damage", "combat.apply", "world.particle", "particles.spiral", "particles.ring", "particles.sphere",
                     "display.magic_circle", "display.item", "display.text", "pdc.set", "skript.emit", "event.emit" ->
                        request(api, args, execution);
                case "pdc.get" -> pdcGet(args);
                case "state.get" -> stateGet(args);
                case "state.set" -> stateSet(args, execution);
                case "random.chance" -> random.nextDouble() < number(args.getFirst(), 0, 1);
                case "math.clamp" -> Math.max(number(args.get(1), -Double.MAX_VALUE, Double.MAX_VALUE),
                        Math.min(number(args.get(2), -Double.MAX_VALUE, Double.MAX_VALUE), number(args.getFirst(), -Double.MAX_VALUE, Double.MAX_VALUE)));
                case "spell.cast", "spell.effect", "virtual_structure.show", "items.inspect", "time.after", "event.cancel" ->
                        ArcBytecode.Variant.of("Err", "CAPABILITY_NOT_AVAILABLE");
                default -> ArcBytecode.Variant.of("Err", "UNKNOWN_API");
            };
        }

        public Object member(Object receiver, String name) {
            return receiver instanceof Map<?, ?> map ? map.get(name) : null;
        }

        private List<Object> hostile(List<Object> args, List<String> names) {
            double radius = Math.min(maxRange, Math.max(1, namedNumber(args, names, "radius", 16)));
            int limit = (int) Math.min(32, Math.max(1, namedNumber(args, names, "limit", 8)));
            return player.getWorld().getNearbyLivingEntities(player.getLocation(), radius).stream()
                    .filter(entity -> entity instanceof Monster && entity.isValid() && !entity.isDead())
                    .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation())))
                    .limit(limit).map(ArcRuntimeService.this::entityRef).map(value -> (Object) value).toList();
        }

        private Object reserveMana(List<Object> args, ArcVm.Execution execution) {
            double amount = number(args.getFirst(), 0, mana.capacity(player));
            if (mana.current(player) - reservedMana + 1e-6 < amount) return ArcBytecode.Variant.of("Err", "NO_MANA");
            reservedMana += amount; execution.request(new ArcVm.EffectRequest("mana.consume", Map.of("amount", amount)));
            return true;
        }

        private Object request(String api, List<Object> args, ArcVm.Execution execution) {
            execution.request(new ArcVm.EffectRequest(api, Map.of("arguments", List.copyOf(args)))); return true;
        }

        private boolean canAttack(Object value) {
            LivingEntity target = living(value);
            return target != null && target != player && target.isValid() && !target.isDead()
                    && target.getWorld() == player.getWorld()
                    && target.getLocation().distanceSquared(player.getLocation()) <= maxRange * maxRange;
        }

        private Object raycastEntity(List<Object> args) {
            double range = args.isEmpty() ? 16 : number(args.getFirst(), 1, maxRange);
            RayTraceResult hit = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(),
                    range, 0.35, entity -> entity instanceof LivingEntity && entity != player);
            if (hit == null || !(hit.getHitEntity() instanceof LivingEntity entity)) return ArcBytecode.Variant.empty("None");
            return ArcBytecode.Variant.of("Some", entityRef(entity));
        }

        private Object raycastBlock(List<Object> args) {
            double range = args.isEmpty() ? 16 : number(args.getFirst(), 1, maxRange);
            RayTraceResult hit = player.getWorld().rayTraceBlocks(player.getEyeLocation(), player.getEyeLocation().getDirection(), range);
            Block block = hit == null ? null : hit.getHitBlock();
            if (block == null) return ArcBytecode.Variant.empty("None");
            return ArcBytecode.Variant.of("Some", Map.of("world", block.getWorld().getUID().toString(), "x", (long) block.getX(),
                    "y", (long) block.getY(), "z", (long) block.getZ(), "type", block.getType().name()));
        }

        private Object pdcGet(List<Object> args) {
            if (args.isEmpty()) return ArcBytecode.Variant.of("Err", "PDC_KEY_REQUIRED");
            NamespacedKey key = pdcKey(String.valueOf(args.getFirst()));
            if (key == null) return ArcBytecode.Variant.of("Err", "PDC_KEY_DENIED");
            String value = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            return value == null ? ArcBytecode.Variant.empty("None") : ArcBytecode.Variant.of("Some", value);
        }

        private Object stateGet(List<Object> args) {
            Object fallback = args.getFirst(); String name = String.valueOf(args.get(1)); String scope = String.valueOf(args.get(2));
            StateKey key = new StateKey(player.getUniqueId(), spellId, name, scope);
            if (pendingState.containsKey(key)) return pendingState.get(key);
            if (scope.equals("SESSION")) return sessionState.getOrDefault(key, fallback);
            return database.arcState(key.ownerId(), key.spellId(), key.name(), key.scope(), System.currentTimeMillis())
                    .map(SpellDatabase.ArcStateRecord::value).orElse(fallback);
        }

        private Object stateSet(List<Object> args, ArcVm.Execution execution) {
            Object value = args.getFirst(); String name = String.valueOf(args.get(1)); String scope = String.valueOf(args.get(2));
            long ttl = ((Number) args.get(3)).longValue(); StateKey key = new StateKey(player.getUniqueId(), spellId, name, scope);
            pendingState.put(key, value);
            execution.request(new ArcVm.EffectRequest("state.set", Map.of("name", name, "scope", scope, "ttl", ttl, "value", value)));
            return true;
        }

        private double distance(List<Object> args) {
            LivingEntity first = living(args.getFirst()), second = living(args.get(1));
            return first == null || second == null || first.getWorld() != second.getWorld() ? Double.POSITIVE_INFINITY
                    : first.getLocation().distance(second.getLocation());
        }
    }

    private void applyEffect(Player player, RuntimeHost host, ArcVm.EffectRequest effect) {
        switch (effect.type()) {
            case "mana.consume" -> mana.consume(player, ((Number) effect.parameters().get("amount")).doubleValue());
            case "state.set" -> {
                String name = String.valueOf(effect.parameters().get("name")); String scope = String.valueOf(effect.parameters().get("scope"));
                long ttl = ((Number) effect.parameters().get("ttl")).longValue(); Object value = effect.parameters().get("value");
                StateKey key = new StateKey(player.getUniqueId(), host.spellId, name, scope);
                if (scope.equals("SESSION")) sessionState.put(key, value);
                else {
                    Long expires = ttl <= 0 ? null : Math.addExact(System.currentTimeMillis(), Math.multiplyExact(ttl, 50L));
                    database.putArcState(key.ownerId(), key.spellId(), name, scope, value, 1, expires);
                }
            }
            case "combat.damage" -> applyDamage(player, arguments(effect));
            case "combat.apply" -> applyPotion(arguments(effect));
            case "world.particle", "particles.spiral", "particles.ring", "particles.sphere" -> applyParticle(player, arguments(effect));
            case "display.magic_circle", "display.item", "display.text" -> applyDisplay(player, effect.type(), arguments(effect));
            case "pdc.set" -> applyPdc(player, arguments(effect));
            case "skript.emit" -> applySkript(player, arguments(effect));
            case "event.emit" -> applyLocalSignal(player, arguments(effect));
            default -> { }
        }
    }

    private void applyDamage(Player source, List<Object> args) {
        if (args.size() < 2) return; LivingEntity target = living(args.getFirst());
        if (target == null || target == source || target.getWorld() != source.getWorld()
                || target.getLocation().distanceSquared(source.getLocation()) > maxRange * maxRange) return;
        double amount = number(args.get(1), 0, maxDamage); if (amount > 0) target.damage(amount, source);
    }

    private void applyPotion(List<Object> args) {
        if (args.size() < 3) return; String effect = String.valueOf(args.getFirst()).toUpperCase(Locale.ROOT);
        LivingEntity target = living(args.get(1)); if (target == null) return;
        PotionEffectType type = Registry.MOB_EFFECT.get(NamespacedKey.minecraft(effect.toLowerCase(Locale.ROOT)));
        if (type == null || Set.of("INSTANT_DAMAGE", "INSTANT_HEALTH").contains(effect)) return;
        int duration = (int) Math.min(maxPotionTicks, Math.max(1, ((Number) args.get(2)).longValue()));
        int amplifier = args.size() > 3 ? (int) Math.min(4, Math.max(0, ((Number) args.get(3)).longValue())) : 0;
        target.addPotionEffect(new PotionEffect(type, duration, amplifier, false, true, true));
    }

    private void applyParticle(Player player, List<Object> args) {
        if (args.isEmpty()) return; String name = String.valueOf(args.getFirst()).toUpperCase(Locale.ROOT);
        Set<Particle> allowed = Set.of(Particle.ENCHANT, Particle.FLAME, Particle.SOUL_FIRE_FLAME, Particle.END_ROD,
                Particle.ELECTRIC_SPARK, Particle.CRIT, Particle.WITCH, Particle.PORTAL);
        Particle particle;
        try { particle = Particle.valueOf(name); if (!allowed.contains(particle)) particle = Particle.ENCHANT; }
        catch (IllegalArgumentException ignored) { particle = Particle.ENCHANT; }
        int count = args.size() > 1 && args.get(1) instanceof Number number ? Math.min(100, Math.max(1, number.intValue())) : 12;
        player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), count, 0.5, 0.5, 0.5, 0.02);
    }

    private void applyDisplay(Player player, String type, List<Object> args) {
        Location location = displayLocation(player, args);
        if (!protections.canBuild(player, location)) throw new IllegalArgumentException("protected");
        int duration = displayDuration(args);
        Display display;
        if (type.equals("display.text")) {
            String text = args.isEmpty() ? "ArcSys" : String.valueOf(args.getFirst());
            if (text.length() > 128) text = text.substring(0, 128);
            String finalText = text;
            display = location.getWorld().spawn(location, TextDisplay.class, entity -> {
                entity.text(Component.text(finalText)); entity.setBillboard(Display.Billboard.CENTER); entity.setSeeThrough(true);
            });
        } else if (type.equals("display.item")) {
            Material material = material(args.isEmpty() ? "AMETHYST_SHARD" : String.valueOf(args.getFirst()), Material.AMETHYST_SHARD);
            display = location.getWorld().spawn(location, ItemDisplay.class, entity -> entity.setItemStack(new ItemStack(material)));
        } else {
            Material material = material(args.isEmpty() ? "GLOWSTONE" : String.valueOf(args.getFirst()), Material.GLOWSTONE);
            display = location.getWorld().spawn(location, BlockDisplay.class, entity -> entity.setBlock(material.createBlockData()));
        }
        display.addScoreboardTag("monaspellforge:temporary");
        Bukkit.getScheduler().runTaskLater(plugin, display::remove, duration);
    }

    private void applyPdc(Player player, List<Object> args) {
        if (args.size() < 2) throw new IllegalArgumentException("pdc args");
        NamespacedKey key = pdcKey(String.valueOf(args.getFirst()));
        if (key == null) throw new IllegalArgumentException("pdc key");
        String value = String.valueOf(args.get(1));
        if (value.length() > 512) throw new IllegalArgumentException("pdc value");
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
    }

    private void applySkript(Player player, List<Object> args) {
        if (args.isEmpty()) throw new IllegalArgumentException("signal");
        String signal = String.valueOf(args.getFirst()).toLowerCase(Locale.ROOT);
        if (!skriptSignals.contains(signal)) throw new IllegalArgumentException("signal denied");
        Object payload = args.size() > 1 ? args.get(1) : "";
        Bukkit.getPluginManager().callEvent(new ArcSkriptBridgeEvent(player, signal,
                Map.of("value", boundedBridgeValue(payload), "source", "ArcSys")));
    }

    private void applyLocalSignal(Player player, List<Object> args) {
        if (args.isEmpty()) return;
        String signal = safeSignal(String.valueOf(args.getFirst()));
        if (!isAllowed(signal)) throw new IllegalArgumentException("signal denied");
        emitSignal(player, signal, "");
    }

    private Location displayLocation(Player player, List<Object> args) {
        for (Object arg : args) {
            LivingEntity target = living(arg);
            if (target != null && target.getWorld() == player.getWorld()
                    && target.getLocation().distanceSquared(player.getLocation()) <= maxRange * maxRange)
                return target.getLocation().add(0, 1, 0);
        }
        return player.getLocation().add(player.getLocation().getDirection().multiply(2)).add(0, 1, 0);
    }

    private int displayDuration(List<Object> args) {
        for (int i = args.size() - 1; i >= 0; i--) if (args.get(i) instanceof Number n)
            return Math.min(maxDisplayTicks, Math.max(1, n.intValue()));
        return Math.min(maxDisplayTicks, 60);
    }

    private NamespacedKey pdcKey(String input) {
        String safe = input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        if (safe.isBlank() || safe.length() > 48 || !safe.startsWith("user_")) return null;
        return new NamespacedKey(plugin, "arc_" + safe);
    }

    private static Object boundedBridgeValue(Object value) {
        if (value instanceof Boolean || value instanceof Number) return value;
        String text = String.valueOf(value); return text.length() <= 256 ? text : text.substring(0, 256);
    }
    private static String safeSignal(String value) {
        String safe = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        return safe.length() <= 64 ? safe : safe.substring(0, 64);
    }
    private static Material material(String value, Material fallback) {
        Material material = Material.matchMaterial(value); return material != null && material.isItem() ? material : fallback;
    }

    @SuppressWarnings("unchecked") private static List<Object> arguments(ArcVm.EffectRequest effect) {
        Object value = effect.parameters().get("arguments"); return value instanceof List<?> list ? (List<Object>) list : List.of();
    }
    private LivingEntity living(Object reference) {
        if (!(reference instanceof Map<?, ?> map) || map.get("uuid") == null) return null;
        try { Entity entity = Bukkit.getEntity(UUID.fromString(String.valueOf(map.get("uuid")))); return entity instanceof LivingEntity living ? living : null; }
        catch (IllegalArgumentException ignored) { return null; }
    }
    private Map<String, Object> entityRef(LivingEntity entity) {
        var attribute = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uuid", entity.getUniqueId().toString()); result.put("type", entity.getType().name()); result.put("name", entity.getName());
        result.put("hp_ratio", entity.getHealth() / Math.max(1.0, attribute == null ? entity.getHealth() : attribute.getValue()));
        String mythicType = protections.mythicType(entity); if (!mythicType.isBlank()) result.put("mythic_type", mythicType);
        return Map.copyOf(result);
    }
    private static double namedNumber(List<Object> args, List<String> names, String name, double fallback) {
        int index = names.indexOf(name); if (index < 0 || index >= args.size() || !(args.get(index) instanceof Number number)) return fallback;
        return number.doubleValue();
    }
    private static double number(Object value, double minimum, double maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("number");
        return Math.max(minimum, Math.min(maximum, number.doubleValue()));
    }

    record CastOutcome(boolean accepted, String code, String message, ArcScheduler.TaskId taskId) { }
    private record StateKey(UUID ownerId, UUID spellId, String name, String scope) { }
}
