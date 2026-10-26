package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/** One owner-bound spell slot for every vanilla or custom sword material. */
final class SwordSpellService implements Listener {
    private static final int SCHEMA = 1;
    private final CircuitService circuits;
    private final DefaultSpellService spells;
    private final IntegrityService integrity;
    private final NamespacedKey instanceKey,ownerKey,circuitKey,revisionKey,signatureKey;

    SwordSpellService(Plugin plugin,CircuitService circuits,DefaultSpellService spells,IntegrityService integrity){
        this.circuits=circuits;this.spells=spells;this.integrity=integrity;
        instanceKey=new NamespacedKey(plugin,"sword_spell_instance");ownerKey=new NamespacedKey(plugin,"sword_spell_owner");
        circuitKey=new NamespacedKey(plugin,"sword_spell_circuit");revisionKey=new NamespacedKey(plugin,"sword_spell_revision");
        signatureKey=new NamespacedKey(plugin,"sword_spell_integrity");
    }

    void openHeld(Player player){
        ItemStack held=player.getInventory().getItemInMainHand();
        if(!isSword(held)){player.sendMessage(Component.text("魔術を埋め込む剣をメインハンドに持ってください",NamedTextColor.RED));return;}
        List<Circuit> saved=circuits.getCircuits(player.getUniqueId());
        if(saved.isEmpty()){player.sendMessage(Component.text("先に /msf open で魔術を保存してください",NamedTextColor.RED));return;}
        SwordPicker holder=new SwordPicker(player.getUniqueId());
        Inventory inventory=Bukkit.createInventory(holder,54,Component.text("剣の魔術スロット（1枠）",NamedTextColor.DARK_AQUA));holder.inventory=inventory;
        for(int slot=0;slot<Math.min(45,saved.size());slot++){
            Circuit circuit=saved.get(slot);ItemStack icon=new ItemStack(Material.ENCHANTED_BOOK);ItemMeta meta=icon.getItemMeta();
            meta.displayName(Component.text(circuit.name(),NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("右クリック発動として埋め込む",NamedTextColor.GRAY),Component.text("既存スロットは上書きされます",NamedTextColor.DARK_GRAY)));
            icon.setItemMeta(meta);inventory.setItem(slot,icon);
        }
        player.openInventory(inventory);
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getInventory().getHolder() instanceof SwordPicker holder)||!(event.getWhoClicked() instanceof Player player))return;
        event.setCancelled(true);if(!holder.owner.equals(player.getUniqueId())||event.getRawSlot()<0||event.getRawSlot()>=45)return;
        List<Circuit> saved=circuits.getCircuits(player.getUniqueId());if(event.getRawSlot()>=saved.size())return;
        ItemStack held=player.getInventory().getItemInMainHand();if(!isSword(held)){player.closeInventory();player.sendMessage(Component.text("剣を持ち続けてください",NamedTextColor.RED));return;}
        bind(held,player,saved.get(event.getRawSlot()));player.getInventory().setItemInMainHand(held);player.closeInventory();
        player.sendMessage(Component.text("剣の魔術スロットへ「"+saved.get(event.getRawSlot()).name()+"」を埋め込みました",NamedTextColor.GREEN));
        player.playSound(player.getLocation(),Sound.BLOCK_ENCHANTMENT_TABLE_USE,1f,1.2f);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false) public void interact(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND||(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        ItemStack sword=event.getPlayer().getInventory().getItemInMainHand();Binding binding=validate(sword,event.getPlayer());if(binding==null)return;
        event.setCancelled(true);Circuit circuit=circuits.getCircuit(binding.circuitId).orElse(null);
        if(circuit==null){event.getPlayer().sendMessage(Component.text("埋め込まれた魔術回路が見つかりません",NamedTextColor.RED));return;}
        var result=spells.castEmbedded(event.getPlayer(),circuit,"MANUAL");if(!result.accepted())event.getPlayer().sendMessage(Component.text(result.message(),NamedTextColor.RED));
    }

    boolean isBoundSword(ItemStack item){return item!=null&&item.hasItemMeta()&&item.getItemMeta().getPersistentDataContainer().has(circuitKey,PersistentDataType.STRING);}
    private boolean isSword(ItemStack item){return item!=null&&!item.getType().isAir()&&item.getType().name().endsWith("_SWORD");}
    private void bind(ItemStack sword,Player owner,Circuit circuit){
        ItemMeta meta=sword.getItemMeta();var pdc=meta.getPersistentDataContainer();String instance=pdc.get(instanceKey,PersistentDataType.STRING);
        if(instance==null)instance=UUID.randomUUID().toString();
        pdc.set(instanceKey,PersistentDataType.STRING,instance);pdc.set(ownerKey,PersistentDataType.STRING,owner.getUniqueId().toString());
        pdc.set(circuitKey,PersistentDataType.STRING,circuit.circuitId().toString());pdc.set(revisionKey,PersistentDataType.LONG,circuit.revision());
        pdc.set(signatureKey,PersistentDataType.STRING,integrity.signPayload("sword-spell-v1",SCHEMA,instance,owner.getUniqueId(),circuit.circuitId(),circuit.revision()));
        List<Component> lore=meta.lore()==null?new java.util.ArrayList<>():new java.util.ArrayList<>(meta.lore());
        lore.removeIf(line->net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line).startsWith("魔術スロット:"));
        lore.add(Component.text("魔術スロット: ",NamedTextColor.DARK_AQUA).append(Component.text(circuit.name(),NamedTextColor.AQUA)));meta.lore(lore);sword.setItemMeta(meta);
    }
    private Binding validate(ItemStack sword,Player player){
        if(!isSword(sword)||!sword.hasItemMeta())return null;var pdc=sword.getItemMeta().getPersistentDataContainer();
        String instance=pdc.get(instanceKey,PersistentDataType.STRING),owner=pdc.get(ownerKey,PersistentDataType.STRING),circuit=pdc.get(circuitKey,PersistentDataType.STRING),signature=pdc.get(signatureKey,PersistentDataType.STRING);Long revision=pdc.get(revisionKey,PersistentDataType.LONG);
        if(instance==null||owner==null||circuit==null||signature==null||revision==null)return null;
        try{UUID ownerId=UUID.fromString(owner),circuitId=UUID.fromString(circuit);if(!ownerId.equals(player.getUniqueId()))return null;
            return integrity.verifyPayload(signature,"sword-spell-v1",SCHEMA,instance,ownerId,circuitId,revision)?new Binding(circuitId):null;
        }catch(RuntimeException ignored){return null;}
    }
    private record Binding(UUID circuitId){}
    private static final class SwordPicker implements InventoryHolder{final UUID owner;Inventory inventory;SwordPicker(UUID owner){this.owner=owner;}@Override public Inventory getInventory(){return inventory;}}
}
