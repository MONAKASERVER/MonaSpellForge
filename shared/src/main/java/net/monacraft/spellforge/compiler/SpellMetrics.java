package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import net.monacraft.spellforge.model.SpellModel.CompiledSpell;
import net.monacraft.spellforge.model.SpellModel.FragmentDefinition;
import net.monacraft.spellforge.model.SpellModel.LevelParameters;

import java.util.Locale;

/** Deterministic combat estimates shared by the editor UI and tests. */
public final class SpellMetrics {
    private SpellMetrics() { }

    public static CombatMetrics calculate(CompiledSpell spell) {
        if (spell == null) return CombatMetrics.empty();
        CircuitNode element = spell.executionOrder().stream()
                .filter(node -> isElement(node.fragmentId())).findFirst().orElse(null);
        double base = element == null ? 8.0 : parameters(spell, element).power();
        double multiplier = 1.0;
        CircuitNode power = node(spell, "POWER");
        if (power != null) multiplier *= 1 + power.level() * 0.22;
        multiplier *= node(spell, "AMPLIFY_X4") != null ? 4.0
                : node(spell, "AMPLIFY_X3") != null ? 3.0
                : node(spell, "AMPLIFY_X2") != null ? 2.0 : 1.0;
        double attack = Math.min(320, base * multiplier) * elementMultiplier(element == null ? "ARCANE" : element.fragmentId());

        CircuitNode trueDamage = node(spell, "TRUE_DAMAGE");
        if (trueDamage != null) attack = Math.min(1_200, Math.max(attack, parameters(spell, trueDamage).power() * multiplier));

        int hits = 1;
        CircuitNode repeat = node(spell, "REPEAT");
        if (repeat != null) hits = Math.min(5, Math.max(1, integer(repeat, "count", repeat.level() + 1)));
        if (node(spell, "PARALLEL") != null || node(spell, "CALL") != null) hits = Math.min(8, hits + 1);

        double windowSeconds = 1.0;
        CircuitNode periodic = node(spell, "PERIODIC_SIGNAL");
        if (periodic != null) {
            double interval = clamp(number(periodic, "seconds", Math.max(1, periodic.level())), 0.25, 30);
            CircuitNode speed = node(spell, "CIRCUIT_SPEED");
            int speedMultiplier = speed == null ? 1 : Math.max(2, Math.min(8, speed.level() * 2));
            windowSeconds = Math.max(0.05, interval / speedMultiplier) * Math.max(1, hits - 1);
        }
        double fireRate = hits / windowSeconds;
        CircuitNode penetration = node(spell, "ARMOR_PENETRATION");
        double penetrationPercent = penetration == null ? 0 : parameters(spell, penetration).power() * 100.0;
        return new CombatMetrics(attack, attack * fireRate, fireRate, spell.manaCost(), spell.runtimeCost(), penetrationPercent, trueDamage != null);
    }

    private static LevelParameters parameters(CompiledSpell spell, CircuitNode node) {
        FragmentDefinition definition = spell.resolvedDefinitions().get(node.nodeId());
        return definition.levels().get(node.level());
    }

    private static CircuitNode node(CompiledSpell spell, String id) {
        return spell.executionOrder().stream().filter(candidate -> candidate.fragmentId().equals(id)).findFirst().orElse(null);
    }

    private static boolean isElement(String id) {
        return switch (id.toUpperCase(Locale.ROOT)) {
            case "FIRE", "ICE", "WIND", "EARTH", "LIGHTNING", "WATER", "LIGHT", "DARK", "POISON", "GRAVITY", "HEAL", "ARCANE" -> true;
            default -> false;
        };
    }

    private static double elementMultiplier(String id) {
        return switch (id) {
            case "ICE", "GRAVITY" -> 0.8;
            case "WIND" -> 0.65;
            case "EARTH" -> 1.25;
            case "LIGHTNING" -> 1.1;
            case "WATER" -> 0.7;
            case "POISON" -> 0.45;
            default -> 1.0;
        };
    }

    private static int integer(CircuitNode node, String key, int fallback) {
        try { return Integer.parseInt(node.parameters().getOrDefault(key, Integer.toString(fallback))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double number(CircuitNode node, String key, double fallback) {
        try { return Double.parseDouble(node.parameters().getOrDefault(key, Double.toString(fallback))); }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record CombatMetrics(double attackPower, double dps, double fireRate, double manaCost,
                                int runtimeCost, double armorPenetrationPercent, boolean trueDamage) {
        public static CombatMetrics empty() { return new CombatMetrics(0, 0, 0, 0, 0, 0, false); }
    }
}
