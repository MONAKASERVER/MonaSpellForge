package net.monacraft.spellforge.paper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;

import java.lang.reflect.Method;
import java.util.Locale;

/** Optional integrations are isolated and fail closed for protected mutations. */
final class ArcProtectionAdapters {
    private final boolean worldGuardPresent = Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
    private final boolean mythicPresent = Bukkit.getPluginManager().isPluginEnabled("MythicMobs");

    boolean canBuild(Player player, Location location) {
        if (!worldGuardPresent) return true;
        try {
            Class<?> wg = Class.forName("com.sk89q.worldguard.WorldGuard");
            Object instance = wg.getMethod("getInstance").invoke(null);
            Object platform = instance.getClass().getMethod("getPlatform").invoke(instance);
            Object matcher = platform.getClass().getMethod("getSessionManager").invoke(platform);
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object localPlayer = adapter.getMethod("adapt", Player.class).invoke(null, player);
            Object localLocation = adapter.getMethod("adapt", Location.class).invoke(null, location);
            for (Method method : matcher.getClass().getMethods()) {
                if (!method.getName().equals("testBuild") || method.getParameterCount() != 2) continue;
                return (boolean) method.invoke(matcher, localPlayer, localLocation);
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    String mythicType(Entity entity) {
        if (!mythicPresent) return "";
        try {
            Class<?> mythic = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object instance = mythic.getMethod("inst").invoke(null);
            Object manager = instance.getClass().getMethod("getMobManager").invoke(instance);
            Object optional = manager.getClass().getMethod("getActiveMob", java.util.UUID.class).invoke(manager, entity.getUniqueId());
            if (optional instanceof java.util.Optional<?> value && value.isPresent()) {
                Object activeMob = value.get(); Object type = activeMob.getClass().getMethod("getMobType").invoke(activeMob);
                String id = String.valueOf(type); if (!id.isBlank()) return safeId(id);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        for (String key : new String[]{"MythicMob", "mythicmob", "MYTHIC_MOB"}) {
            for (MetadataValue value : entity.getMetadata(key)) {
                String text = value.asString();
                if (!text.isBlank()) return safeId(text);
            }
        }
        return entity.getScoreboardTags().stream().filter(tag -> tag.toLowerCase(Locale.ROOT).startsWith("mythic:"))
                .map(tag -> safeId(tag.substring(7))).findFirst().orElse("");
    }

    private static String safeId(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "");
        return normalized.length() > 96 ? normalized.substring(0, 96) : normalized;
    }
}
