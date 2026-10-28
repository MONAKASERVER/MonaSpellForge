package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
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
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Signed, transferable, five-use bearer container for a snapshot of one saved spell. */
final class NeutralMagicStoneService implements Listener {
    private static final int SCHEMA = 1;
    private static final int MAX_USES = 5;
    private static final String TYPE = "NEUTRAL_MAGIC_STONE";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final CircuitService circuits;
    private final DefaultSpellService spells;
    private final SpellDatabase database;
    private final IntegrityService integrity;
    private final NamespacedKey typeKey;
    private final NamespacedKey schemaKey;
    private final NamespacedKey instanceKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey circuitKey;
    private final NamespacedKey revisionKey;
    private final NamespacedKey usesKey;
    private final NamespacedKey issuedAtKey;
    private final NamespacedKey integrityKey;
    private final Map<UUID, Long> debounce = new ConcurrentHashMap<>();

    NeutralMagicStoneService(Plugin plugin, CircuitService circuits, DefaultSpellService spells,
                             SpellDatabase database, IntegrityService integrity) {
        this.circuits = circuits;
        this.spells = spells;
        this.database = database;
        this.integrity = integrity;
        typeKey = new NamespacedKey(plugin, "stone_type");
        schemaKey = new NamespacedKey(plugin, "stone_schema");
        instanceKey = new NamespacedKey(plugin, "stone_instance");
        ownerKey = new NamespacedKey(plugin, "stone_owner");
        circuitKey = new NamespacedKey(plugin, "stone_circuit");
        revisionKey = new NamespacedKey(plugin, "stone_revision");
        usesKey = new NamespacedKey(plugin, "stone_uses");
        issuedAtKey = new NamespacedKey(plugin, "stone_issued_at");
        integrityKey = new NamespacedKey(plugin, "stone_integrity");
    }

    List<ItemStack> createItems(Player owner, int amount, String issuer) {
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Amount must be 1..64");
        List<ItemStack> result = new ArrayList<>(amount);
        for (int index = 0; index < amount; index++) {
            UUID instance = UUID.randomUUID();
            long issuedAt = System.currentTimeMillis();
            String source = Objects.requireNonNullElse(issuer, "api");
            database.registerNeutralStone(instance, owner.getUniqueId(), MAX_USES, source, issuedAt);
            SpellDatabase.NeutralStoneRecord record = database.neutralStone(instance).orElseThrow();
            ItemStack item = new ItemStack(Material.ECHO_SHARD);
            applyAppearance(item, record);
            result.add(item);
        }
        return result;
    }

