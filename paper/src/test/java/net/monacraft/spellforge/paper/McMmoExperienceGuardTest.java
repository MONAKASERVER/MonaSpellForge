package net.monacraft.spellforge.paper;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class McMmoExperienceGuardTest {
    @Test
    void suppressionIsScopedNestedAndAlwaysCleared() {
        McMmoExperienceGuard guard = new McMmoExperienceGuard(null);
        UUID playerId = UUID.randomUUID();
        PlayerStub player = new PlayerStub(playerId);

        assertFalse(guard.isSuppressed(playerId));
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                guard.runWithoutExperience(player.player(), () -> {
                    assertTrue(guard.isSuppressed(playerId));
                    guard.runWithoutExperience(player.player(), () -> assertTrue(guard.isSuppressed(playerId)));
                    assertTrue(guard.isSuppressed(playerId));
                    throw new RuntimeException("test");
                }));

        assertEquals("test", failure.getMessage());
        assertFalse(guard.isSuppressed(playerId));
    }

    private record PlayerStub(UUID id) {
        org.bukkit.entity.Player player() {
            return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class},
                    (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
        }
    }
}
