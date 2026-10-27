package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.catalog.FragmentPresentation;
import net.monacraft.spellforge.model.SpellModel.FragmentDefinition;
import net.monacraft.spellforge.model.SpellModel.FragmentInstance;
import net.monacraft.spellforge.model.SpellModel.LevelParameters;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.*;

final class DefaultFragmentService implements FragmentService {
    private static final int SCHEMA = 1;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private final FragmentCatalog catalog;
    private final IntegrityService integrity;
    private final SpellDatabase database;
    private final NamespacedKey schemaKey, idKey, levelKey, instanceKey, ownerKey, issuerKey, issuedAtKey, integrityKey;

    DefaultFragmentService(Plugin plugin, FragmentCatalog catalog, IntegrityService integrity, SpellDatabase database) {
        this.catalog = catalog; this.integrity = integrity; this.database = database;
        schemaKey = new NamespacedKey(plugin, "schema_version"); idKey = new NamespacedKey(plugin, "fragment_id");
        levelKey = new NamespacedKey(plugin, "level"); instanceKey = new NamespacedKey(plugin, "instance_uuid");
        ownerKey = new NamespacedKey(plugin, "owner_uuid"); issuerKey = new NamespacedKey(plugin, "issuer");
        issuedAtKey = new NamespacedKey(plugin, "issued_at"); integrityKey = new NamespacedKey(plugin, "integrity_tag");
    }

    @Override public List<ItemStack> createItems(OfflinePlayer owner, String fragmentId, int level, int amount, String source) {
        FragmentDefinition definition;
        try { definition = catalog.get(fragmentId); } catch (RuntimeException ignored) { definition = null; }
        if (definition == null || level < 1 || level > definition.maxLevel()) throw new IllegalArgumentException("Unknown fragment or level");
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Amount must be 1..64");
        List<ItemStack> result = new ArrayList<>(amount);
        for (int index = 0; index < amount; index++) {
            UUID instanceId = UUID.randomUUID(); Instant issuedAt = Instant.now(); String issuer = Objects.requireNonNullElse(source, "api");
            FragmentInstance instance = new FragmentInstance(instanceId, definition.id(), level, owner.getUniqueId(), issuer, issuedAt);
            ItemStack item = new ItemStack(Optional.ofNullable(Material.matchMaterial(definition.displayItem())).orElse(Material.AMETHYST_SHARD));
            ItemMeta meta = item.getItemMeta();
            applyAppearance(meta, definition, level, instanceId);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            var pdc = meta.getPersistentDataContainer();
            long millis = issuedAt.toEpochMilli();
            pdc.set(schemaKey, PersistentDataType.INTEGER, SCHEMA); pdc.set(idKey, PersistentDataType.STRING, definition.id());
            pdc.set(levelKey, PersistentDataType.INTEGER, level); pdc.set(instanceKey, PersistentDataType.STRING, instanceId.toString());
            pdc.set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString()); pdc.set(issuerKey, PersistentDataType.STRING, issuer);
            pdc.set(issuedAtKey, PersistentDataType.LONG, millis);
            pdc.set(integrityKey, PersistentDataType.STRING, integrity.sign(SCHEMA, instanceId, definition.id(), level, owner.getUniqueId(), issuer, millis));
            item.setItemMeta(meta);
            try { database.recordIssuance(instance, source); } catch (Exception error) { throw new IllegalStateException("Could not persist issued fragment", error); }
            result.add(item);
        }
        return result;
    }

    @Override public ValidationResult validateItem(ItemStack item, OfflinePlayer expectedOwner) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return ValidationResult.failure("NOT_FRAGMENT");
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Integer schema = pdc.get(schemaKey, PersistentDataType.INTEGER); String id = pdc.get(idKey, PersistentDataType.STRING);
        Integer level = pdc.get(levelKey, PersistentDataType.INTEGER); String instanceRaw = pdc.get(instanceKey, PersistentDataType.STRING);
        String ownerRaw = pdc.get(ownerKey, PersistentDataType.STRING); String issuer = pdc.get(issuerKey, PersistentDataType.STRING);
        Long issuedAt = pdc.get(issuedAtKey, PersistentDataType.LONG); String tag = pdc.get(integrityKey, PersistentDataType.STRING);
        if (schema == null || id == null || level == null || instanceRaw == null || ownerRaw == null || issuer == null || issuedAt == null) return ValidationResult.failure("MISSING_PDC");
        try {
            UUID instanceId = UUID.fromString(instanceRaw), ownerId = UUID.fromString(ownerRaw);
            FragmentDefinition definition = catalog.get(id);
            if (definition == null || level < 1 || level > definition.maxLevel()) return ValidationResult.failure("DEFINITION_OR_LEVEL");
            if (expectedOwner != null && !ownerId.equals(expectedOwner.getUniqueId())) return ValidationResult.failure("OWNER_MISMATCH");
            if (!integrity.verify(tag, schema, instanceId, definition.id(), level, ownerId, issuer, issuedAt)) return ValidationResult.failure("SIGNATURE_INVALID");
            FragmentInstance instance = new FragmentInstance(instanceId, definition.id(), level, ownerId, issuer, Instant.ofEpochMilli(issuedAt));
            if (!database.isIssued(instance) && !database.recoverSignedIssuance(instance)) {
                return ValidationResult.failure("NOT_IN_ISSUANCE_LEDGER");
            }
            return ValidationResult.success(instance);
        } catch (RuntimeException error) { return ValidationResult.failure("MALFORMED_PDC"); }
    }

    @Override public FragmentDefinition getDefinition(String fragmentId) { return catalog.get(fragmentId); }
    @Override public int getLevel(ItemStack item) { ValidationResult result = validateItem(item, null); return result.valid() ? result.instance().level() : 0; }

    UUID instanceIdUnchecked(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String raw = item.getItemMeta().getPersistentDataContainer().get(instanceKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try { return UUID.fromString(raw); } catch (IllegalArgumentException ignored) { return null; }
    }

    @Override public void refreshAppearance(ItemStack item) {
        ValidationResult result = validateItem(item, null);
        if (!result.valid()) return;
        FragmentInstance instance = result.instance();
        FragmentDefinition definition = catalog.get(instance.fragmentId());
        ItemMeta meta = item.getItemMeta();
        applyAppearance(meta, definition, instance.level(), instance.instanceId());
        item.setItemMeta(meta);
    }

    private static void applyAppearance(ItemMeta meta, FragmentDefinition definition, int level, UUID instanceId) {
        FragmentPresentation.Entry presentation = FragmentPresentation.get(definition.id());
        meta.displayName(LEGACY.deserialize("&8【&5魔術欠片&8】 " + presentation.legacyName()));
        LevelParameters parameters = definition.levels().get(level);
        meta.lore(List.of(
                LEGACY.deserialize("&7原典識別: &f" + definition.id()),
                LEGACY.deserialize("&b階位: &fLv." + level + " &8/ &7" + definition.maxLevel()),
                LEGACY.deserialize("&6系統: &f" + FragmentPresentation.categoryName(definition.category())),
                LEGACY.deserialize("&7" + presentation.description()),
                LEGACY.deserialize("&9消費魔力: &f" + (int) Math.ceil(parameters.manaCost()) + " &8| &e実行負荷: &f" + parameters.runtimeCost()),
                LEGACY.deserialize("&8個体識別: " + instanceId.toString().substring(0, 8))
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
    }

}