    void openHeld(Player player) {
        Validation validation = validate(player.getInventory().getItemInMainHand(), player);
        if (!validation.stone) {
            player.sendMessage(Component.text("無属性の魔石をメインハンドに持ってください", NamedTextColor.RED));
            return;
        }
        if (!validation.valid) {
            player.sendMessage(Component.text("この魔石は不正・複製済み・古い状態のため使用できません", NamedTextColor.RED));
            return;
        }
        if (validation.record.circuit() != null) {
            player.sendMessage(Component.text("この魔石には「" + validation.record.circuit().name() + "」が封入済みです", NamedTextColor.AQUA));
            return;
        }
        openPicker(player, validation.record.instanceId(), 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        if (!isStone(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        if (now - debounce.getOrDefault(player.getUniqueId(), 0L) < 250L) return;
        debounce.put(player.getUniqueId(), now);
        Validation validation = validate(item, player);
        if (!validation.valid) {
            player.sendMessage(Component.text("この魔石は不正・複製済み・古い状態のため使用できません", NamedTextColor.RED));
            return;
        }
        if (validation.record.circuit() == null) {
            openPicker(player, validation.record.instanceId(), 0);
            return;
        }
        var result = spells.castEmbedded(player, validation.record.circuit(), "MANUAL");
        if (!result.accepted()) {
            player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
            return;
        }
        if (!database.consumeNeutralStone(validation.record.instanceId(), player.getUniqueId(), validation.record.usesRemaining())) {
            player.sendMessage(Component.text("魔石の使用回数を更新できませんでした", NamedTextColor.RED));
            return;
        }
        int remaining = validation.record.usesRemaining() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            player.sendMessage(Component.text("無属性の魔石は力を使い果たし、消滅しました", NamedTextColor.GRAY));
            player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 24, 0.35, 0.45, 0.35, 0.05);
        } else {
            SpellDatabase.NeutralStoneRecord updated = database.neutralStone(validation.record.instanceId()).orElseThrow();
            applyAppearance(item, updated);
            player.getInventory().setItemInMainHand(item);
            player.sendMessage(Component.text("魔石の残り使用回数: " + remaining + "/" + MAX_USES, NamedTextColor.AQUA));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.25f);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PickerHolder holder)
                || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId())) return;
        int raw = event.getRawSlot();
        if (raw == 45) { openPicker(player, holder.stoneId, holder.page - 1); return; }
        if (raw == 49) { openPicker(player, holder.stoneId, holder.page); return; }
        if (raw == 53) { openPicker(player, holder.stoneId, holder.page + 1); return; }
        if (raw < 0 || raw >= 45) return;
        List<Circuit> saved = circuits.getCircuits(player.getUniqueId());
        int index = holder.page * 45 + raw;
        if (index >= saved.size()) return;
        Validation held = validate(player.getInventory().getItemInMainHand(), player);
        if (!held.valid || !held.record.instanceId().equals(holder.stoneId) || held.record.circuit() != null) {
            player.closeInventory();
            player.sendMessage(Component.text("未封入の魔石をメインハンドに持ち続けてください", NamedTextColor.RED));
            return;
        }
        Circuit selected = saved.get(index);
        if (!database.bindNeutralStone(holder.stoneId, player.getUniqueId(), selected)) {
            player.closeInventory();
            player.sendMessage(Component.text("魔術を封入できませんでした", NamedTextColor.RED));
            return;
        }
        SpellDatabase.NeutralStoneRecord updated = database.neutralStone(holder.stoneId).orElseThrow();
        ItemStack item = player.getInventory().getItemInMainHand();
        applyAppearance(item, updated);
        player.getInventory().setItemInMainHand(item);
        player.closeInventory();
        player.sendMessage(Component.text("「" + selected.name() + "」を無属性の魔石へ封入しました", NamedTextColor.GREEN));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.1f);
    }

    private void openPicker(Player player, UUID stoneId, int requestedPage) {
        List<Circuit> saved = circuits.getCircuits(player.getUniqueId());
        if (saved.isEmpty()) {
            player.sendMessage(Component.text("先に /msf open で魔術を作成・保存してください", NamedTextColor.RED));
            return;
        }
        int pages = Math.max(1, (saved.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        PickerHolder holder = new PickerHolder(player.getUniqueId(), stoneId, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("無属性の魔石 — 封入する魔術", NamedTextColor.BLUE));
        holder.inventory = inventory;
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= saved.size()) break;
            Circuit circuit = saved.get(index);
            inventory.setItem(slot, guiItem(Material.ENCHANTED_BOOK, circuit.name(), NamedTextColor.AQUA,
                    List.of("ノード: " + circuit.nodes().size() + "/25", "Revision: " + circuit.revision(), "クリックして封入")));
        }
        inventory.setItem(45, guiItem(Material.ARROW, "前のページ", NamedTextColor.YELLOW, List.of((page + 1) + " / " + pages)));
        inventory.setItem(49, guiItem(Material.COMPASS, "一覧を更新", NamedTextColor.AQUA, List.of("保存済み魔術を再読込")));
        inventory.setItem(53, guiItem(Material.ARROW, "次のページ", NamedTextColor.YELLOW, List.of((page + 1) + " / " + pages)));
        player.openInventory(inventory);
    }

    boolean isStone(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return TYPE.equals(item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING));
    }

    private Validation validate(ItemStack item, Player expectedOwner) {
        if (!isStone(item)) return new Validation(false, false, "NOT_STONE", null);
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Integer schema = pdc.get(schemaKey, PersistentDataType.INTEGER);
        String instanceRaw = pdc.get(instanceKey, PersistentDataType.STRING);
        String ownerRaw = pdc.get(ownerKey, PersistentDataType.STRING);
        String circuitRaw = pdc.get(circuitKey, PersistentDataType.STRING);
        Long revision = pdc.get(revisionKey, PersistentDataType.LONG);
        Integer uses = pdc.get(usesKey, PersistentDataType.INTEGER);
        Long issuedAt = pdc.get(issuedAtKey, PersistentDataType.LONG);
        String signature = pdc.get(integrityKey, PersistentDataType.STRING);
        if (schema == null || instanceRaw == null || ownerRaw == null || uses == null || issuedAt == null || signature == null) {
            return new Validation(true, false, "MISSING_DATA", null);
        }
        try {
            UUID instance = UUID.fromString(instanceRaw);
            UUID owner = UUID.fromString(ownerRaw);
            String signedCircuit = Objects.requireNonNullElse(circuitRaw, "");
            long signedRevision = Objects.requireNonNullElse(revision, 0L);
            boolean signed = integrity.verifyPayload(signature, "neutral-stone-v1", SCHEMA, instance, owner,
                    signedCircuit, signedRevision, uses, issuedAt);
            if (!signed) return new Validation(true, false, "SIGNATURE", null);
            SpellDatabase.NeutralStoneRecord record = database.neutralStone(instance).orElse(null);
            if (record == null) {
                Circuit recoveredCircuit = null;
                if (!signedCircuit.isEmpty()) {
                    recoveredCircuit = circuits.getCircuit(UUID.fromString(signedCircuit))
                            .filter(value -> value.ownerId().equals(owner) && value.revision() == signedRevision).orElse(null);
                    if (recoveredCircuit == null) return new Validation(true, false, "CIRCUIT", null);
                } else if (signedRevision != 0L) return new Validation(true, false, "CIRCUIT", null);
                if (!database.recoverSignedNeutralStone(instance, owner, uses, issuedAt, recoveredCircuit)) {
                    return new Validation(true, false, "LEDGER", null);
                }
                record = database.neutralStone(instance).orElse(null);
            }
            if (record == null || !record.ownerId().equals(owner) || record.usesRemaining() != uses || record.usesRemaining() < 1
                    || record.issuedAt() != issuedAt) return new Validation(true, false, "LEDGER", record);
            String expectedCircuit = record.circuit() == null ? "" : record.circuit().circuitId().toString();
            long expectedRevision = record.circuit() == null ? 0L : record.circuit().revision();
            if (!Objects.equals(Objects.requireNonNullElse(circuitRaw, ""), expectedCircuit)
                    || Objects.requireNonNullElse(revision, 0L) != expectedRevision) return new Validation(true, false, "CIRCUIT", record);
            if (expectedOwner != null && !record.ownerId().equals(expectedOwner.getUniqueId())) {
                if (!database.transferNeutralStone(instance, record.ownerId(), expectedOwner.getUniqueId(), uses)) {
                    return new Validation(true, false, "TRANSFER_CONFLICT", record);
                }
                record = database.neutralStone(instance).orElse(null);
                if (record == null || !record.ownerId().equals(expectedOwner.getUniqueId())) {
                    return new Validation(true, false, "TRANSFER_FAILED", record);
                }
                applyAppearance(item, record);
            }
            return new Validation(true, true, "OK", record);
        } catch (RuntimeException error) { return new Validation(true, false, "MALFORMED", null); }
    }

    private void applyAppearance(ItemStack item, SpellDatabase.NeutralStoneRecord record) {
        item.setType(Material.ECHO_SHARD);
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LEGACY.deserialize("&8【&9無属性の魔石&8】"));
        List<Component> lore = new ArrayList<>();
        if (record.circuit() == null) {
            lore.add(LEGACY.deserialize("&7自分で構築した魔術を一つ封入できる"));
            lore.add(LEGACY.deserialize("&b右クリック: &f封入する魔術を選択"));
        } else {
            lore.add(LEGACY.deserialize("&9封入魔術: &f" + record.circuit().name()));
            lore.add(LEGACY.deserialize("&7保存時Revision: &f" + record.circuit().revision()));
            lore.add(LEGACY.deserialize("&b右クリック: &f封入魔術を発動"));
        }
        lore.add(LEGACY.deserialize("&9残り使用回数: &f" + record.usesRemaining() + "&7/" + MAX_USES));
        lore.add(LEGACY.deserialize("&8個体識別: " + record.instanceId().toString().substring(0, 8)));
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(typeKey, PersistentDataType.STRING, TYPE);
        pdc.set(schemaKey, PersistentDataType.INTEGER, SCHEMA);
        pdc.set(instanceKey, PersistentDataType.STRING, record.instanceId().toString());
        pdc.set(ownerKey, PersistentDataType.STRING, record.ownerId().toString());
        pdc.set(usesKey, PersistentDataType.INTEGER, record.usesRemaining());
        pdc.set(issuedAtKey, PersistentDataType.LONG, record.issuedAt());
        String circuit = record.circuit() == null ? "" : record.circuit().circuitId().toString();
        long revision = record.circuit() == null ? 0L : record.circuit().revision();
        if (circuit.isEmpty()) pdc.remove(circuitKey); else pdc.set(circuitKey, PersistentDataType.STRING, circuit);
        pdc.set(revisionKey, PersistentDataType.LONG, revision);
        pdc.set(integrityKey, PersistentDataType.STRING, integrity.signPayload("neutral-stone-v1", SCHEMA,
                record.instanceId(), record.ownerId(), circuit, revision, record.usesRemaining(), record.issuedAt()));
        item.setItemMeta(meta);
    }

    private static ItemStack guiItem(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private record Validation(boolean stone, boolean valid, String reason, SpellDatabase.NeutralStoneRecord record) { }
    private static final class PickerHolder implements InventoryHolder {
        final UUID owner;
        final UUID stoneId;
        final int page;
        Inventory inventory;
        PickerHolder(UUID owner, UUID stoneId, int page) { this.owner = owner; this.stoneId = stoneId; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
}
