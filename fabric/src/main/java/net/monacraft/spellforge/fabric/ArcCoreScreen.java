package net.monacraft.spellforge.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;

import java.util.*;

/** Dedicated Fabric control panel for a saved ARCSYS_CORE node. */
final class ArcCoreScreen extends Screen {
    private static final int BG = 0xE00A0C14, PANEL = 0xFF1B2130, BORDER = 0xFF77839A;
    private static final int CYAN = 0xFF62E7FF, PURPLE = 0xFFC58BFF, TEXT = 0xFFF3F5FA, MUTED = 0xFF9CA9BA;
    private final Screen parent; private final String token; private final UUID circuitId; private final UUID nodeId;
    private final Map<UUID, String> pending = new HashMap<>();
    private final List<SpellChoice> spells = new ArrayList<>();
    private int selected; private String mode = "PINNED"; private String status = "読込中";
    private String program = "未設定"; private long revision; private int level; private String lastResult = "-";
    private Button programButton, modeButton;

    ArcCoreScreen(Screen parent, String token, UUID circuitId, UUID nodeId) {
        super(Component.literal("術式演算核 — プログラム制御パネル"));
        this.parent = parent; this.token = token; this.circuitId = circuitId; this.nodeId = nodeId;
    }

    @Override protected void init() {
        int cx = width / 2, y = Math.max(72, height / 2 - 110), w = Math.min(190, width / 3);
        programButton = addRenderableWidget(Button.builder(Component.literal("プログラムを選択"), b -> cycleProgram()).bounds(cx - w - 4, y + 118, w, 20).build());
        modeButton = addRenderableWidget(Button.builder(Component.literal("方式: " + mode), b -> { mode = mode.equals("PINNED") ? "LATEST" : "PINNED"; b.setMessage(Component.literal("方式: " + mode)); }).bounds(cx + 4, y + 118, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("設定保存"), b -> bind()).bounds(cx - w - 4, y + 144, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("紐付け解除"), b -> request("unbindCore", object())).bounds(cx + 4, y + 144, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("プログラム検証"), b -> request("validateCoreBinding", object())).bounds(cx - w - 4, y + 170, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("実行ログ"), b -> request("logs", new JsonObject())).bounds(cx + 4, y + 170, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("シミュレーション"), b -> request("simulateCore", object())).bounds(cx - w - 4, y + 196, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("VS Codeで編集"), b -> openVsCode()).bounds(cx + 4, y + 196, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("再読込"), b -> { request("listSpells", new JsonObject()); request("inspectCore", object()); }).bounds(cx - w - 4, y + 222, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose()).bounds(cx + 4, y + 222, w, 20).build());
        request("listSpells", new JsonObject()); request("inspectCore", object());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BG);
        int panelW = Math.min(620, width - 30), panelH = Math.min(330, height - 30), x = (width - panelW) / 2, y = (height - panelH) / 2;
        graphics.fill(x, y, x + panelW, y + panelH, BORDER); graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, PANEL);
        graphics.centeredText(font, title, width / 2, y + 14, PURPLE);
        int tx = x + 22, ty = y + 46;
        graphics.text(font, Component.literal("現在の術式: " + program), tx, ty, TEXT, false);
        graphics.text(font, Component.literal("Revision: " + revision + "   Mode: " + mode), tx, ty + 18, CYAN, false);
        graphics.text(font, Component.literal("Node Lv." + level + "   Status: " + status), tx, ty + 36, status.equals("READY") ? 0xFF72E6A5 : 0xFFFFC66D, false);
        graphics.text(font, Component.literal("入力: EXEC / ENTITY / LOCATION / NUMBER / BOOLEAN"), tx, ty + 58, MUTED, false);
        graphics.text(font, Component.literal("出力: EXEC(success/fail) / ENTITY / NUMBER / BOOLEAN"), tx, ty + 74, MUTED, false);
        graphics.text(font, Component.literal(fit(lastResult, Math.max(20, panelW / 7))), tx, y + panelH - 28, MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    void accept(SpellForgeProtocol.Frame frame) {
        if (frame.type() != SpellForgeProtocol.Type.ARC_RESPONSE) return;
        String action = pending.remove(frame.requestId()); if (action == null) return;
        try {
            JsonObject response = JsonParser.parseString(frame.payload()).getAsJsonObject();
            if (!response.get("ok").getAsBoolean()) { JsonObject error = response.getAsJsonObject("error"); lastResult = error.get("code").getAsString() + ": " + error.get("message").getAsString(); return; }
            JsonObject result = response.getAsJsonObject("result");
            if (action.equals("listSpells")) {
                spells.clear(); JsonArray values = result.getAsJsonArray("spells");
                values.forEach(value -> { JsonObject spell = value.getAsJsonObject(); spells.add(new SpellChoice(spell.get("id").getAsString(), spell.get("name").getAsString(), spell.get("revision").getAsLong())); });
                refreshProgramButton();
            } else if (action.equals("inspectCore") || action.equals("bindCore") || action.equals("validateCoreBinding")) {
                status = text(result, "status", "UNKNOWN"); program = text(result, "program", "未設定");
                revision = number(result, "revision"); level = (int) number(result, "level"); mode = text(result, "mode", mode);
                modeButton.setMessage(Component.literal("方式: " + mode)); lastResult = action + ": " + status;
            } else if (action.equals("unbindCore")) { status = "UNBOUND"; program = "未設定"; revision = 0; lastResult = "紐付けを解除しました"; }
            else if (action.equals("logs")) lastResult = "デプロイ・実行ログを取得しました（VS Code出力でも確認できます）";
            else if (action.equals("simulateCore")) lastResult = "Simulation: " + text(result, "status", "UNKNOWN")
                    + " / fuel " + number(result, "fuelUsed") + " / rev." + number(result, "revision");
        } catch (RuntimeException error) { lastResult = "応答を解析できませんでした"; }
    }

    private void cycleProgram() { if (spells.isEmpty()) { lastResult = "先にVS Codeから術式をデプロイしてください"; return; } selected = (selected + 1) % spells.size(); refreshProgramButton(); }
    private void refreshProgramButton() { if (programButton != null) programButton.setMessage(Component.literal(spells.isEmpty() ? "プログラムなし" : spells.get(selected).name + " rev." + spells.get(selected).revision)); }
    private void bind() { if (spells.isEmpty()) { lastResult = "プログラムがありません"; return; } SpellChoice spell = spells.get(selected); JsonObject payload = object(); payload.addProperty("spellId", spell.id); payload.addProperty("revision", spell.revision); payload.addProperty("mode", mode); request("bindCore", payload); }
    private void openVsCode() {
        String code = ArcSysDeveloperBridge.start(minecraft, token, circuitId, nodeId);
        lastResult = code.equals("起動失敗") ? "VS Code Bridgeを起動できませんでした"
                : "ペアリングコード: " + code + " — VS Codeで保存するとこのCoreへ自動反映します";
    }
    private JsonObject object() { JsonObject object = new JsonObject(); object.addProperty("circuitId", circuitId.toString()); object.addProperty("nodeId", nodeId.toString()); return object; }
    private void request(String type, JsonObject payload) { UUID id = UUID.randomUUID(); pending.put(id, type); JsonObject request = new JsonObject(); request.addProperty("version", 1); request.addProperty("id", id.toString()); request.addProperty("type", type); request.add("payload", payload); MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ARC_REQUEST, id, token, circuitId, 0, request.toString())); }
    private static String text(JsonObject value, String key, String fallback) { return value.has(key) ? value.get(key).getAsString() : fallback; }
    private static long number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsLong() : 0; }
    private static String fit(String value, int length) { return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "…"; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    private record SpellChoice(String id, String name, long revision) { }
}
