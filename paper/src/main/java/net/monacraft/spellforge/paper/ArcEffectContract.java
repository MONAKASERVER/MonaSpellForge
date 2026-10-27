package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.arcsys.vm.ArcVm;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.*;

/** Server-authoritative contract for every ArcVM effect segment. */
final class ArcEffectContract {
    record Decision(boolean allowed, String code, String message) {
        static Decision allow() { return new Decision(true, "OK", ""); }
        static Decision deny(String code, String message) { return new Decision(false, code, message); }
    }

    private static final Map<String, String> REQUIRED_CAPABILITY = Map.ofEntries(
            Map.entry("mana.consume", "mana.consume"), Map.entry("state.set", "state.write"),
            Map.entry("combat.damage", "combat.damage"), Map.entry("combat.apply", "combat.effect"),
            Map.entry("world.particle", "visual.particle"), Map.entry("particles.spiral", "visual.particle"),
            Map.entry("particles.ring", "visual.particle"), Map.entry("particles.sphere", "visual.particle"),
            Map.entry("display.magic_circle", "visual.display"), Map.entry("display.item", "visual.display"),
            Map.entry("display.text", "visual.display"), Map.entry("pdc.set", "storage.write"),
            Map.entry("skript.emit", "operator.skript.bridge"), Map.entry("event.emit", "event.emit")
    );

    private final int maxEffectsPerSegment;
    private final int maxDisplaysPerSegment;
    private final int maxPdcWritesPerSegment;
    private final int maxSkriptSignalsPerSegment;

    ArcEffectContract(FileConfiguration config) {
        maxEffectsPerSegment = bounded(config.getInt("arc-runtime.contract.max-effects-per-segment", 64), 1, 256);
        maxDisplaysPerSegment = bounded(config.getInt("arc-runtime.contract.max-displays-per-segment", 8), 0, 32);
        maxPdcWritesPerSegment = bounded(config.getInt("arc-runtime.contract.max-pdc-writes-per-segment", 8), 0, 32);
        maxSkriptSignalsPerSegment = bounded(config.getInt("arc-runtime.contract.max-skript-signals-per-segment", 4), 0, 16);
    }

    Decision validate(List<ArcVm.EffectRequest> effects, Set<String> capabilities) {
        if (effects.size() > maxEffectsPerSegment) return Decision.deny("CONTRACT_EFFECT_BUDGET", "効果数が契約上限を超えました");
        int displays = 0, pdc = 0, skript = 0;
        for (ArcVm.EffectRequest effect : effects) {
            String required = REQUIRED_CAPABILITY.get(effect.type());
            if (required == null) return Decision.deny("CONTRACT_UNKNOWN_EFFECT", "未許可の効果です: " + effect.type());
            if (!capabilities.contains(required)) return Decision.deny("CONTRACT_CAPABILITY", "Capability宣言と効果が一致しません");
            if (effect.type().startsWith("display.")) displays++;
            if (effect.type().equals("pdc.set")) pdc++;
            if (effect.type().equals("skript.emit")) skript++;
        }
        if (displays > maxDisplaysPerSegment) return Decision.deny("CONTRACT_DISPLAY_BUDGET", "Display生成数が上限を超えました");
        if (pdc > maxPdcWritesPerSegment) return Decision.deny("CONTRACT_PDC_BUDGET", "PDC書込数が上限を超えました");
        if (skript > maxSkriptSignalsPerSegment) return Decision.deny("CONTRACT_SKRIPT_BUDGET", "Skript通知数が上限を超えました");
        return Decision.allow();
    }

    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
