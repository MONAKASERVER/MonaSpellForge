package net.monacraft.spellforge.paper;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.catalog.FragmentPresentation;
import net.monacraft.spellforge.model.SpellModel.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

final class EditorGui implements Listener {
    private static final int[] GRID = {2,3,4,5,6, 11,12,13,14,15, 20,21,22,23,24, 29,30,31,32,33, 38,39,40,41,42};
    private final FragmentService fragments;
    private final CircuitService circuits;
    private final CircuitEmbeddingService embedding;
    private final DefaultSpellService spells;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, DeleteConfirmation> pendingDeletes = new ConcurrentHashMap<>();

    EditorGui(FragmentService fragments, CircuitService circuits, CircuitEmbeddingService embedding, DefaultSpellService spells) {
        this.fragments = fragments; this.circuits = circuits; this.embedding = embedding; this.spells = spells;
    }

    void open(Player player) {
        for (ItemStack item : player.getInventory().getContents()) fragments.refreshAppearance(item);
        renderLibrary(player, 0);
    }

    private void renderLibrary(Player player, int requestedPage) {
        List<Circuit> saved = circuits.getCircuits(player.getUniqueId());
        UUID activeId = circuits.getActiveCircuit(player.getUniqueId()).map(Circuit::circuitId).orElse(null);
        int pages = Math.max(1, (saved.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        LibraryHolder holder = new LibraryHolder(player.getUniqueId(), page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("MonaSpellForge — 魔術一覧", NamedTextColor.DARK_PURPLE));
        holder.inventory = inventory;
        DeleteConfirmation pending = pendingDeletes.get(player.getUniqueId());
        if (pending != null && pending.expiresAt < System.currentTimeMillis()) {
            pendingDeletes.remove(player.getUniqueId());
            pending = null;
        }
        for (int slot = 0; slot < 45; slot++) {
            int index = page * 45 + slot;
            if (index >= saved.size()) break;
            Circuit circuit = saved.get(index);
            boolean active = circuit.circuitId().equals(activeId);
            List<String> lore = new ArrayList<>(List.of(
                    "状態: " + (active ? "有効" : "無効"),
                    "ノード: " + circuit.nodes().size() + "/25  配線: " + circuit.edges().size() + "/64",
                    "Revision: " + circuit.revision(),
                    "左クリック: 再編集",
                    "Shift+左クリック: " + (active ? "無効化" : "有効化"),
                    "右クリック: 削除"));
            if (pending != null && pending.circuitId.equals(circuit.circuitId())) {
                lore.add("もう一度右クリックで完全に削除");
            }
            inventory.setItem(slot, item(active ? Material.LIME_DYE : Material.GRAY_DYE,
                    (active ? "[有効] " : "[無効] ") + circuit.name(), active ? NamedTextColor.GREEN : NamedTextColor.GRAY, lore));
        }
        inventory.setItem(45, item(Material.ARROW, "前のページ", NamedTextColor.YELLOW,
                List.of((page + 1) + " / " + pages)));
        inventory.setItem(49, item(Material.NETHER_STAR, "新しい魔術を作る", NamedTextColor.GREEN,
                List.of("空の魔術回路を新規作成します")));
        inventory.setItem(50, item(Material.COMPASS, "一覧を更新", NamedTextColor.AQUA,
                List.of("保存内容を再読み込みします")));
        inventory.setItem(53, item(Material.ARROW, "次のページ", NamedTextColor.YELLOW,
                List.of((page + 1) + " / " + pages)));
        player.openInventory(inventory);
    }

    private Circuit newCircuit(UUID ownerId) {
        Circuit empty = Circuit.empty(ownerId);
        String name = "魔術回路 " + (circuits.getCircuits(ownerId).size() + 1);
        return new Circuit(empty.circuitId(), empty.ownerId(), name, empty.revision(), empty.schemaVersion(),
                empty.nodes(), empty.edges(), empty.createdAt(), empty.updatedAt());
    }

    private void openEditor(Player player, Circuit circuit) {
        Session session = new Session(circuit, System.currentTimeMillis());
        sessions.put(player.getUniqueId(), session);
        render(player, session);
    }

    private void render(Player player, Session session) {
        EditorHolder holder = new EditorHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("MonaSpellForge — 5×5魔術回路", NamedTextColor.DARK_PURPLE));
        holder.inventory = inventory;
        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.BLACK, List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, pane);
        for (int index = 0; index < GRID.length; index++) {
            int gridIndex = index;
            CircuitNode node = session.circuit.nodes().stream().filter(n -> n.slotIndex() == gridIndex).findFirst().orElse(null);
            if (node == null) inventory.setItem(GRID[index], item(Material.GRAY_STAINED_GLASS_PANE, "空きスロット " + (index + 1), NamedTextColor.DARK_GRAY,
                    List.of("カーソルに欠片を持ってクリック", "右クリック: 配置済みノードを削除")));
            else {
                FragmentDefinition definition = fragments.getDefinition(node.fragmentId());
                Material material = definition == null ? Material.BARRIER : Optional.ofNullable(Material.matchMaterial(definition.displayItem())).orElse(Material.AMETHYST_SHARD);
                FragmentPresentation.Entry presentation = FragmentPresentation.get(node.fragmentId());
                inventory.setItem(GRID[index], item(material, presentation.plainName() + " Lv." + node.level(), color(definition == null ? "" : definition.rarity()),
                        List.of(presentation.description(), "原典識別: " + node.fragmentId(),
                                "ノード " + node.nodeId().toString().substring(0, 8), "右クリック: 削除")));
            }
        }
        var validation = circuits.validate(session.circuit);
        inventory.setItem(46, item(Material.BOOK, "回路情報", NamedTextColor.AQUA,
                List.of("ノード: " + session.circuit.nodes().size() + "/25", "配線: " + session.circuit.edges().size() + "/64", "Revision: " + session.circuit.revision())));
        inventory.setItem(47, item(validation.valid() ? Material.LIME_DYE : Material.RED_DYE, "検証", validation.valid() ? NamedTextColor.GREEN : NamedTextColor.RED,
                validation.valid() ? List.of("有効な回路です") : validation.issues().stream().limit(5).map(i -> i.code() + ": " + i.message()).toList()));
        inventory.setItem(49, item(Material.WRITABLE_BOOK, "保存して有効化", NamedTextColor.GOLD, List.of("新しく装着した欠片を消費して回路へ封入")));
        inventory.setItem(51, item(Material.BLAZE_ROD, "テスト発動", NamedTextColor.LIGHT_PURPLE, List.of("保存中の回路を手動発動します")));
        inventory.setItem(53, item(Material.CHEST, "欠片の配置方法", NamedTextColor.YELLOW, List.of("インベントリ内の欠片をカーソルで持ち", "中央5×5の空き枠をクリック", "保存時に欠片を消費・封入します")));
        inventory.setItem(45, item(Material.ARROW, "魔術一覧へ戻る", NamedTextColor.YELLOW,
                List.of("保存済み魔術の選択・削除")));
        player.openInventory(inventory);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getInventory().getHolder() instanceof LibraryHolder holder) {
            event.setCancelled(true);
            if (!holder.owner.equals(player.getUniqueId())) return;
            handleLibraryClick(player, holder, event);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) return;
        event.setCancelled(true);
        if (!holder.owner.equals(player.getUniqueId())) return;
        Session session = sessions.get(player.getUniqueId()); if (session == null) return;
        int raw = event.getRawSlot();
        if (raw == 45) { renderLibrary(player, 0); return; }
        int gridIndex = indexOf(raw);
        if (gridIndex >= 0) {
            if (event.isRightClick()) removeNode(session, gridIndex);
            else addNode(player, session, gridIndex, event.getCursor());
            render(player, session); return;
        }
        if (raw == 47) { showValidation(player, circuits.validate(session.circuit)); return; }
        if (raw == 49) {
            CircuitService.SaveResult result = embedding.save(player, session.circuit, session.circuit.revision());
            if (result.saved()) { session.circuit = result.circuit(); player.sendMessage(Component.text("魔術回路を保存しました。装着した欠片は回路へ封入されました", NamedTextColor.GREEN)); }
            else player.sendMessage(Component.text(result.conflict() ? "別の画面で更新されています。開き直してください"
                    : result.message().equals("MISSING_FRAGMENT") ? "新しく装着した欠片が不足しています" : "検証エラーのため保存できません", NamedTextColor.RED));
            render(player, session); return;
        }
        if (raw == 51) {
            if (session.circuit.revision() == 0) player.sendMessage(Component.text("先に保存してください", NamedTextColor.RED));
            else { var result = spells.cast(player, session.circuit.circuitId(), "MANUAL"); player.sendMessage(Component.text(result.message(), result.accepted() ? NamedTextColor.GREEN : NamedTextColor.RED)); }
        }
    }

    private void handleLibraryClick(Player player, LibraryHolder holder, InventoryClickEvent event) {
        int raw = event.getRawSlot();
        if (raw == 45) { renderLibrary(player, holder.page - 1); return; }
        if (raw == 49) { openEditor(player, newCircuit(player.getUniqueId())); return; }
        if (raw == 50) { renderLibrary(player, holder.page); return; }
        if (raw == 53) { renderLibrary(player, holder.page + 1); return; }
        if (raw < 0 || raw >= 45) return;
        List<Circuit> saved = circuits.getCircuits(player.getUniqueId());
        int index = holder.page * 45 + raw;
        if (index >= saved.size()) return;
        Circuit circuit = saved.get(index);
        if (event.isShiftClick() && event.isLeftClick()) {
            boolean active = circuits.getActiveCircuit(player.getUniqueId())
                    .map(value -> value.circuitId().equals(circuit.circuitId())).orElse(false);
            if (circuits.setActive(circuit.circuitId(), player.getUniqueId(), !active)) {
                spells.cancel(player.getUniqueId());
                player.sendMessage(Component.text("「" + circuit.name() + "」を" + (active ? "無効化" : "有効化") + "しました",
                        active ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
            } else player.sendMessage(Component.text("魔術の状態を変更できませんでした", NamedTextColor.RED));
            pendingDeletes.remove(player.getUniqueId());
            renderLibrary(player, holder.page);
            return;
        }
        if (!event.isRightClick()) {
            pendingDeletes.remove(player.getUniqueId());
            openEditor(player, circuit);
            return;
        }
        long now = System.currentTimeMillis();
        DeleteConfirmation pending = pendingDeletes.get(player.getUniqueId());
        if (pending != null && pending.circuitId.equals(circuit.circuitId()) && pending.expiresAt >= now) {
            pendingDeletes.remove(player.getUniqueId());
            if (circuits.delete(circuit.circuitId(), player.getUniqueId())) {
                Session session = sessions.get(player.getUniqueId());
                if (session != null && session.circuit.circuitId().equals(circuit.circuitId())) sessions.remove(player.getUniqueId());
                player.sendMessage(Component.text("「" + circuit.name() + "」を削除しました", NamedTextColor.GREEN));
            } else player.sendMessage(Component.text("魔術を削除できませんでした", NamedTextColor.RED));
        } else {
            pendingDeletes.put(player.getUniqueId(), new DeleteConfirmation(circuit.circuitId(), now + 5_000));
            player.sendMessage(Component.text("削除する場合は5秒以内にもう一度右クリックしてください", NamedTextColor.RED));
        }
        renderLibrary(player, holder.page);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof EditorHolder) {
            Session session = sessions.get(event.getPlayer().getUniqueId()); if (session != null) session.touched = System.currentTimeMillis();
        }
    }

    void cleanup(long maxIdleMillis) {
        long cutoff = System.currentTimeMillis() - maxIdleMillis;
        sessions.entrySet().removeIf(entry -> entry.getValue().touched < cutoff);
        pendingDeletes.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
    }

    private void addNode(Player player, Session session, int slot, ItemStack cursor) {
        if (session.circuit.nodes().stream().anyMatch(n -> n.slotIndex() == slot)) return;
        FragmentService.ValidationResult valid = fragments.validateItem(cursor, player);
        if (!valid.valid()) { player.sendMessage(Component.text("有効な自分の欠片をカーソルに持ってください: " + valid.reason(), NamedTextColor.RED)); return; }
        FragmentInstance instance = valid.instance();
        CircuitNode node = new CircuitNode(UUID.randomUUID(), slot, instance.instanceId(), instance.fragmentId(), instance.level(), Map.of(), slot % 5, slot / 5);
        List<CircuitNode> nodes = new ArrayList<>(session.circuit.nodes()); nodes.add(node);
        List<CircuitEdge> edges = new ArrayList<>(session.circuit.edges()); autoConnect(nodes, node, edges);
        session.circuit = session.circuit.withContent(nodes, edges); session.touched = System.currentTimeMillis();
    }

    private void autoConnect(List<CircuitNode> nodes, CircuitNode added, List<CircuitEdge> edges) {
        FragmentDefinition addedDefinition = fragments.getDefinition(added.fragmentId());
        for (CircuitNode source : nodes) {
            if (source == added) continue;
            FragmentDefinition sourceDefinition = fragments.getDefinition(source.fragmentId());
            for (PortDefinition output : sourceDefinition.ports()) for (PortDefinition input : addedDefinition.ports()) {
                if (output.direction() == Direction.OUTPUT && input.direction() == Direction.INPUT && output.type() == input.type()
                        && edges.stream().noneMatch(e -> e.targetNodeId().equals(added.nodeId()) && e.targetPortId().equals(input.id()))) {
                    edges.add(new CircuitEdge(UUID.randomUUID(), source.nodeId(), output.id(), added.nodeId(), input.id()));
                }
            }
        }
    }

    private void removeNode(Session session, int slot) {
        CircuitNode removed = session.circuit.nodes().stream().filter(n -> n.slotIndex() == slot).findFirst().orElse(null); if (removed == null) return;
        List<CircuitNode> nodes = session.circuit.nodes().stream().filter(n -> !n.nodeId().equals(removed.nodeId())).toList();
        List<CircuitEdge> edges = session.circuit.edges().stream().filter(e -> !e.sourceNodeId().equals(removed.nodeId()) && !e.targetNodeId().equals(removed.nodeId())).toList();
        session.circuit = session.circuit.withContent(nodes, edges); session.touched = System.currentTimeMillis();
    }

    private void showValidation(Player player, CompilationResult result) {
        if (result.valid()) player.sendMessage(Component.text("回路は有効です。魔力: " + (int) result.spell().manaCost() + " / 負荷: " + result.spell().runtimeCost(), NamedTextColor.GREEN));
        else result.issues().stream().limit(8).forEach(issue -> player.sendMessage(Component.text(issue.code() + ": " + issue.message() + " — " + issue.suggestion(), NamedTextColor.RED)));
    }

    private static int indexOf(int slot) { for (int i = 0; i < GRID.length; i++) if (GRID[i] == slot) return i; return -1; }
    private static ItemStack item(Material material, String name, NamedTextColor color, List<String> lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.displayName(Component.text(name, color));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)).toList()); item.setItemMeta(meta); return item;
    }
    private static NamedTextColor color(String rarity) { return switch (rarity) { case "EPIC" -> NamedTextColor.LIGHT_PURPLE; case "RARE" -> NamedTextColor.AQUA; default -> NamedTextColor.GREEN; }; }
    private static final class Session { Circuit circuit; long touched; Session(Circuit circuit, long touched) { this.circuit = circuit; this.touched = touched; } }
    private static final class EditorHolder implements InventoryHolder { final UUID owner; Inventory inventory; EditorHolder(UUID owner) { this.owner = owner; } @Override public Inventory getInventory() { return inventory; } }
    private static final class LibraryHolder implements InventoryHolder {
        final UUID owner; final int page; Inventory inventory;
        LibraryHolder(UUID owner, int page) { this.owner = owner; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private record DeleteConfirmation(UUID circuitId, long expiresAt) { }
}
