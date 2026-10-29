package net.monacraft.spellforge.paper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.monacraft.spellforge.api.CircuitService;
import net.monacraft.spellforge.api.FragmentService;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitLibrary;
import net.monacraft.spellforge.model.SpellModel.CircuitSummary;
import net.monacraft.spellforge.model.SpellModel.EditorPalette;
import net.monacraft.spellforge.model.SpellModel.OwnedFragment;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class ProtocolBridge implements PluginMessageListener, Listener {
    static final String CHANNEL = "monaspellforge:bridge";

    private final Plugin plugin;
    private final CircuitService circuits;
    private final FragmentService fragments;
    private final SpellDatabase database;
    private final CircuitEmbeddingService embedding;
    private final DefaultSpellService spells;
    private final ArcSysService arcSys;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new com.google.gson.JsonPrimitive(value.toEpochMilli()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>)
                    (json, type, context) -> Instant.ofEpochMilli(json.getAsLong()))
            .create();
    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Rate> rates = new ConcurrentHashMap<>();

    ProtocolBridge(Plugin plugin, CircuitService circuits, FragmentService fragments, CircuitEmbeddingService embedding,
                   SpellDatabase database, DefaultSpellService spells, ArcSysService arcSys) {
        this.plugin = plugin;
        this.circuits = circuits;
        this.fragments = fragments;
        this.embedding = embedding;
        this.database = database;
        this.spells = spells;
        this.arcSys = arcSys;
    }

    boolean openModEditor(Player player) {
        if (!player.getListeningPluginChannels().contains(CHANNEL)) return false;
        String token = UUID.randomUUID().toString();
        sessions.put(player.getUniqueId(), token);
        Circuit circuit = circuits.getCircuits(player.getUniqueId()).stream()
                .findFirst().orElseGet(() -> Circuit.empty(player.getUniqueId()));
        // Older 1.0.x clients do not know the library packet, so keep the original editor packet first.
        openCircuit(player, token, circuit);
        sendLibrary(player, token, "");
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sessions.remove(playerId);
        rates.remove(playerId);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] bytes) {
        if (!CHANNEL.equals(channel) || !allow(player)) return;
        try {
            SpellForgeProtocol.Frame frame = SpellForgeProtocol.decode(bytes);
            if (frame.type() == SpellForgeProtocol.Type.HELLO) {
                send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.HELLO_ACK, frame.requestId(),
                        "", null, 0, "1"));
                return;
            }

            String token = sessions.get(player.getUniqueId());
            if (token == null || !token.equals(frame.sessionToken())) {
                error(player, frame, "INVALID_SESSION");
                return;
            }

            if (frame.type() == SpellForgeProtocol.Type.ARC_REQUEST) {
                handleArcRequest(player, frame, token);
                return;
            }
            if (frame.type() == SpellForgeProtocol.Type.ARC_FILE_IMPORT_REQ) {
                handleArcFileImport(player, frame, token);
                return;
            }

            if (frame.type() == SpellForgeProtocol.Type.CIRCUIT_LIBRARY_REQ) {
                sendLibrary(player, token, "");
                return;
            }
            if (frame.type() == SpellForgeProtocol.Type.CIRCUIT_CREATE_REQ) {
                int number = circuits.getCircuits(player.getUniqueId()).size() + 1;
                Circuit empty = Circuit.empty(player.getUniqueId());
                Circuit named = new Circuit(empty.circuitId(), empty.ownerId(), "魔術回路 " + number,
                        empty.revision(), empty.schemaVersion(), empty.nodes(), empty.edges(), empty.createdAt(), empty.updatedAt());
                openCircuit(player, token, named);
                return;
            }
            if (frame.type() == SpellForgeProtocol.Type.CIRCUIT_OPEN_REQ) {
                Circuit circuit = ownedCircuit(player, frame.circuitId());
                if (circuit == null) {
                    error(player, frame, "CIRCUIT_NOT_FOUND");
                    return;
                }
                openCircuit(player, token, circuit);
                return;
            }
            if (frame.type() == SpellForgeProtocol.Type.CIRCUIT_DELETE_REQ) {
                Circuit circuit = ownedCircuit(player, frame.circuitId());
                if (circuit == null || !circuits.delete(circuit.circuitId(), player.getUniqueId())) {
                    error(player, frame, "CIRCUIT_NOT_FOUND");
                    return;
                }
                sendLibrary(player, token, "「" + circuit.name() + "」を削除しました");
                return;
            }
            if (frame.type() == SpellForgeProtocol.Type.CIRCUIT_TOGGLE_REQ) {
                Circuit circuit = ownedCircuit(player, frame.circuitId());
                if (circuit == null) {
                    error(player, frame, "CIRCUIT_NOT_FOUND");
                    return;
                }
                boolean currentlyActive = circuits.getActiveCircuit(player.getUniqueId())
                        .map(active -> active.circuitId().equals(circuit.circuitId())).orElse(false);
                boolean enable = !currentlyActive;
                if (!circuits.setActive(circuit.circuitId(), player.getUniqueId(), enable)) {
                    error(player, frame, "CIRCUIT_NOT_FOUND");
                    return;
                }
                spells.cancel(player.getUniqueId());
                sendLibrary(player, token, "「" + circuit.name() + "」を" + (enable ? "有効化" : "無効化") + "しました");
                return;
            }

            if (frame.type() == SpellForgeProtocol.Type.STOW_NODE_REQ) {
                com.google.gson.JsonObject request = com.google.gson.JsonParser.parseString(frame.payload()).getAsJsonObject();
                UUID nodeId = UUID.fromString(request.get("nodeId").getAsString());
                Circuit circuit = gson.fromJson(request.get("circuit"), Circuit.class);
                if (!circuit.ownerId().equals(player.getUniqueId())) {
                    error(player, frame, "OWNER_MISMATCH");
                    return;
                }
                var result = embedding.stow(player, circuit, frame.baseRevision(), nodeId);
                send(player, new SpellForgeProtocol.Frame(result.conflict()
                        ? SpellForgeProtocol.Type.CONFLICT : SpellForgeProtocol.Type.SAVE_RESULT,
                        frame.requestId(), token, circuit.circuitId(), result.circuit().revision(), gson.toJson(result)));
                if (result.saved()) sendEditorData(player, token, result.circuit());
                return;
            }

            if (frame.type() == SpellForgeProtocol.Type.VALIDATE_REQ || frame.type() == SpellForgeProtocol.Type.SAVE_REQ) {
                Circuit circuit = gson.fromJson(frame.payload(), Circuit.class);
                if (!circuit.ownerId().equals(player.getUniqueId())) {
                    error(player, frame, "OWNER_MISMATCH");
                    return;
                }
                if (frame.type() == SpellForgeProtocol.Type.VALIDATE_REQ) {
                    var result = circuits.validate(circuit);
                    send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.VALIDATE_RESULT,
                            frame.requestId(), token, circuit.circuitId(), circuit.revision(), gson.toJson(result.issues())));
                    return;
                }

                var result = embedding.save(player, circuit, frame.baseRevision());
                send(player, new SpellForgeProtocol.Frame(result.conflict()
                        ? SpellForgeProtocol.Type.CONFLICT : SpellForgeProtocol.Type.SAVE_RESULT,
                        frame.requestId(), token, circuit.circuitId(), result.circuit().revision(), gson.toJson(result)));
                if (result.saved()) sendEditorData(player, token, result.circuit());
            }
        } catch (Exception error) {
            plugin.getLogger().warning("Rejected editor packet from " + player.getName() + ": " + error.getMessage());
        }
    }

    private Circuit ownedCircuit(Player player, UUID circuitId) {
        if (circuitId == null) return null;
        return circuits.getCircuit(circuitId)
                .filter(circuit -> circuit.ownerId().equals(player.getUniqueId()))
                .orElse(null);
    }

    private void openCircuit(Player player, String token, Circuit circuit) {
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.OPEN_EDITOR, UUID.randomUUID(), token,
                circuit.circuitId(), circuit.revision(), gson.toJson(circuit)));
        sendEditorData(player, token, circuit);
    }

    private void sendLibrary(Player player, String token, String message) {
        UUID activeId = circuits.getActiveCircuit(player.getUniqueId()).map(Circuit::circuitId).orElse(null);
        List<CircuitSummary> summaries = circuits.getCircuits(player.getUniqueId()).stream()
                .map(circuit -> new CircuitSummary(circuit.circuitId(), circuit.name(), circuit.revision(),
                        circuit.nodes().size(), circuit.edges().size(), circuit.circuitId().equals(activeId), circuit.updatedAt()))
                .toList();
        CircuitLibrary library = new CircuitLibrary(summaries, message);
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.CIRCUIT_LIBRARY,
                UUID.randomUUID(), token, null, 0, gson.toJson(library)));
    }

    private void sendEditorData(Player player, String token, Circuit circuit) {
        EditorPalette palette = new EditorPalette(ownedFragments(player));
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.EDITOR_DATA, UUID.randomUUID(), token,
                circuit.circuitId(), circuit.revision(), gson.toJson(palette)));
    }

    private List<OwnedFragment> ownedFragments(Player player) {
        Map<UUID, OwnedFragment> owned = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getContents()) {
            FragmentService.ValidationResult valid = fragments.validateItem(item, player);
            if (!valid.valid()) continue;
            fragments.refreshAppearance(item);
            var instance = valid.instance();
            owned.putIfAbsent(instance.instanceId(),
                    new OwnedFragment(instance.instanceId(), instance.fragmentId(), instance.level()));
        }
        for (OwnedFragment stored : database.storedFragments(player.getUniqueId())) {
            owned.putIfAbsent(stored.instanceId(), stored);
        }
        return new ArrayList<>(owned.values());
    }

    private boolean allow(Player player) {
        long second = System.currentTimeMillis() / 1000;
        Rate rate = rates.compute(player.getUniqueId(), (id, old) -> old == null || old.second != second
                ? new Rate(second, 1) : new Rate(second, old.count + 1));
        return rate.count <= 20;
    }

    private void error(Player player, SpellForgeProtocol.Frame frame, String code) {
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ERROR, frame.requestId(), "",
                frame.circuitId(), frame.baseRevision(), code));
    }

    private void send(Player player, SpellForgeProtocol.Frame frame) {
        player.sendPluginMessage(plugin, CHANNEL, SpellForgeProtocol.encode(frame));
    }

    private void handleArcRequest(Player player, SpellForgeProtocol.Frame frame, String token) {
        JsonObjectResponse response;
        try {
            if (!player.hasPermission("monaspellforge.arcsys.vscode"))
                throw new ArcSysService.ArcRequestException("NO_PERMISSION", "VS Code連携権限がありません");
            com.google.gson.JsonObject request = com.google.gson.JsonParser.parseString(frame.payload()).getAsJsonObject();
            if (request.get("version").getAsInt() != 1 || !frame.requestId().toString().equals(request.get("id").getAsString()))
                throw new ArcSysService.ArcRequestException("PROTOCOL", "要求IDまたはバージョンが不正です");
            String type = request.get("type").getAsString();
            com.google.gson.JsonObject payload = request.has("payload") && request.get("payload").isJsonObject()
                    ? request.getAsJsonObject("payload") : new com.google.gson.JsonObject();
            response = new JsonObjectResponse(true, arcSys.handle(player, type, payload), null, null);
        } catch (ArcSysService.ArcRequestException rejected) {
            response = new JsonObjectResponse(false, null, rejected.code, rejected.getMessage());
        } catch (Exception rejected) {
            plugin.getLogger().warning("Rejected ArcSys request from " + player.getName() + ": " + rejected.getMessage());
            response = new JsonObjectResponse(false, null, "SERVER_REJECTED", "サーバーが要求を安全に拒否しました");
        }
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("version", 1); json.addProperty("id", frame.requestId().toString()); json.addProperty("ok", response.ok());
        if (response.ok()) json.add("result", response.result());
        else { com.google.gson.JsonObject error = new com.google.gson.JsonObject(); error.addProperty("code", response.code()); error.addProperty("message", response.message()); json.add("error", error); }
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ARC_RESPONSE, frame.requestId(), token, null, 0, json.toString()));
    }

    private void handleArcFileImport(Player player, SpellForgeProtocol.Frame frame, String token) {
        com.google.gson.JsonObject envelope = new com.google.gson.JsonObject();
        try {
            com.google.gson.JsonObject payload = com.google.gson.JsonParser.parseString(frame.payload()).getAsJsonObject();
            com.google.gson.JsonObject result = arcSys.handle(player, "deploy", payload);
            envelope.addProperty("ok", true);
            envelope.add("result", result);
        } catch (ArcSysService.ArcRequestException rejected) {
            envelope.addProperty("ok", false);
            envelope.addProperty("code", rejected.code);
            envelope.addProperty("message", rejected.getMessage());
        } catch (Exception rejected) {
            plugin.getLogger().warning("Rejected ArcSys file import from " + player.getName() + ": " + rejected.getMessage());
            envelope.addProperty("ok", false);
            envelope.addProperty("code", "SERVER_REJECTED");
            envelope.addProperty("message", "サーバーがファイルを安全に拒否しました");
        }
        send(player, new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ARC_FILE_IMPORT_RESULT,
                frame.requestId(), token, null, 0, envelope.toString()));
    }

    private record JsonObjectResponse(boolean ok, com.google.gson.JsonObject result, String code, String message) { }

    private record Rate(long second, int count) { }
}
