package net.monacraft.spellforge.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SpellTriggerListener implements Listener {
    private final DefaultSpellService spells;private final NeutralMagicStoneService stones;private final SwordSpellService swords;private final DefaultFragmentService fragments;private final Map<String,Long> debounce=new ConcurrentHashMap<>();
    SpellTriggerListener(DefaultSpellService spells,NeutralMagicStoneService stones,SwordSpellService swords,DefaultFragmentService fragments){this.spells=spells;this.stones=stones;this.swords=swords;this.fragments=fragments;}
    private void cast(Player p,String trigger){String key=p.getUniqueId()+":"+trigger;long now=System.currentTimeMillis();if(now-debounce.getOrDefault(key,0L)<250)return;debounce.put(key,now);spells.castActive(p,trigger);}
    @EventHandler(ignoreCancelled=false,priority=EventPriority.MONITOR) public void interact(PlayerInteractEvent e){if(e.getHand()==org.bukkit.inventory.EquipmentSlot.HAND&&(e.getAction()==Action.RIGHT_CLICK_AIR||e.getAction()==Action.RIGHT_CLICK_BLOCK)&&fragments.instanceIdUnchecked(e.getItem())==null&&!stones.isStone(e.getItem())&&!swords.isBoundSword(e.getItem()))cast(e.getPlayer(),"RIGHT_CLICK");}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void sneak(PlayerToggleSneakEvent e){if(e.isSneaking())cast(e.getPlayer(),"SNEAK");}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p)cast(p,"DAMAGE_TAKEN");}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void land(PlayerMoveEvent e){Player p=e.getPlayer();if(e.getFrom().getY()!=e.getTo().getY()&&p.isOnGround()&&p.getFallDistance()>2)cast(p,"LAND");}
    @EventHandler(ignoreCancelled=true,priority=EventPriority.MONITOR) public void projectile(ProjectileHitEvent e){if(e.getEntity().getShooter() instanceof Player p)cast(p,"PROJECTILE_HIT");}
    @EventHandler(priority=EventPriority.MONITOR) public void kill(EntityDeathEvent e){if(e.getEntity().getKiller() instanceof Player p)cast(p,"KILL_CONFIRM");}
    @EventHandler public void quit(PlayerQuitEvent e){spells.cancel(e.getPlayer().getUniqueId());debounce.keySet().removeIf(k->k.startsWith(e.getPlayer().getUniqueId().toString()));}
}
