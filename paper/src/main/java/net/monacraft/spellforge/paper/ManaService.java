package net.monacraft.spellforge.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ManaService implements AutoCloseable {
    private final Map<UUID, Double> mana = new ConcurrentHashMap<>();
    private final double baseCapacity;
    private final double baseRegeneration;
    private final double maximumEquipmentCapacityBonus;
    private final double maximumEquipmentRegenerationBonus;
    private final BukkitTask task;

    ManaService(Plugin plugin, double capacity, double regeneration,
                double maximumEquipmentCapacityBonus, double maximumEquipmentRegenerationBonus) {
        this.baseCapacity = Math.max(0.0, capacity);
        this.baseRegeneration = Math.max(0.0, regeneration);
        this.maximumEquipmentCapacityBonus = Math.max(0.0, maximumEquipmentCapacityBonus);
        this.maximumEquipmentRegenerationBonus = Math.max(0.0, maximumEquipmentRegenerationBonus);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(player ->
                mana.compute(player.getUniqueId(), (id, value) -> {
                    double capacityNow = capacity(player);
                    return Math.min(capacityNow, (value == null ? capacityNow : value) + regeneration(player));
                })), 20L, 20L);
    }

    double current(Player player) {
        double capacityNow = capacity(player);
        return mana.compute(player.getUniqueId(), (ignored, value) -> Math.min(capacityNow, value == null ? capacityNow : value));
    }
    double capacity(Player player) { return baseCapacity + bonuses(player).maximumMana(); }
    double regeneration(Player player) { return baseRegeneration + bonuses(player).regenerationPerSecond(); }
    EquipmentManaBonuses.Bonus bonuses(Player player) {
        return EquipmentManaBonuses.read(player, maximumEquipmentCapacityBonus, maximumEquipmentRegenerationBonus);
    }
    boolean consume(Player player, double amount) {
        UUID id = player.getUniqueId(); double current = current(player);
        if (current + 1e-6 < amount) return false;
        mana.put(id, current - amount); return true;
    }
    void refund(Player player, double amount) {
        double capacityNow = capacity(player);
        mana.compute(player.getUniqueId(), (id, value) -> Math.min(capacityNow, (value == null ? capacityNow : value) + amount));
    }
    @Override public void close() { task.cancel(); mana.clear(); }
}
