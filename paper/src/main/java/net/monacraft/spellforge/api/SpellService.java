package net.monacraft.spellforge.api;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.UUID;

public interface SpellService {
    CastResult cast(Player player, UUID circuitId, String trigger);
    int cancel(UUID playerId);
    int cancelAll();
    Collection<UUID> getActiveCasts(UUID playerId);
    record CastResult(boolean accepted, String code, String message, UUID castId) {}
}
