package net.monacraft.spellforge.paper;

import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.api.SpellService;
import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Base64;

public final class MonaSpellForgePlugin extends JavaPlugin {
    private SpellDatabase database;
    private ManaService mana;
    private StatusHud hud;
    private DefaultSpellService spells;
    private EditorGui editor;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            for (String name : new String[]{"runtime.yml","upgrade.yml","integrations.yml","messages_ja.yml","messages_en.yml"}) saveResourceIfMissing(name);
            ensureSecret();
            FragmentCatalog catalog = FragmentCatalog.defaults();
            CircuitCompiler compiler = new CircuitCompiler(catalog);
            database = new SpellDatabase(getDataFolder().toPath().resolve(getConfig().getString("database.file","monaspellforge.sqlite")));
            IntegrityService integrity = new IntegrityService(getConfig().getString("security.hmac-secret"));
            DefaultFragmentService fragments = new DefaultFragmentService(this,catalog,integrity,database);
            DefaultCircuitService circuits = new DefaultCircuitService(database,compiler);
            CircuitEmbeddingService embedding = new CircuitEmbeddingService(circuits,fragments,database);
            boolean equipmentMana = getConfig().getBoolean("equipment-mana.enabled", true);
            mana = new ManaService(this,getConfig().getDouble("runtime.mana-capacity",1000),
                    getConfig().getDouble("runtime.mana-regeneration-per-second",10),
                    equipmentMana ? getConfig().getDouble("equipment-mana.maximum-mp-bonus",100000) : 0,
                    equipmentMana ? getConfig().getDouble("equipment-mana.maximum-mps-bonus",10000) : 0);
            if (getConfig().getBoolean("hud.enabled", true)) hud = new StatusHud(this, mana,
                    getConfig().getLong("hud.update-interval-ticks", 10), getConfig().getDouble("hud.vanilla-health-scale", 20.0));
            spells = new DefaultSpellService(this,database,compiler,mana,new TickBudgetManager(getConfig().getDouble("runtime.minimum-tps",17),getConfig().getInt("runtime.max-runtime-cost",512)));
            editor = new EditorGui(fragments,circuits,embedding,spells);
            UpgradeService upgrades = new UpgradeService(this,fragments,database);
            ProtocolBridge bridge = new ProtocolBridge(this,circuits,fragments,embedding,database,spells);
            NeutralMagicStoneService stones = new NeutralMagicStoneService(this,circuits,spells,database,integrity);
            FragmentStorageService fragmentStorage = new FragmentStorageService(fragments,database);
            SwordSpellService swords = new SwordSpellService(this,circuits,spells,integrity);

            Bukkit.getServicesManager().register(FragmentService.class,fragments,this,ServicePriority.Normal);
            Bukkit.getServicesManager().register(CircuitService.class,circuits,this,ServicePriority.Normal);
            Bukkit.getServicesManager().register(SpellService.class,spells,this,ServicePriority.Normal);
            Bukkit.getPluginManager().registerEvents(editor,this);
            Bukkit.getPluginManager().registerEvents(stones,this);
            Bukkit.getPluginManager().registerEvents(fragmentStorage,this);
            Bukkit.getPluginManager().registerEvents(swords,this);
            Bukkit.getPluginManager().registerEvents(new SpellTriggerListener(spells,stones,swords,fragments),this);
            Bukkit.getPluginManager().registerEvents(upgrades,this);
            getServer().getMessenger().registerIncomingPluginChannel(this,ProtocolBridge.CHANNEL,bridge);
            getServer().getMessenger().registerOutgoingPluginChannel(this,ProtocolBridge.CHANNEL);
            MsfCommand commands=new MsfCommand(this,fragments,circuits,spells,editor,bridge,upgrades,stones,swords,database);
            getCommand("msf").setExecutor(commands);getCommand("msf").setTabCompleter(commands);
            Bukkit.getScheduler().runTaskTimer(this,()->editor.cleanup(getConfig().getLong("editor.session-timeout-seconds",900)*1000L),1200L,1200L);
            getLogger().info("MonaSpellForge enabled: 69 fragments, protocol v1, database ready");
        } catch (Exception error) {
            getLogger().severe("MonaSpellForge could not start: "+error.getMessage());error.printStackTrace();Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable(){if(spells!=null)spells.cancelAll();if(hud!=null)hud.close();if(mana!=null)mana.close();if(database!=null)try{database.close();}catch(Exception e){getLogger().warning("DB close failed: "+e.getMessage());}}
    private void saveResourceIfMissing(String name){if(!new File(getDataFolder(),name).exists())saveResource(name,false);}
    private void ensureSecret() throws Exception {
        if(!getConfig().getString("security.hmac-secret","").isBlank())return;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);getConfig().set("security.hmac-secret",Base64.getEncoder().encodeToString(bytes));saveConfig();
        // The production SFTP/deployment user is in the trusted minecraft group.
        // Keep the secret hidden from all other users while allowing that group to maintain config.yml.
        try{Files.setPosixFilePermissions(getDataFolder().toPath().resolve("config.yml"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw----"));}catch(UnsupportedOperationException ignored){}
    }
}
