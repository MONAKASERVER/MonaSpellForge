package net.monacraft.spellforge.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Prevents plugin-authored TRUE_DAMAGE from being counted as held-weapon mcMMO experience. */
final class McMmoExperienceGuard {
    private static final String XP_EVENT = "com.gmail.nossr50.events.experience.McMMOPlayerXpGainEvent";

    private final Plugin plugin;
    private final ThreadLocal<Map<UUID, Integer>> suppressed = ThreadLocal.withInitial(HashMap::new);

    McMmoExperienceGuard(Plugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unchecked")
    void register() {
        Plugin mcMmo = Bukkit.getPluginManager().getPlugin("mcMMO");
        if (mcMmo == null || !mcMmo.isEnabled()) return;
        try {
            Class<?> raw = Class.forName(XP_EVENT, false, mcMmo.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) return;
            EventExecutor executor = (listener, event) -> {
                if (raw.isInstance(event)) suppressExperience(event);
            };
            Bukkit.getPluginManager().registerEvent((Class<? extends Event>) raw, new org.bukkit.event.Listener() { },
                    EventPriority.LOWEST, executor, plugin, false);
            plugin.getLogger().info("mcMMO integration: TRUE_DAMAGE weapon experience suppression enabled");
        } catch (ClassNotFoundException | LinkageError error) {
            plugin.getLogger().warning("mcMMO integration could not be enabled: " + error.getMessage());
        }
    }

    void runWithoutExperience(Player source, Runnable damageAction) {
        UUID playerId = source.getUniqueId();
        Map<UUID, Integer> active = suppressed.get();
        active.merge(playerId, 1, Integer::sum);
        try {
            damageAction.run();
        } finally {
            int remaining = active.getOrDefault(playerId, 1) - 1;
            if (remaining <= 0) active.remove(playerId);
            else active.put(playerId, remaining);
            if (active.isEmpty()) suppressed.remove();
        }
    }

    boolean isSuppressed(UUID playerId) {
        return suppressed.get().getOrDefault(playerId, 0) > 0;
    }

    private void suppressExperience(Event event) {
        try {
            Method playerGetter = event.getClass().getMethod("getPlayer");
            Object player = playerGetter.invoke(event);
            if (!(player instanceof Player source) || !isSuppressed(source.getUniqueId())) return;
            event.getClass().getMethod("setRawXpGained", float.class).invoke(event, 0.0F);
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
        } catch (ReflectiveOperationException error) {
            plugin.getLogger().warning("Failed to suppress mcMMO TRUE_DAMAGE experience: " + error.getMessage());
        }
    }
}
