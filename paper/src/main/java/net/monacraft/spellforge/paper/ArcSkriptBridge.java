package net.monacraft.spellforge.paper;

import org.bukkit.entity.Player;

/**
 * Allowlisted inbound Skript/SkBee integration point. Obtain it from Bukkit's
 * ServicesManager. Calls must be made on the primary server thread.
 */
public interface ArcSkriptBridge {
    boolean isAllowed(String signal);
    int emitSignal(Player player, String signal, Object payload);
}
