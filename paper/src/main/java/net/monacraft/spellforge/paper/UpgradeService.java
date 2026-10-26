package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.FragmentService;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class UpgradeService implements Listener {
    private final FragmentService fragments;private final SpellDatabase database;private final net.milkbowl.vault.economy.Economy economy;private final Random random=new java.security.SecureRandom();private final Set<UUID> locks=ConcurrentHashMap.newKeySet();private final org.bukkit.configuration.file.YamlConfiguration cfg;
    UpgradeService(Plugin plugin,FragmentService fragments,SpellDatabase database){this.fragments=fragments;this.database=database;cfg=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(),"upgrade.yml"));var registration=Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);economy=registration==null?null:registration.getProvider();}
    void open(Player p){Holder h=new Holder(p.getUniqueId());Inventory inv=Bukkit.createInventory(h,27,Component.text("魔術欠片の強化",NamedTextColor.DARK_PURPLE));h.inv=inv;for(int i=0;i<27;i++)inv.setItem(i,item(Material.BLACK_STAINED_GLASS_PANE," "));inv.setItem(11,null);inv.setItem(15,item(Material.ANVIL,"強化実行"));p.openInventory(inv);}
    @EventHandler public void click(InventoryClickEvent e){if(!(e.getInventory().getHolder() instanceof Holder h)||!(e.getWhoClicked() instanceof Player p))return;if(!h.owner.equals(p.getUniqueId())){e.setCancelled(true);return;}if(e.getRawSlot()==11)return;e.setCancelled(true);if(e.getRawSlot()==15)upgrade(p,e.getInventory());}
    @EventHandler public void close(InventoryCloseEvent e){if(!(e.getInventory().getHolder() instanceof Holder)||!(e.getPlayer() instanceof Player p))return;ItemStack source=e.getInventory().getItem(11);e.getInventory().setItem(11,null);if(source!=null)p.getInventory().addItem(source).values().forEach(item->p.getWorld().dropItemNaturally(p.getLocation(),item));}
    private void upgrade(Player p,Inventory inv){if(!locks.add(p.getUniqueId()))return;try{ItemStack source=inv.getItem(11);var check=fragments.validateItem(source,p);if(!check.valid()){msg(p,"強化枠に自分の欠片を1個置いてください");return;}int level=check.instance().level();var def=fragments.getDefinition(check.instance().fragmentId());if(level>=def.maxLevel()){msg(p,"この欠片は最大レベルです");return;}int target=level+1;String path="levels."+target;int diamonds=cfg.getInt(path+".diamond-blocks"),emeralds=cfg.getInt(path+".emerald-blocks"),netherite=cfg.getInt(path+".netherite-ingots"),duplicates=cfg.getInt(path+".duplicate-count");double chance=cfg.getDouble(path+".success",1),mona=cfg.getDouble(path+".mona",0);if(count(p,Material.DIAMOND_BLOCK)<diamonds||count(p,Material.EMERALD_BLOCK)<emeralds||count(p,Material.NETHERITE_INGOT)<netherite){msg(p,"強化素材が不足しています");return;}if(mona>0&&(economy==null||!economy.has(p,mona))){msg(p,economy==null?"Vault経済サービスが利用できません":"MONAが不足しています");return;}List<Integer> dup=new ArrayList<>();for(int i=0;i<p.getInventory().getSize()&&dup.size()<duplicates;i++){ItemStack x=p.getInventory().getItem(i);var v=fragments.validateItem(x,p);if(v.valid()&&v.instance().fragmentId().equals(check.instance().fragmentId())&&v.instance().level()==level&&!v.instance().instanceId().equals(check.instance().instanceId()))dup.add(i);}if(dup.size()<duplicates){msg(p,"同種・同レベルの欠片が "+duplicates+" 個必要です");return;}if(mona>0&&!economy.withdrawPlayer(p,mona).transactionSuccess()){msg(p,"MONAの決済に失敗しました");return;}take(p,Material.DIAMOND_BLOCK,diamonds);take(p,Material.EMERALD_BLOCK,emeralds);take(p,Material.NETHERITE_INGOT,netherite);dup.forEach(i->p.getInventory().setItem(i,null));boolean success=random.nextDouble()<=chance;if(success){inv.setItem(11,fragments.createItems(p,check.instance().fragmentId(),target,1,"upgrade").getFirst());msg(p,"強化成功！ Lv."+target);}else msg(p,"強化に失敗しました（レベルは維持）");database.auditUpgrade(UUID.randomUUID(),p.getUniqueId(),check.instance().instanceId(),level,target,success,"{\"mona\":"+(long)mona+",\"duplicates\":"+duplicates+"}");}finally{locks.remove(p.getUniqueId());}}
    private static int count(Player p,Material m){return Arrays.stream(p.getInventory().getContents()).filter(Objects::nonNull).filter(i->i.getType()==m).mapToInt(ItemStack::getAmount).sum();}
    private static void take(Player p,Material m,int n){for(int i=0;i<p.getInventory().getSize()&&n>0;i++){ItemStack x=p.getInventory().getItem(i);if(x==null||x.getType()!=m)continue;int k=Math.min(n,x.getAmount());x.subtract(k);n-=k;}}
    private static ItemStack item(Material m,String n){ItemStack i=new ItemStack(m);ItemMeta meta=i.getItemMeta();meta.displayName(Component.text(n,NamedTextColor.GOLD));i.setItemMeta(meta);return i;}
    private static void msg(Player p,String m){p.sendMessage(Component.text(m,NamedTextColor.YELLOW));}
    private static final class Holder implements InventoryHolder{final UUID owner;Inventory inv;Holder(UUID o){owner=o;}public Inventory getInventory(){return inv;}}
}
