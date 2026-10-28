package net.monacraft.spellforge.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback-only VS Code bridge. Every server operation is forwarded through the
 * logged-in Fabric connection; this class never connects to Paper directly.
 */
final class ArcSysDeveloperBridge extends WebSocketServer {
    private static final int PORT = 27_128;
    private static final int MAX_JSON_BYTES = 65_536;
    private static final int MAX_ARC_SOURCE_CHARS = 23_000;
    private static final long PAIRING_TTL_MILLIS = 10 * 60_000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, WebSocket> PENDING = new ConcurrentHashMap<>();
    private static ArcSysDeveloperBridge instance;

    private final Minecraft minecraft;
    private final String paperSession;
    private final String pairingCode;
    private final long expiresAt;
    private volatile WebSocket authenticated;
    private volatile long windowSecond;
    private volatile int messagesThisSecond;

    private ArcSysDeveloperBridge(Minecraft minecraft, String paperSession, String pairingCode) {
        super(new InetSocketAddress("127.0.0.1", PORT));
        this.minecraft = minecraft;
        this.paperSession = paperSession;
        this.pairingCode = pairingCode;
        this.expiresAt = System.currentTimeMillis() + PAIRING_TTL_MILLIS;
        setConnectionLostTimeout(15);
    }

    static synchronized String start(Minecraft minecraft, String paperSession) {
        stopBridge();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        ArcSysDeveloperBridge bridge = new ArcSysDeveloperBridge(minecraft, paperSession, code);
        try {
            bridge.start();
            instance = bridge;
            return code;
        } catch (RuntimeException failure) {
            return "起動失敗";
        }
    }

    static synchronized void stopBridge() {
        ArcSysDeveloperBridge bridge = instance;
        instance = null;
        if (bridge != null) try { bridge.stop(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        PENDING.clear();
    }

    static boolean forward(SpellForgeProtocol.Frame frame) {
        if (frame.type() != SpellForgeProtocol.Type.ARC_RESPONSE) return false;
        WebSocket socket = PENDING.remove(frame.requestId());
        if (socket != null && socket.isOpen()) { socket.send(frame.payload()); return true; }
        return false;
    }

    @Override public void onOpen(WebSocket connection, ClientHandshake handshake) {
        String resource = handshake.getResourceDescriptor();
        if (!"/arcsys".equals(resource) || authenticated != null || System.currentTimeMillis() > expiresAt) {
            connection.close(1008, "pairing unavailable");
        }
    }

    @Override public void onMessage(WebSocket connection, String message) {
        if (message.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES || !rateAllowed()) {
            connection.close(1009, "payload or rate limit"); return;
        }
        try {
            JsonObject request = JsonParser.parseString(message).getAsJsonObject();
            String idText = request.get("id").getAsString();
            String type = request.get("type").getAsString();
            if (authenticated == null) {
                if (!"hello".equals(type) || !pairingCode.equals(request.get("token").getAsString())) {
                    sendError(connection, idText, "PAIRING_REJECTED", "Minecraftに表示されたコードが一致しません"); return;
                }
                authenticated = connection;
                JsonObject result = new JsonObject(); result.addProperty("minecraftConnected", true); result.addProperty("at", Instant.now().toString());
                sendOk(connection, idText, result); return;
            }
            if (connection != authenticated) { connection.close(1008, "another client is paired"); return; }
            if (!allowedType(type)) { sendError(connection, idText, "UNKNOWN_REQUEST", "許可されていない要求です"); return; }
            if (request.has("payload") && request.get("payload").isJsonObject()) {
                JsonObject payload = request.getAsJsonObject("payload");
                if (payload.has("source") && payload.get("source").getAsString().length() > MAX_ARC_SOURCE_CHARS) {
                    sendError(connection, idText, "SOURCE_TOO_LARGE", "Minecraft転送上限を超えています"); return;
                }
            }
            UUID requestId = UUID.fromString(idText);
            PENDING.put(requestId, connection);
            minecraft.execute(() -> MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(
                    SpellForgeProtocol.Type.ARC_REQUEST, requestId, paperSession, null, 0, message)));
        } catch (Exception rejected) {
            sendError(connection, "", "INVALID_JSON", "要求形式が不正です");
        }
    }

    private boolean rateAllowed() {
        long second = System.currentTimeMillis() / 1000L;
        if (second != windowSecond) { windowSecond = second; messagesThisSecond = 0; }
        return ++messagesThisSecond <= 10;
    }

    private boolean allowedType(String type) {
        return switch (type) {
            case "listSpells", "openSpell", "compile", "validate", "deploy", "simulate",
                 "nodeGraph", "debugStart", "debugContinue", "debugState", "debugStop", "logs", "studioSnapshot",
                 "listCores", "inspectCore", "bindCore", "unbindCore", "validateCoreBinding", "simulateCore" -> true;
            default -> false;
        };
    }

    private static void sendOk(WebSocket socket, String id, JsonObject result) {
        JsonObject response = new JsonObject(); response.addProperty("version", 1); response.addProperty("id", id);
        response.addProperty("ok", true); response.add("result", result); socket.send(response.toString());
    }

    private static void sendError(WebSocket socket, String id, String code, String message) {
        JsonObject error = new JsonObject(); error.addProperty("code", code); error.addProperty("message", message);
        JsonObject response = new JsonObject(); response.addProperty("version", 1); response.addProperty("id", id);
        response.addProperty("ok", false); response.add("error", error); socket.send(response.toString());
    }

    @Override public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        if (connection == authenticated) { authenticated = null; PENDING.entrySet().removeIf(entry -> entry.getValue() == connection); }
    }
    @Override public void onError(WebSocket connection, Exception error) { }
    @Override public void onStart() { }
}
