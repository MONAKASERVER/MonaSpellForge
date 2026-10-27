package net.monacraft.spellforge.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * The only outbound ArcSys -> Skript boundary. Skript addons may listen to this
 * Bukkit event, but ArcSys can never execute source text, commands or arbitrary
 * Skript functions.
 */
public final class ArcSkriptBridgeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String signal;
    private final Map<String, Object> payload;

    ArcSkriptBridgeEvent(Player player, String signal, Map<String, Object> payload) {
        this.player = player;
        this.signal = signal;
        this.payload = Map.copyOf(payload);
    }

    public Player getPlayer() { return player; }
    public String getSignal() { return signal; }
    public Map<String, Object> getPayload() { return payload; }
    @Override public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
