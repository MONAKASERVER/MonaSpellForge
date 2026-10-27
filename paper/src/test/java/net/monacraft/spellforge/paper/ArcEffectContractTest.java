package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.arcsys.vm.ArcVm;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArcEffectContractTest {
    @Test void requiresMatchingCapabilitiesAndRejectsUnknownEffects() {
        ArcEffectContract contract = new ArcEffectContract(new YamlConfiguration());
        var damage = new ArcVm.EffectRequest("combat.damage", Map.of("arguments", List.of("target", 4)));
        assertTrue(contract.validate(List.of(damage), Set.of("combat.damage")).allowed());
        assertEquals("CONTRACT_CAPABILITY", contract.validate(List.of(damage), Set.of()).code());
        assertEquals("CONTRACT_UNKNOWN_EFFECT", contract.validate(
                List.of(new ArcVm.EffectRequest("server.command", Map.of())), Set.of()).code());
    }

    @Test void enforcesPerSegmentDisplayBudgetAtomically() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("arc-runtime.contract.max-displays-per-segment", 1);
        ArcEffectContract contract = new ArcEffectContract(config);
        var effect = new ArcVm.EffectRequest("display.text", Map.of("arguments", List.of("x")));
        assertEquals("CONTRACT_DISPLAY_BUDGET",
                contract.validate(List.of(effect, effect), Set.of("visual.display")).code());
    }
}
