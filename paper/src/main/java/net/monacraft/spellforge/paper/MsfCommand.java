package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.*;

final class MsfCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;private final FragmentService fragments;private final CircuitService circuits;private final DefaultSpellService spells;private final EditorGui editor;private final ProtocolBridge bridge;private final WebEditorBridge webEditor;private final UpgradeService upgrades;private final NeutralMagicStoneService stones;private final SwordSpellService swords;private final SpellDatabase database;
    MsfCommand(Plugin plugin,FragmentService fragments,CircuitService circuits,DefaultSpellService spells,EditorGui editor,ProtocolBridge bridge,WebEditorBridge webEditor,UpgradeService upgrades,NeutralMagicStoneService stones,SwordSpellService swords,SpellDatabase database){this.plugin=plugin;this.fragments=fragments;this.circuits=circuits;this.spells=spells;this.editor=editor;this.bridge=bridge;this.webEditor=webEditor;this.upgrades=upgrades;this.stones=stones;this.swords=swords;this.database=database;}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==0){help(sender);return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "open","editor"->{if(sender instanceof Player p&&!bridge.openModEditor(p))editor.open(p);else if(!(sender instanceof Player))sender.sendMessage("Player only");}
            case "web"->{if(sender instanceof Player p)webEditor.pair(p);else sender.sendMessage("Player only");}
            case "spell"->spell(sender,args);
            case "fragment"->fragment(sender,args);
            case "stone"->stone(sender,args);
            case "sword"->{if(sender instanceof Player p)swords.openHeld(p);else sender.sendMessage("Player only");}
            case "upgrade"->{if(sender instanceof Player p)upgrades.open(p);else sender.sendMessage("Player only");}
            case "reload"->{if(!sender.hasPermission("monaspellforge.admin.reload"))denied(sender);else{plugin.reloadConfig();sender.sendMessage(Component.text("設定を再読込しました（HMAC/DB変更は再起動後に反映）",NamedTextColor.GREEN));}}
            case "debug"->debug(sender);
            case "admin"->admin(sender,args);
            default->help(sender);
        }return true;
    }
    private void spell(CommandSender sender,String[] args){if(!(sender instanceof Player p)){sender.sendMessage("Player only");return;}if(args.length<2){sender.sendMessage("/msf spell list|cast|validate <id>");return;}if(args[1].equalsIgnoreCase("list")){var list=circuits.getCircuits(p.getUniqueId());p.sendMessage(Component.text("魔術回路: "+list.size(),NamedTextColor.AQUA));list.forEach(c->p.sendMessage(Component.text("- "+c.name()+" "+c.circuitId()+" rev."+c.revision(),NamedTextColor.GRAY)));}else if(args[1].equalsIgnoreCase("cast")){var r=spells.castActive(p,"MANUAL");p.sendMessage(Component.text(r.message(),r.accepted()?NamedTextColor.GREEN:NamedTextColor.RED));}else if(args[1].equalsIgnoreCase("validate")&&args.length>=3){try{var c=circuits.getCircuit(UUID.fromString(args[2])).orElseThrow();var r=circuits.validate(c);p.sendMessage(Component.text(r.valid()?"回路は有効です":"無効: "+r.issues(),r.valid()?NamedTextColor.GREEN:NamedTextColor.RED));}catch(Exception e){p.sendMessage(Component.text("回路が見つかりません",NamedTextColor.RED));}}}
    private void fragment(CommandSender sender,String[] args){if(args.length>=2&&args[1].equalsIgnoreCase("inspect")){if(sender instanceof Player p){var r=fragments.validateItem(p.getInventory().getItemInMainHand(),null);p.sendMessage(Component.text(r.valid()?r.instance().toString():"無効: "+r.reason(),r.valid()?NamedTextColor.GREEN:NamedTextColor.RED));}return;}if(args.length<6||!args[1].equalsIgnoreCase("give")){sender.sendMessage("/msf fragment give <player> <id> <level> <amount> | inspect");return;}if(!sender.hasPermission("monaspellforge.admin.fragment.give")){denied(sender);return;}Player target=Bukkit.getPlayerExact(args[2]);if(target==null){sender.sendMessage("Player not found");return;}try{int level=Integer.parseInt(args[4]),amount=Integer.parseInt(args[5]);for(ItemStack item:fragments.createItems(target,args[3],level,amount,"command:"+sender.getName()))target.getInventory().addItem(item).values().forEach(drop->target.getWorld().dropItemNaturally(target.getLocation(),drop));sender.sendMessage(Component.text(amount+"個の "+args[3]+" を発行しました",NamedTextColor.GREEN));}catch(RuntimeException e){sender.sendMessage(Component.text("発行失敗: "+e.getMessage(),NamedTextColor.RED));}}
    private void stone(CommandSender sender,String[] args){if(args.length==1){if(sender instanceof Player p)stones.openHeld(p);else sender.sendMessage("Player only");return;}if(!args[1].equalsIgnoreCase("give")||args.length<3){sender.sendMessage("/msf stone | /msf stone give <player> [amount]");return;}if(!sender.hasPermission("monaspellforge.admin.stone.give")){denied(sender);return;}Player target=Bukkit.getPlayerExact(args[2]);if(target==null){sender.sendMessage("Player not found");return;}try{int amount=args.length>=4?Integer.parseInt(args[3]):1;for(ItemStack item:stones.createItems(target,amount,"command:"+sender.getName()))target.getInventory().addItem(item).values().forEach(drop->target.getWorld().dropItemNaturally(target.getLocation(),drop));sender.sendMessage(Component.text(amount+"個の無属性の魔石を発行しました",NamedTextColor.GREEN));target.sendMessage(Component.text("無属性の魔石を受け取りました。右クリックで魔術を封入できます",NamedTextColor.AQUA));}catch(RuntimeException e){sender.sendMessage(Component.text("発行失敗: "+e.getMessage(),NamedTextColor.RED));}}
    private void debug(CommandSender sender){if(sender instanceof Player p)sender.sendMessage(Component.text("回路="+circuits.getCircuits(p.getUniqueId()).size()+" active-casts="+spells.getActiveCasts(p.getUniqueId()).size(),NamedTextColor.AQUA));else sender.sendMessage("Player only");}
    private void admin(CommandSender sender,String[] args){if(args.length>=2&&args[1].equalsIgnoreCase("audit")){if(!sender.hasPermission("monaspellforge.admin.audit")){denied(sender);return;}if(args.length<3){sender.sendMessage("/msf admin audit <uuid|player>");return;}try{Player p=Bukkit.getPlayerExact(args[2]);UUID id=p==null?UUID.fromString(args[2]):p.getUniqueId();sender.sendMessage(database.auditSummary(id));}catch(Exception e){sender.sendMessage("Invalid player/UUID");}return;}if(!sender.hasPermission("monaspellforge.admin.stopcasts")){denied(sender);return;}if(args.length>=2&&args[1].equalsIgnoreCase("stopcasts")){if(args.length<3||args[2].equalsIgnoreCase("all"))sender.sendMessage("Stopped: "+spells.cancelAll());else{Player p=Bukkit.getPlayerExact(args[2]);sender.sendMessage("Stopped: "+(p==null?0:spells.cancel(p.getUniqueId())));}}else help(sender);}
    private static void help(CommandSender s){s.sendMessage(Component.text("/msf open | web | spell list|cast | stone | sword | upgrade | fragment give | debug",NamedTextColor.LIGHT_PURPLE));}
    private static void denied(CommandSender s){s.sendMessage(Component.text("権限がありません",NamedTextColor.RED));}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return List.of("open","web","spell","stone","sword","upgrade","fragment","debug","reload","admin");if(args.length==2&&args[0].equalsIgnoreCase("spell"))return List.of("list","cast","validate");if(args.length==2&&args[0].equalsIgnoreCase("fragment"))return List.of("give","inspect");if(args.length==2&&args[0].equalsIgnoreCase("stone")&&s.hasPermission("monaspellforge.admin.stone.give"))return List.of("give");if(args.length==2&&args[0].equalsIgnoreCase("admin"))return List.of("stopcasts","audit");return List.of();}
}
