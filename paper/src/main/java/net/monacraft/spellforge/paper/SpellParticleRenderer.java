package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import net.monacraft.spellforge.model.SpellModel.CompiledSpell;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Renders a bounded, circuit-aware particle composition for a compiled spell. */
final class SpellParticleRenderer {
    private static final double TWO_PI = Math.PI * 2.0;

    private final boolean enabled;
    private final int maxParticles;
    private final double degradedTps;
    private final double degradedMultiplier;

    SpellParticleRenderer(FileConfiguration config) {
        enabled = config.getBoolean("particles.enabled", true);
        maxParticles = Math.max(24, Math.min(600, config.getInt("particles.max-per-cast", 180)));
        degradedTps = Math.max(5.0, Math.min(20.0, config.getDouble("particles.degraded-below-tps", 18.0)));
        degradedMultiplier = Math.max(0.1, Math.min(1.0, config.getDouble("particles.degraded-multiplier", 0.4)));
    }

    void render(Player caster, CompiledSpell spell, String element, String shape, double range, double radius,
                double powerMultiplier, List<LivingEntity> targets) {
        if (!enabled || !caster.isOnline()) return;
        Map<String, Integer> levels = levels(spell);
        VisualPlan plan = plan(levels, element, shape, range, radius, powerMultiplier, maxParticles,
                currentTps() < degradedTps ? degradedMultiplier : 1.0);
        ParticleBudget budget = new ParticleBudget(caster.getWorld(), plan.particleBudget());
        ElementStyle style = style(element, plan.dustSize());
        Location origin = caster.getEyeLocation().clone().add(0, -0.12, 0);
        Vector forward = origin.getDirection().clone().normalize();
        Location focus = focus(caster, origin, forward, plan.range(), targets);

        switch (shape) {
            case "PROJECTILE" -> projectile(budget, style, origin, focus, forward, plan, targets);
            case "BEAM" -> beam(budget, style, origin, focus, forward, plan);
            case "WAVE" -> wave(budget, style, origin, forward, plan);
            case "BARRIER" -> sphere(budget, style, caster.getLocation().add(0, 1.05, 0), plan.radius(), plan);
            case "RING" -> rings(budget, style, caster.getLocation().add(0, 0.18, 0), plan.radius(), plan);
            case "LINE" -> lineShape(budget, style, origin, focus, forward, plan);
            case "SIGIL" -> sigil(budget, style, focus.clone().add(0, 0.08, 0), plan.radius(), plan);
            default -> burst(budget, style, focus, plan.radius(), plan);
        }
        if (plan.chain() && targets.size() > 1) chain(budget, style, caster, targets, plan);
        if (plan.durationRings() > 0 && !shape.equals("RING") && !shape.equals("SIGIL")) {
            accentRings(budget, style, focus, Math.min(plan.radius(), 4.5), plan.durationRings());
        }
        caster.getWorld().playSound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                (float) Math.min(1.4, 0.65 + plan.intensity() * 0.08), pitch(element, shape));
    }

    static VisualPlan plan(Map<String, Integer> levels, String element, String shape, double range, double radius,
                           double powerMultiplier, int maximum, double tpsMultiplier) {
        int elementLevel = levels.getOrDefault(element, 1);
        int shapeLevel = levels.getOrDefault(shape, 1);
        int powerLevel = levels.getOrDefault("POWER", 0);
        int areaLevel = levels.getOrDefault("AREA_SCALE", 0);
        int durationLevel = levels.getOrDefault("DURATION", 0);
        int amplifier = levels.containsKey("AMPLIFY_X4") ? 4 : levels.containsKey("AMPLIFY_X3") ? 3
                : levels.containsKey("AMPLIFY_X2") ? 2 : 1;
        int splitBranches = levels.containsKey("SPLIT") ? Math.min(5, 2 + levels.get("SPLIT")) : 1;
        double intensity = Math.min(8.0, Math.max(1.0, powerMultiplier) + powerLevel * 0.28 + amplifier * 0.18);
        int requested = 34 + elementLevel * 6 + shapeLevel * 7 + powerLevel * 5 + areaLevel * 8
                + durationLevel * 4 + (splitBranches - 1) * 15 + (levels.containsKey("CHAIN") ? 24 : 0);
        int bounded = Math.max(12, (int) Math.round(Math.min(maximum, requested) * tpsMultiplier));
        double visualRadius = Math.min(14.0, Math.max(1.0, radius) * (1.0 + areaLevel * 0.08));
        return new VisualPlan(element.toUpperCase(Locale.ROOT), shape.toUpperCase(Locale.ROOT),
                Math.min(64.0, Math.max(2.0, range)), visualRadius, intensity, bounded, splitBranches,
                levels.containsKey("HOMING"), levels.containsKey("PIERCE"), levels.containsKey("CHAIN"),
                durationLevel, (float) Math.min(2.5, 0.8 + intensity * 0.16));
    }

    private void projectile(ParticleBudget budget, ElementStyle style, Location origin, Location focus, Vector forward,
                            VisualPlan plan, List<LivingEntity> targets) {
        if (plan.homing() && !targets.isEmpty()) {
            for (int index = 0; index < Math.min(plan.splitBranches(), targets.size()); index++) {
                curvedLine(budget, style, origin, targets.get(index).getLocation().add(0, targets.get(index).getHeight() * 0.55, 0),
                        index * 0.55 + 0.7, plan);
            }
            return;
        }
        line(budget, style, origin, focus, 0.42, plan, true);
        for (int branch = 1; branch < plan.splitBranches(); branch++) {
            double sign = branch % 2 == 0 ? -1 : 1;
            double angle = Math.toRadians((12 + branch * 4) * sign);
            Vector branchDirection = forward.clone().rotateAroundY(angle);
            Location end = origin.clone().add(branchDirection.multiply(origin.distance(focus)));
            line(budget, style, origin, end, 0.62, plan, false);
        }
    }

    private void beam(ParticleBudget budget, ElementStyle style, Location origin, Location focus, Vector forward, VisualPlan plan) {
        double distance = origin.distance(focus);
        Vector direction = focus.toVector().subtract(origin.toVector()).normalize();
        Vector side = perpendicular(direction);
        for (double travelled = 0; travelled <= distance && budget.hasRemaining(); travelled += 0.32) {
            Location center = origin.clone().add(direction.clone().multiply(travelled));
            budget.dust(center, style);
            double phase = travelled * (plan.pierce() ? 3.2 : 2.2);
            Vector spiral = side.clone().rotateAroundAxis(direction, phase).multiply(0.16 + plan.intensity() * 0.018);
            budget.secondary(center.clone().add(spiral), style, 0.0);
            if (plan.pierce() && ((int) (travelled * 10)) % 5 == 0) budget.dust(center.clone().subtract(spiral), style);
        }
    }

    private void lineShape(ParticleBudget budget, ElementStyle style, Location origin, Location focus, Vector forward, VisualPlan plan) {
        line(budget, style, origin, focus, plan.pierce() ? 0.28 : 0.48, plan, true);
        if (plan.pierce()) {
            Vector side = perpendicular(forward).multiply(0.18);
            line(budget, style, origin.clone().add(side), focus.clone().add(side), 0.55, plan, false);
            line(budget, style, origin.clone().subtract(side), focus.clone().subtract(side), 0.55, plan, false);
        }
    }

    private void wave(ParticleBudget budget, ElementStyle style, Location origin, Vector forward, VisualPlan plan) {
        Vector side = perpendicular(forward).setY(0).normalize();
        int waves = Math.min(5, 2 + plan.splitBranches());
        for (int wave = 1; wave <= waves && budget.hasRemaining(); wave++) {
            double distance = Math.min(plan.range(), 2.0 + wave * 2.2);
            double width = plan.radius() * wave / waves;
            for (int step = -10; step <= 10 && budget.hasRemaining(); step++) {
                double t = step / 10.0;
                Location point = origin.clone().add(forward.clone().multiply(distance + Math.cos(t * Math.PI) * 0.45))
                        .add(side.clone().multiply(width * t)).add(0, Math.sin((t + 1) * Math.PI) * 0.65 - 0.45, 0);
                budget.alternating(point, style, step + wave);
            }
        }
    }

    private void burst(ParticleBudget budget, ElementStyle style, Location center, double radius, VisualPlan plan) {
        int rings = Math.max(3, Math.min(8, (int) Math.ceil(plan.intensity()) + 2));
        for (int ring = 1; ring <= rings && budget.hasRemaining(); ring++) {
            double phi = Math.PI * ring / (rings + 1);
            double ringRadius = Math.sin(phi) * radius;
            double y = Math.cos(phi) * radius;
            circle(budget, style, center.clone().add(0, y, 0), ringRadius, 24, ring);
        }
        budget.secondary(center, style, Math.min(0.18, plan.intensity() * 0.02), Math.min(12, budget.remaining()), radius * 0.2);
    }

    private void sphere(ParticleBudget budget, ElementStyle style, Location center, double radius, VisualPlan plan) {
        int latitude = Math.max(3, Math.min(7, (int) Math.ceil(plan.intensity()) + 2));
        for (int ring = 1; ring <= latitude && budget.hasRemaining(); ring++) {
            double phi = Math.PI * ring / (latitude + 1);
            circle(budget, style, center.clone().add(0, Math.cos(phi) * radius, 0), Math.sin(phi) * radius, 28, ring);
        }
    }

    private void rings(ParticleBudget budget, ElementStyle style, Location center, double radius, VisualPlan plan) {
        int count = Math.max(1, Math.min(5, 1 + plan.durationRings()));
        for (int ring = 0; ring < count && budget.hasRemaining(); ring++) {
            circle(budget, style, center.clone().add(0, ring * 0.42, 0), radius * (1.0 - ring * 0.08), 40, ring);
        }
    }

    private void sigil(ParticleBudget budget, ElementStyle style, Location center, double radius, VisualPlan plan) {
        double r = Math.min(8.0, Math.max(1.5, radius));
        circle(budget, style, center, r, 44, 0);
        circle(budget, style, center, r * 0.58, 32, 1);
        for (int arm = 0; arm < 6 && budget.hasRemaining(); arm++) {
            double a = TWO_PI * arm / 6.0;
            Location start = center.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            Location end = center.clone().add(Math.cos(a + Math.PI * 2 / 3) * r, 0, Math.sin(a + Math.PI * 2 / 3) * r);
            line(budget, style, start, end, 0.5, plan, false);
        }
    }

    private void chain(ParticleBudget budget, ElementStyle style, Player caster, List<LivingEntity> targets, VisualPlan plan) {
        Location previous = caster.getEyeLocation();
        for (LivingEntity target : targets.stream().limit(8).toList()) {
            Location next = target.getLocation().add(0, target.getHeight() * 0.55, 0);
            curvedLine(budget, style, previous, next, 0.35, plan);
            previous = next;
            if (!budget.hasRemaining()) break;
        }
    }

    private void accentRings(ParticleBudget budget, ElementStyle style, Location center, double radius, int count) {
        for (int ring = 0; ring < Math.min(4, count) && budget.hasRemaining(); ring++) {
            circle(budget, style, center.clone().add(0, ring * 0.22, 0), radius * (0.55 + ring * 0.12), 24, ring);
        }
    }

    private void curvedLine(ParticleBudget budget, ElementStyle style, Location start, Location end, double curve, VisualPlan plan) {
        Vector delta = end.toVector().subtract(start.toVector());
        double distance = Math.max(0.01, delta.length());
        Vector side = perpendicular(delta.clone().normalize());
        int steps = Math.max(4, Math.min(64, (int) Math.ceil(distance / 0.45)));
        for (int step = 0; step <= steps && budget.hasRemaining(); step++) {
            double t = step / (double) steps;
            Vector point = start.toVector().clone().multiply(1 - t).add(end.toVector().clone().multiply(t));
            point.add(side.clone().multiply(Math.sin(Math.PI * t) * curve));
            budget.alternating(point.toLocation(start.getWorld()), style, step);
        }
    }

    private void line(ParticleBudget budget, ElementStyle style, Location start, Location end, double spacing,
                      VisualPlan plan, boolean alternate) {
        Vector delta = end.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (distance < 0.01) return;
        Vector direction = delta.normalize();
        for (double travelled = 0; travelled <= distance && budget.hasRemaining(); travelled += spacing) {
            Location point = start.clone().add(direction.clone().multiply(travelled));
            if (alternate) budget.alternating(point, style, (int) Math.round(travelled / spacing)); else budget.dust(point, style);
        }
    }

    private void circle(ParticleBudget budget, ElementStyle style, Location center, double radius, int requested, int phase) {
        int points = Math.max(8, Math.min(requested, budget.remaining()));
        for (int point = 0; point < points && budget.hasRemaining(); point++) {
            double angle = TWO_PI * point / points;
            Location at = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            budget.alternating(at, style, point + phase);
        }
    }

    private static Location focus(Player caster, Location origin, Vector direction, double range, List<LivingEntity> targets) {
        if (!targets.isEmpty()) {
            LivingEntity target = targets.getFirst();
            return target.getLocation().add(0, target.getHeight() * 0.55, 0);
        }
        RayTraceResult trace = caster.getWorld().rayTraceBlocks(origin, direction, range);
        if (trace != null && trace.getHitPosition() != null) return trace.getHitPosition().toLocation(caster.getWorld());
        return origin.clone().add(direction.clone().multiply(range));
    }

    private static Vector perpendicular(Vector direction) {
        Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.001) side = new Vector(1, 0, 0);
        return side.normalize();
    }

    private static Map<String, Integer> levels(CompiledSpell spell) {
        Map<String, Integer> levels = new HashMap<>();
        for (CircuitNode node : spell.executionOrder()) levels.merge(node.fragmentId(), node.level(), Math::max);
        return levels;
    }

    private static ElementStyle style(String element, float dustSize) {
        return switch (element) {
            case "FIRE" -> new ElementStyle(Color.fromRGB(255, 72, 12), Particle.FLAME, dustSize);
            case "ICE" -> new ElementStyle(Color.fromRGB(120, 235, 255), Particle.SNOWFLAKE, dustSize);
            case "WIND" -> new ElementStyle(Color.fromRGB(225, 255, 235), Particle.CLOUD, dustSize);
            case "EARTH" -> new ElementStyle(Color.fromRGB(132, 83, 38), Particle.CLOUD, dustSize);
            case "LIGHTNING" -> new ElementStyle(Color.fromRGB(255, 236, 32), Particle.ENCHANT, dustSize);
            case "WATER" -> new ElementStyle(Color.fromRGB(35, 125, 255), Particle.SNOWFLAKE, dustSize);
            case "LIGHT" -> new ElementStyle(Color.fromRGB(255, 246, 165), Particle.ENCHANT, dustSize);
            case "DARK" -> new ElementStyle(Color.fromRGB(78, 20, 125), Particle.PORTAL, dustSize);
            case "POISON" -> new ElementStyle(Color.fromRGB(92, 210, 35), Particle.WITCH, dustSize);
            case "GRAVITY" -> new ElementStyle(Color.fromRGB(145, 42, 220), Particle.PORTAL, dustSize);
            case "HEAL" -> new ElementStyle(Color.fromRGB(90, 255, 145), Particle.HEART, dustSize);
            default -> new ElementStyle(Color.fromRGB(225, 90, 255), Particle.ENCHANT, dustSize);
        };
    }

    private static float pitch(String element, String shape) {
        float elementPitch = switch (element) {
            case "FIRE", "EARTH", "DARK", "GRAVITY" -> 0.78f;
            case "ICE", "WATER", "HEAL" -> 1.35f;
            case "LIGHTNING", "LIGHT", "WIND" -> 1.65f;
            default -> 1.15f;
        };
        return Math.max(0.55f, Math.min(1.9f, elementPitch + (shape.equals("BEAM") ? 0.12f : 0f)));
    }

    private static double currentTps() {
        double[] tps = Bukkit.getTPS();
        return tps.length == 0 ? 20.0 : tps[0];
    }

    record VisualPlan(String element, String shape, double range, double radius, double intensity, int particleBudget,
                      int splitBranches, boolean homing, boolean pierce, boolean chain, int durationRings, float dustSize) { }

    private record ElementStyle(Color color, Particle secondary, float dustSize) { }

    private static final class ParticleBudget {
        private final World world;
        private int remaining;

        ParticleBudget(World world, int remaining) { this.world = world; this.remaining = Math.max(0, remaining); }
        int remaining() { return remaining; }
        boolean hasRemaining() { return remaining > 0; }
        void alternating(Location location, ElementStyle style, int index) {
            if ((index & 1) == 0) dust(location, style); else secondary(location, style, 0.0);
        }
        void dust(Location location, ElementStyle style) {
            if (remaining-- <= 0) return;
            world.spawnParticle(Particle.DUST, location, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(style.color(), style.dustSize()));
        }
        void secondary(Location location, ElementStyle style, double speed) { secondary(location, style, speed, 1, 0); }
        void secondary(Location location, ElementStyle style, double speed, int requested, double spread) {
            int count = Math.max(0, Math.min(requested, remaining));
            if (count == 0) return;
            remaining -= count;
            world.spawnParticle(style.secondary(), location, count, spread, spread, spread, speed);
        }
    }
}
