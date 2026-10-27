package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.FragmentService;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/** Converts a signed physical fragment into an owner-bound database entry. */
final class FragmentStorageService implements Listener {
    private final DefaultFragmentService fragments;
    private final SpellDatabase database;

    FragmentStorageService(DefaultFragmentService fragments, SpellDatabase database) {
        this.fragments = fragments;
        this.database = database;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
        FragmentService.ValidationResult validation = fragments.validateItem(held, event.getPlayer());
        if (!validation.valid()) return;
        event.setCancelled(true);

        SpellDatabase.StoreFragmentResult result = database.storeFragment(validation.instance());
        if (!result.stored()) {
            String message = result.code().equals("LIMIT")
                    ? "この種類の欠片は保管上限255個です"
                    : "この欠片は既に保管・封入済みのため使用できません";
            event.getPlayer().sendMessage(Component.text(message, NamedTextColor.RED));
            return;
        }

        if (held.getAmount() <= 1) {
            event.getPlayer().getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        } else {
            held.setAmount(held.getAmount() - 1);
            event.getPlayer().getInventory().setItemInMainHand(held);
        }
        event.getPlayer().sendActionBar(Component.text(
                validation.instance().fragmentId() + " を所持ノードへ格納しました（" + result.count() + "/255）",
                NamedTextColor.AQUA));
        event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f);
    }
}
