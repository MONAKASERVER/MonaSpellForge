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

final class ProtocolBridge implements PluginMessageListener {
    static final String CHANNEL = "monaspellforge:bridge";

    private final Plugin plugin;
    private final CircuitService circuits;
    private final FragmentService fragments;
    private final SpellDatabase database;
    private final CircuitEmbeddingService embedding;
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new com.google.gson.JsonPrimitive(value.toEpochMilli()))
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>)
                    (json, type, context) -> Instant.ofEpochMilli(json.getAsLong()))
            .create();
    private final Map<UUID, String> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Rate> rates = new ConcurrentHashMap<>();

    ProtocolBridge(Plugin plugin, CircuitService circuits, FragmentService fragments, CircuitEmbeddingService embedding, SpellDatabase database) {
        this.plugin = plugin;
        this.circuits = circuits;
        this.fragments = fragments;
        this.embedding = embedding;
        this.database = database;
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
        List<CircuitSummary> summaries = circuits.getCircuits(player.getUniqueId()).stream()
                .map(circuit -> new CircuitSummary(circuit.circuitId(), circuit.name(), circuit.revision(),
                        circuit.nodes().size(), circuit.edges().size(), circuit.updatedAt()))
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

    private record Rate(long second, int count) { }
}
