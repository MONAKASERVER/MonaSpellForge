package net.monacraft.spellforge.api;

import net.monacraft.spellforge.model.SpellModel.FragmentDefinition;
import net.monacraft.spellforge.model.SpellModel.FragmentInstance;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public interface FragmentService {
    List<ItemStack> createItems(OfflinePlayer owner, String fragmentId, int level, int amount, String source);
    default List<ItemStack> createItem(OfflinePlayer owner, String fragmentId, int level, int amount, String source) {
        return createItems(owner, fragmentId, level, amount, source);
    }
    ValidationResult validateItem(ItemStack item, OfflinePlayer expectedOwner);
    FragmentDefinition getDefinition(String fragmentId);
    int getLevel(ItemStack item);
    void refreshAppearance(ItemStack item);

    record ValidationResult(boolean valid, String reason, FragmentInstance instance) {
        public static ValidationResult failure(String reason) { return new ValidationResult(false, reason, null); }
        public static ValidationResult success(FragmentInstance instance) { return new ValidationResult(true, "OK", instance); }
    }
}
