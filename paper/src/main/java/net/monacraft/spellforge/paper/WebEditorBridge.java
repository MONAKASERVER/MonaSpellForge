package net.monacraft.spellforge.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.catalog.FragmentPresentation;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.OwnedFragment;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure outbound bridge for the Java/Bedrock browser circuit editor. */
final class WebEditorBridge implements AutoCloseable {
    private final Plugin plugin;
    private final CircuitService circuits;
    private final FragmentService fragments;
    private final CircuitEmbeddingService embedding;
    private final SpellDatabase database;
    private final FragmentCatalog catalog;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>)
                    (json, type, context) -> Instant.parse(json.getAsString()))
            .create();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String apiBase;
    private final String publicUrl;
    private final String secret;
    private final AtomicBoolean polling = new AtomicBoolean();
    private volatile boolean closed;

    WebEditorBridge(Plugin plugin, CircuitService circuits, FragmentService fragments,
                    CircuitEmbeddingService embedding, SpellDatabase database, FragmentCatalog catalog) {
        this.plugin = plugin;
        this.circuits = circuits;
        this.fragments = fragments;
        this.embedding = embedding;
        this.database = database;
        this.catalog = catalog;
        this.apiBase = trimSlash(plugin.getConfig().getString("web-editor.api-base", "https://www.monacraft.net"));
        this.publicUrl = plugin.getConfig().getString("web-editor.public-url", "https://node.monacraft.net/");
        this.secret = plugin.getConfig().getString("web-editor.server-secret", "");
        if (enabled()) Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::poll, 40L, 40L);
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("web-editor.enabled", true) && secret != null && secret.length() >= 32;
    }

    void pair(Player player) {
        if (!enabled()) {
            player.sendMessage(Component.text("Web魔術エディターは現在準備中です", NamedTextColor.RED));
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("playerUuid", player.getUniqueId().toString());
        request.addProperty("playerName", player.getName());
        request.add("snapshot", snapshot(player));
        send("/api/spellforge/server/pair", "POST", request.toString()).whenComplete((response, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null || response.statusCode() / 100 != 2) {
                        player.sendMessage(Component.text("連携コードを発行できませんでした。少し待って再試行してください", NamedTextColor.RED));
                        return;
                    }
                    try {
                        String code = gson.fromJson(response.body(), JsonObject.class).get("code").getAsString();
                        player.sendMessage(Component.text("Web魔術エディター連携コード: ", NamedTextColor.AQUA)
                                .append(Component.text(code, NamedTextColor.GOLD)));
                        player.sendMessage(Component.text("10分以内にここをクリックしてコードを入力", NamedTextColor.GREEN)
                                .clickEvent(ClickEvent.openUrl(publicUrl)));
                    } catch (RuntimeException parseError) {
                        player.sendMessage(Component.text("連携応答を読み取れませんでした", NamedTextColor.RED));
                    }
                }));
    }

    private void poll() {
        if (closed || !enabled() || !polling.compareAndSet(false, true)) return;
        send("/api/spellforge/server/commands", "GET", null).whenComplete((response, error) -> {
            polling.set(false);
            if (closed || error != null || response.statusCode() / 100 != 2) return;
            try {
                JsonArray commands = gson.fromJson(response.body(), JsonObject.class).getAsJsonArray("commands");
                for (var element : commands) {
                    JsonObject command = element.getAsJsonObject();
                    Bukkit.getScheduler().runTask(plugin, () -> process(command));
                }
            } catch (RuntimeException parseError) {
                plugin.getLogger().warning("Web editor command response was invalid");
            }
        });
    }

    private void process(JsonObject command) {
        String id = command.get("id").getAsString();
        UUID playerId;
        try { playerId = UUID.fromString(command.get("playerUuid").getAsString()); }
        catch (RuntimeException error) { result(id, false, "プレイヤー情報が不正です", null); return; }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            result(id, false, "保存時はゲームへログインしている必要があります", null);
            return;
        }
        try {
            if (!"SAVE".equals(command.get("type").getAsString())) throw new IllegalArgumentException("未対応の操作です");
            Circuit circuit = gson.fromJson(command.getAsJsonObject("payload").get("circuit"), Circuit.class);
            CircuitService.SaveResult saved = embedding.save(player, circuit, circuit.revision());
            if (!saved.saved()) {
                String message = switch (saved.message().split(":", 2)[0]) {
                    case "MISSING_FRAGMENT" -> "所持ノードが不足しています";
                    case "FRAGMENT_STORAGE_FULL" -> "所持ノードが上限255個のため、しまえません";
                    case "OWNER_MISMATCH" -> "所有者が一致しません";
                    default -> saved.conflict() ? "別の画面で更新されています。再連携してください" : "回路を保存できません";
                };
                result(id, false, message, snapshot(player));
                return;
            }
            player.sendMessage(Component.text("Webエディターから魔術回路を保存しました", NamedTextColor.GREEN));
            result(id, true, "保存しました", snapshot(player));
        } catch (RuntimeException error) {
            result(id, false, "回路データを処理できませんでした", snapshot(player));
        }
    }

    private void result(String commandId, boolean success, String message, JsonObject snapshot) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        JsonObject result = new JsonObject(); result.addProperty("message", message); body.add("result", result);
        if (snapshot != null) body.add("snapshot", snapshot);
        send("/api/spellforge/server/commands/" + commandId + "/result", "POST", body.toString());
    }

    private JsonObject snapshot(Player player) {
        JsonObject root = new JsonObject();
        root.addProperty("playerUuid", player.getUniqueId().toString());
        root.addProperty("playerName", player.getName());
        root.add("circuits", gson.toJsonTree(circuits.getCircuits(player.getUniqueId())));
        root.add("fragments", gson.toJsonTree(ownedFragments(player)));
        JsonArray definitions = new JsonArray();
        catalog.all().forEach(definition -> {
            JsonObject entry = gson.toJsonTree(definition).getAsJsonObject();
            FragmentPresentation.Entry presentation = FragmentPresentation.get(definition.id());
            entry.addProperty("name", presentation.plainName());
            entry.addProperty("description", presentation.description());
            definitions.add(entry);
        });
        root.add("definitions", definitions);
        return root;
    }

    private List<OwnedFragment> ownedFragments(Player player) {
        Map<UUID, OwnedFragment> owned = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            FragmentService.ValidationResult valid = fragments.validateItem(item, player);
            if (!valid.valid()) continue;
            var instance = valid.instance();
            owned.putIfAbsent(instance.instanceId(), new OwnedFragment(instance.instanceId(), instance.fragmentId(), instance.level()));
        }
        for (OwnedFragment stored : database.storedFragments(player.getUniqueId())) owned.putIfAbsent(stored.instanceId(), stored);
        return new ArrayList<>(owned.values());
    }

    private java.util.concurrent.CompletableFuture<HttpResponse<String>> send(String path, String method, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(10)).header("x-spellforge-server-secret", secret)
                .header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String trimSlash(String value) { return value == null ? "" : value.replaceAll("/+$", ""); }
    @Override public void close() { closed = true; }
}
