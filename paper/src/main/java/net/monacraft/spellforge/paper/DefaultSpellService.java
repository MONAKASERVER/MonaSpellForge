package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.SpellService;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import net.monacraft.spellforge.model.SpellModel.*;
import org.bukkit.*;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class DefaultSpellService implements SpellService {
    private final Plugin plugin;
    private final SpellDatabase database;
    private final CircuitCompiler compiler;
    private final ManaService mana;
    private final TickBudgetManager budgets;
    private final Map<UUID, Set<UUID>> active = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BukkitTask>> scheduled = new ConcurrentHashMap<>();
    private final Map<String, Double> memory = new ConcurrentHashMap<>();

    DefaultSpellService(Plugin plugin, SpellDatabase database, CircuitCompiler compiler, ManaService mana, TickBudgetManager budgets) {
        this.plugin = plugin; this.database = database; this.compiler = compiler; this.mana = mana; this.budgets = budgets;
    }

    CastResult castActive(Player player, String trigger) {
        return database.activeCircuit(player.getUniqueId())
                .map(circuit -> cast(player, circuit.circuitId(), trigger))
                .orElseGet(() -> new CastResult(false, "NO_ACTIVE_CIRCUIT", "有効な魔術回路がありません", null));
    }

    @Override public CastResult cast(Player player, UUID circuitId, String trigger) {
        Circuit circuit = database.circuit(circuitId).orElse(null);
        if (circuit == null || !circuit.ownerId().equals(player.getUniqueId())) return reject("NOT_FOUND", "魔術回路が見つかりません");
        return castCircuit(player, circuit, trigger);
    }

    CastResult castEmbedded(Player player, Circuit circuit, String trigger) {
        if (circuit == null || !circuit.ownerId().equals(player.getUniqueId())) {
            return reject("NOT_FOUND", "封入された魔術を使用できません");
        }
        return castCircuit(player, circuit, trigger);
    }

    private CastResult castCircuit(Player player, Circuit circuit, String trigger) {
        CompilationResult compilation = compiler.compile(circuit);
        if (!compilation.valid()) return reject("INVALID_CIRCUIT", "魔術回路が無効です");
        CompiledSpell spell = compilation.spell();
        String normalizedTrigger = trigger.toUpperCase(Locale.ROOT);
        boolean acceptsTrigger = spell.resolvedDefinitions().values().stream().anyMatch(d -> d.category() == Category.TRIGGER && d.id().equals(normalizedTrigger));
        if (!acceptsTrigger && !normalizedTrigger.equals("MANUAL")) return reject("WRONG_TRIGGER", "この発動条件には反応しません");
        String blocked = precondition(player, spell);
        if (blocked != null) return reject("CONDITION", blocked);
        if (!budgets.canStart(spell.runtimeCost())) return reject("SERVER_BUDGET", "サーバー保護のため現在は発動できません");
        if (!mana.consume(player, spell.manaCost())) return reject("MANA", "魔力が不足しています（必要 " + (int) spell.manaCost() + "）");

        UUID castId = UUID.randomUUID(); long started = System.currentTimeMillis();
        active.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(castId);
        database.auditCast(castId, player.getUniqueId(), circuit.circuitId(), "STARTED", spell.manaCost(), spell.runtimeCost(), started, null);
        try {
            RuntimePlan plan = plan(player, spell, circuit.circuitId());
            if (plan.delayTicks > 0 || plan.intervalTicks > 0) {
                schedule(player, spell, circuit.circuitId(), castId, started, plan);
                return new CastResult(true, "SCHEDULED", "魔術を起動しました", castId);
            }
            for (int iteration = 0; iteration < plan.iterations; iteration++) execute(player, spell, circuit.circuitId(), plan.powerMultiplier);
            finish(player.getUniqueId(), castId);
            database.auditCast(castId, player.getUniqueId(), circuit.circuitId(), "SUCCESS", spell.manaCost(), spell.runtimeCost(), started, System.currentTimeMillis());
            return new CastResult(true, "OK", "魔術を発動しました", castId);
        } catch (RuntimeException error) {
            mana.refund(player, spell.manaCost());
            database.auditCast(castId, player.getUniqueId(), circuit.circuitId(), "FAILED", spell.manaCost(), spell.runtimeCost(), started, System.currentTimeMillis());
            return reject("EXECUTION_FAILED", "魔術の実行に失敗しました");
        }
    }

    private String precondition(Player player, CompiledSpell spell) {
        CircuitNode sensor = node(spell, "MOB_SENSOR");
        if (sensor != null) {
            double radius = clamp(number(sensor, "radius", 3.0 + sensor.level() - 1), 1, 32);
            boolean found = player.getNearbyEntities(radius, radius, radius).stream().anyMatch(Mob.class::isInstance);
            if (!found) return "感知範囲内にMobがいません";
        }
        CircuitNode gate = node(spell, "MAGIC_GATE");
        if (gate != null) {
            int required = (int) clamp(number(gate, "minimum_targets", 1), 1, 32);
            double radius = clamp(number(gate, "radius", 6), 1, 32);
            long found = player.getNearbyEntities(radius, radius, radius).stream().filter(LivingEntity.class::isInstance).filter(e -> e != player).count();
            if (found < required) return "魔術門の条件を満たしていません（対象 " + found + "/" + required + "）";
        }
        return null;
    }

    private RuntimePlan plan(Player player, CompiledSpell spell, UUID circuitId) {
        double multiplier = node(spell, "AMPLIFY_X4") != null ? 4 : node(spell, "AMPLIFY_X3") != null ? 3 : node(spell, "AMPLIFY_X2") != null ? 2 : 1;
        CircuitNode variable = node(spell, "SPELL_VARIABLE");
        if (variable != null) multiplier *= clamp(1 + number(variable, "value", 0) / 100.0, 0.25, 4);
        if (node(spell, "CHAOS") != null) multiplier *= 0.75 + Math.random() * 0.5;
        String memoryKey = player.getUniqueId() + ":" + circuitId;
        if (node(spell, "RETURN_CIRCUIT") != null) multiplier *= 1 + Math.min(1.5, memory.getOrDefault(memoryKey, 0.0) * 0.1);
        int iterations = 1;
        CircuitNode repeat = node(spell, "REPEAT");
        if (repeat != null) iterations = Math.min(5, Math.max(1, (int) number(repeat, "count", repeat.level() + 1)));
        if (node(spell, "PARALLEL") != null || node(spell, "CALL") != null) iterations = Math.min(8, iterations + 1);
        CircuitNode delay = node(spell, "TIMER");
        if (delay == null) delay = node(spell, "DELAY");
        long delayTicks = delay == null ? 0 : (long) (clamp(number(delay, "seconds", delay.level()), 0.05, 30) * 20);
        CircuitNode periodic = node(spell, "PERIODIC_SIGNAL");
        long interval = periodic == null ? 0 : (long) (clamp(number(periodic, "seconds", Math.max(1, periodic.level())), 0.25, 30) * 20);
        if (periodic != null || node(spell, "AUTOMATION") != null) iterations = Math.min(8, Math.max(iterations, periodic == null ? 3 : periodic.level() + 1));
        return new RuntimePlan(iterations, delayTicks, interval, multiplier);
    }

    private void schedule(Player player, CompiledSpell spell, UUID circuitId, UUID castId, long started, RuntimePlan plan) {
        BukkitRunnable runnable = new BukkitRunnable() {
            int remaining = plan.iterations;
            @Override public void run() {
                try {
                    if (!player.isOnline() || remaining-- <= 0) { complete(false); return; }
                    execute(player, spell, circuitId, plan.powerMultiplier);
                    if (remaining <= 0 || plan.intervalTicks == 0) complete(true);
                } catch (RuntimeException error) {
                    complete(false);
                }
            }
            private void complete(boolean success) {
                cancel();
                finish(player.getUniqueId(), castId);
                database.auditCast(castId, player.getUniqueId(), circuitId, success ? "SUCCESS" : "FAILED", spell.manaCost(), spell.runtimeCost(), started, System.currentTimeMillis());
            }
        };
        BukkitTask task = plan.intervalTicks > 0
                ? runnable.runTaskTimer(plugin, Math.max(1, plan.delayTicks), plan.intervalTicks)
                : runnable.runTaskLater(plugin, Math.max(1, plan.delayTicks));
        scheduled.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(task);
    }

    private void execute(Player player, CompiledSpell spell, UUID circuitId, double planMultiplier) {
        Map<String, Integer> levels = new HashMap<>();
        for (CircuitNode node : spell.executionOrder()) levels.merge(node.fragmentId(), node.level(), Math::max);
        double power = (1 + levels.getOrDefault("POWER", 0) * 0.22) * planMultiplier;
        double range = Math.min(64, 16 + levels.getOrDefault("RANGE", 0) * 8);
        double radius = Math.min(12, 3 + levels.getOrDefault("AREA_SCALE", 0) * 1.5);
        String element = spell.executionOrder().stream().map(CircuitNode::fragmentId).filter(FragmentCatalogIds.ELEMENTS::contains).findFirst().orElse("ARCANE");
        String shape = spell.executionOrder().stream().map(CircuitNode::fragmentId).filter(FragmentCatalogIds.SHAPES::contains).findFirst().orElse("BURST");
        String target = spell.executionOrder().stream().map(CircuitNode::fragmentId).filter(FragmentCatalogIds.TARGETS::contains).findFirst().orElse("LOOK_TARGET");

        List<LivingEntity> targets = new ArrayList<>(selectTargets(player, target, shape, range, radius));
        if (node(spell, "CHAIN") != null && !targets.isEmpty()) {
            LivingEntity origin = targets.getFirst();
            origin.getNearbyEntities(Math.min(12, radius + 3), Math.min(12, radius + 3), Math.min(12, radius + 3)).stream()
                    .filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).filter(entity -> entity != player && !targets.contains(entity))
                    .limit(7).forEach(targets::add);
        }
        if (element.equals("HEAL") && targets.isEmpty()) targets.add(player);
        for (LivingEntity victim : targets) applyElement(player, victim, element, power, spell);
        String memoryKey = player.getUniqueId() + ":" + circuitId;
        if (node(spell, "COUNTER") != null || node(spell, "MEMORY_CORE") != null || node(spell, "RETURN_CIRCUIT") != null) {
            memory.merge(memoryKey, (double) Math.max(1, targets.size()), Double::sum);
        }
        render(player, element, shape, range, radius);
        player.sendActionBar(Component.text("✦ " + element + " / " + shape + "  魔力 " + (int) mana.current(player), NamedTextColor.LIGHT_PURPLE));
    }

    private List<LivingEntity> selectTargets(Player player, String target, String shape, double range, double radius) {
        if (target.equals("SELF")) return List.of(player);
        if (target.equals("ALLIES")) return player.getNearbyEntities(radius, radius, radius).stream().filter(Player.class::isInstance).map(Player.class::cast).filter(p -> p != player).map(LivingEntity.class::cast).toList();
        if (target.equals("AREA_TARGETS") || shape.equals("BURST") || shape.equals("RING") || shape.equals("WAVE")) {
            return player.getNearbyEntities(radius, radius, radius).stream().filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast).filter(e -> e != player).limit(32).toList();
        }
        RayTraceResult trace = player.getWorld().rayTraceEntities(player.getEyeLocation(), player.getEyeLocation().getDirection(), range, 0.5,
                entity -> entity instanceof LivingEntity && entity != player);
        if (trace != null && trace.getHitEntity() instanceof LivingEntity living) return List.of(living);
        if (target.equals("NEAREST_ENEMY")) return player.getNearbyEntities(range, range, range).stream().filter(LivingEntity.class::isInstance).map(LivingEntity.class::cast)
                .filter(e -> e != player).min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation()))).map(List::of).orElse(List.of());
        return List.of();
    }

    private void applyElement(Player caster, LivingEntity target, String element, double multiplier, CompiledSpell spell) {
        CircuitNode node = spell.executionOrder().stream().filter(n -> n.fragmentId().equals(element)).findFirst().orElse(null);
        double base = node == null ? 8 : spell.resolvedDefinitions().get(node.nodeId()).levels().get(node.level()).power();
        double damage = Math.min(320, base * multiplier);
        switch (element) {
            case "FIRE" -> { target.damage(damage, caster); target.setFireTicks(Math.max(target.getFireTicks(), 60)); }
            case "ICE" -> { target.damage(damage * 0.8, caster); target.setFreezeTicks(Math.max(target.getFreezeTicks(), target.getMaxFreezeTicks() + 40)); target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2)); }
            case "WIND" -> { target.damage(damage * 0.65, caster); target.setVelocity(target.getVelocity().add(target.getLocation().toVector().subtract(caster.getLocation().toVector()).normalize().multiply(1.4).setY(0.5))); }
            case "EARTH" -> { target.damage(damage * 1.25, caster); target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1)); }
            case "LIGHTNING" -> { target.getWorld().strikeLightningEffect(target.getLocation()); target.damage(damage * 1.1, caster); }
            case "WATER" -> { target.damage(damage * 0.7, caster); target.setFireTicks(0); target.setVelocity(target.getVelocity().multiply(0.5)); }
            case "LIGHT" -> { target.damage(damage, caster); target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 120, 0)); }
            case "DARK" -> { target.damage(damage, caster); target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0)); }
            case "POISON" -> { target.damage(damage * 0.45, caster); target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1)); }
            case "GRAVITY" -> { target.damage(damage * 0.8, caster); target.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 35, 2)); }
            case "HEAL" -> target.setHealth(Math.min(target.getMaxHealth(), target.getHealth() + damage));
            default -> target.damage(damage, caster);
        }
    }

    private void render(Player player, String element, String shape, double range, double radius) {
        Particle particle = switch (element) { case "FIRE" -> Particle.FLAME; case "ICE", "WATER" -> Particle.SNOWFLAKE; case "WIND" -> Particle.CLOUD; case "POISON" -> Particle.WITCH; case "DARK" -> Particle.PORTAL; case "HEAL" -> Particle.HEART; default -> Particle.ENCHANT; };
        Location at = player.getLocation().add(0, 1, 0);
        int count = shape.equals("BURST") || shape.equals("RING") ? 60 : 24;
        player.getWorld().spawnParticle(particle, at, count, radius / 3, 1, radius / 3, 0.03);
        player.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
    }

    @Override public int cancel(UUID playerId) { Set<UUID> removed = active.remove(playerId);Set<BukkitTask> tasks=scheduled.remove(playerId);if(tasks!=null)tasks.forEach(BukkitTask::cancel);return removed == null ? 0 : removed.size(); }
    @Override public int cancelAll() { int total = active.values().stream().mapToInt(Set::size).sum(); active.clear();scheduled.values().forEach(tasks->tasks.forEach(BukkitTask::cancel));scheduled.clear();return total; }
    @Override public Collection<UUID> getActiveCasts(UUID playerId) { return Set.copyOf(active.getOrDefault(playerId, Set.of())); }
    private static CastResult reject(String code, String message) { return new CastResult(false, code, message, null); }

    private void finish(UUID playerId, UUID castId) { Set<UUID> casts=active.get(playerId);if(casts!=null)casts.remove(castId);Set<BukkitTask> tasks=scheduled.get(playerId);if(tasks!=null)tasks.removeIf(BukkitTask::isCancelled); }
    private static CircuitNode node(CompiledSpell spell,String id){return spell.executionOrder().stream().filter(n->n.fragmentId().equals(id)).findFirst().orElse(null);}
    private static double number(CircuitNode node,String key,double fallback){try{return Double.parseDouble(node.parameters().getOrDefault(key,Double.toString(fallback)));}catch(Exception ignored){return fallback;}}
    private static double clamp(double value,double min,double max){return Math.max(min,Math.min(max,value));}
    private record RuntimePlan(int iterations,long delayTicks,long intervalTicks,double powerMultiplier){}

    private static final class FragmentCatalogIds {
        static final Set<String> ELEMENTS = Set.of("FIRE","ICE","WIND","EARTH","LIGHTNING","WATER","LIGHT","DARK","POISON","GRAVITY","HEAL","ARCANE");
        static final Set<String> SHAPES = Set.of("PROJECTILE","BEAM","WAVE","BURST","BARRIER","RING","LINE","SIGIL");
        static final Set<String> TARGETS = Set.of("LOOK_TARGET","NEAREST_ENEMY","AREA_TARGETS","SELF","ALLIES");
    }
}
