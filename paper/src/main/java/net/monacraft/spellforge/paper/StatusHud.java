package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Displays status only; it never changes health, max-health attributes, or vanilla hearts. */
final class StatusHud implements AutoCloseable {
    private static final DecimalFormat NUMBER = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
    private final ManaService mana;
    private final BukkitTask task;
    private final double healthScale;
    private final Map<UUID, PreviousScale> previousScales = new ConcurrentHashMap<>();

    StatusHud(Plugin plugin, ManaService mana, long intervalTicks, double healthScale) {
        this.mana = mana;
        this.healthScale = Math.max(1.0, healthScale);
        long interval = Math.max(1L, intervalTicks);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::renderAll, interval, interval);
    }

    private void renderAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            previousScales.computeIfAbsent(player.getUniqueId(), ignored ->
                    new PreviousScale(player.isHealthScaled(), player.isHealthScaled() ? player.getHealthScale() : 20.0));
            if (!player.isHealthScaled() || Math.abs(player.getHealthScale() - healthScale) > 0.001) {
                // Client-side display scaling only. The real health and MAX_HEALTH attribute remain unchanged.
                player.setHealthScaled(true);
                player.setHealthScale(healthScale);
            }
            double maxHealth = player.getAttribute(Attribute.MAX_HEALTH) == null
                    ? 20.0 : player.getAttribute(Attribute.MAX_HEALTH).getValue();
            Component line = Component.text("HP : ", NamedTextColor.RED)
                    .append(Component.text(format(player.getHealth()) + "/" + format(maxHealth), NamedTextColor.RED))
                    .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("MP : ", NamedTextColor.BLUE))
                    .append(Component.text(format(mana.current(player)) + "/" + format(mana.capacity(player)), NamedTextColor.BLUE));
            player.sendActionBar(line);
        }
    }

    private static String format(double value) {
        return NUMBER.format(Math.max(0.0, value));
    }

    @Override public void close() {
        task.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PreviousScale previous = previousScales.get(player.getUniqueId());
            if (previous == null) continue;
            player.setHealthScaled(previous.scaled());
            if (previous.scaled()) player.setHealthScale(previous.scale());
        }
        previousScales.clear();
    }

    private record PreviousScale(boolean scaled, double scale) {}
}
