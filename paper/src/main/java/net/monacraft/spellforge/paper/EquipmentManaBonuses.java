package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads explicitly blue MP/MPS lore from equipped armor and both hands. */
final class EquipmentManaBonuses {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern FORMATTING = Pattern.compile("(?i)&[0-9A-FK-OR]");
    private static final Pattern STAT = Pattern.compile("(?i)^(MP|MPS)\\s*:\\s*\\+?([0-9]+(?:\\.[0-9]+)?)$");

    private EquipmentManaBonuses() { }

    static Bonus read(Player player, double maximumMpBonus, double maximumMpsBonus) {
        List<ItemStack> equipment = new ArrayList<>(6);
        equipment.add(player.getInventory().getItemInMainHand());
        equipment.add(player.getInventory().getItemInOffHand());
        for (ItemStack armor : player.getInventory().getArmorContents()) equipment.add(armor);
        return read(equipment, maximumMpBonus, maximumMpsBonus);
    }

    static Bonus read(Iterable<ItemStack> equipment, double maximumMpBonus, double maximumMpsBonus) {
        double mp = 0.0;
        double mps = 0.0;
        for (ItemStack item : equipment) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) continue;
            ItemMeta meta = item.getItemMeta();
            List<Component> lore = meta.lore();
            if (lore == null) continue;
            for (Component line : lore) {
                ParsedStat parsed = parse(line);
                if (parsed == null) continue;
                if (parsed.key.equals("MP")) mp += parsed.value;
                else mps += parsed.value;
            }
        }
        return new Bonus(Math.min(Math.max(0.0, maximumMpBonus), mp),
                Math.min(Math.max(0.0, maximumMpsBonus), mps));
    }

    static ParsedStat parse(Component line) {
        if (line == null) return null;
        return parseLegacy(LEGACY.serialize(line));
    }

    static ParsedStat parseLegacy(String legacy) {
        if (legacy == null) return null;
        String normalized = legacy.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        int blue = lower.indexOf("&9");
        if (blue < 0) return null;
        String beforeBlue = lower.substring(0, blue);
        if (!FORMATTING.matcher(beforeBlue).replaceAll("").isBlank()) return null;
        String plain = FORMATTING.matcher(normalized.substring(blue)).replaceAll("").trim();
        Matcher matcher = STAT.matcher(plain);
        if (!matcher.matches()) return null;
        try {
            double value = Double.parseDouble(matcher.group(2));
            if (!Double.isFinite(value) || value <= 0.0) return null;
            return new ParsedStat(matcher.group(1).toUpperCase(Locale.ROOT), value);
        } catch (NumberFormatException ignored) { return null; }
    }

    record Bonus(double maximumMana, double regenerationPerSecond) { }
    record ParsedStat(String key, double value) { }
}
