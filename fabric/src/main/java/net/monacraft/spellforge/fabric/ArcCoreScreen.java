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
    private ControlMode controlMode = ControlMode.EASY;

    ArcCoreScreen(Screen parent, String token, UUID circuitId, UUID nodeId) {
        super(Component.literal("術式演算核 — プログラム制御パネル"));
        this.parent = parent; this.token = token; this.circuitId = circuitId; this.nodeId = nodeId;
    }

    @Override protected void init() {
        int panelW = panelWidth(), x = (width - panelW) / 2, y = panelTop();
        Button easy = addRenderableWidget(Button.builder(Component.literal(controlMode == ControlMode.EASY ? "● かんたんモード" : "かんたんモード"),
                b -> switchMode(ControlMode.EASY)).bounds(x + panelW - 226, y + 9, 104, 20).build());
        Button advanced = addRenderableWidget(Button.builder(Component.literal(controlMode == ControlMode.ADVANCED ? "● アドバンスド" : "アドバンスド"),
                b -> switchMode(ControlMode.ADVANCED)).bounds(x + panelW - 116, y + 9, 104, 20).build());
        easy.active = controlMode != ControlMode.EASY;
        advanced.active = controlMode != ControlMode.ADVANCED;

        if (controlMode == ControlMode.EASY) initEasyWidgets(x, y, panelW);
        else initAdvancedWidgets(x, y, panelW);
        request("listSpells", new JsonObject()); request("inspectCore", object());
    }

    private void initEasyWidgets(int x, int y, int panelW) {
        int left = x + 22, contentW = panelW - 44;
        addRenderableWidget(Button.builder(Component.literal("① VS Codeで術式を作る・編集"), b -> openVsCode())
                .bounds(left, y + 158, contentW, 24).build());
        programButton = addRenderableWidget(Button.builder(Component.literal("② 保存済み術式を選ぶ"), b -> cycleProgram())
                .bounds(left, y + 190, contentW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("③ 選択した術式をこのCoreで使う"), b -> bindLatest())
                .bounds(left, y + 216, contentW, 20).build());
        int half = (contentW - 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("状態を更新"), b -> reload()).bounds(left, y + 250, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose()).bounds(left + half + 8, y + 250, half, 20).build());
    }

    private void initAdvancedWidgets(int x, int y, int panelW) {
        int w = (panelW - 52) / 2, left = x + 22, right = left + w + 8;
        programButton = addRenderableWidget(Button.builder(Component.literal("プログラムを選択"), b -> cycleProgram()).bounds(left, y + 158, w, 20).build());
        modeButton = addRenderableWidget(Button.builder(Component.literal("方式: " + mode), b -> { mode = mode.equals("PINNED") ? "LATEST" : "PINNED"; b.setMessage(Component.literal("方式: " + mode)); }).bounds(right, y + 158, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("設定保存"), b -> bind()).bounds(left, y + 184, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("紐付け解除"), b -> request("unbindCore", object())).bounds(right, y + 184, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("プログラム検証"), b -> request("validateCoreBinding", object())).bounds(left, y + 210, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("実行ログ"), b -> request("logs", new JsonObject())).bounds(right, y + 210, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("シミュレーション"), b -> request("simulateCore", object())).bounds(left, y + 236, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("VS Codeかんたん編集"), b -> openVsCode()).bounds(right, y + 236, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("再読込"), b -> reload()).bounds(left, y + 262, w, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), b -> onClose()).bounds(right, y + 262, w, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BG);
        int panelW = panelWidth(), panelH = panelHeight(), x = (width - panelW) / 2, y = panelTop();
        graphics.fill(x, y, x + panelW, y + panelH, BORDER); graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, PANEL);
        graphics.text(font, title, x + 18, y + 15, PURPLE, false);
        int tx = x + 22, ty = y + 46;
        graphics.text(font, Component.literal("現在の術式: " + program), tx, ty, TEXT, false);
        graphics.text(font, Component.literal("Revision: " + revision + "   Mode: " + mode), tx, ty + 18, CYAN, false);
        graphics.text(font, Component.literal("Node Lv." + level + "   Status: " + status), tx, ty + 36, status.equals("READY") ? 0xFF72E6A5 : 0xFFFFC66D, false);
        if (controlMode == ControlMode.EASY) {
            graphics.text(font, Component.literal("1. VS Code編集を押す → 2. かんたんスタート → 3. Ctrl+Sで自動反映"), tx, ty + 62, MUTED, false);
            graphics.text(font, Component.literal("VS Codeを使わない場合は、保存済み術式を選んで③を押してください"), tx, ty + 80, MUTED, false);
        } else {
            graphics.text(font, Component.literal("入力: EXEC / ENTITY / LOCATION / NUMBER / BOOLEAN"), tx, ty + 58, MUTED, false);
            graphics.text(font, Component.literal("出力: EXEC(success/fail) / ENTITY / NUMBER / BOOLEAN"), tx, ty + 74, MUTED, false);
        }
        graphics.text(font, Component.literal(fit(lastResult, Math.max(20, panelW / 7))), tx, y + panelH - 28, MUTED, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    void accept(SpellForgeProtocol.Frame frame) {
        if (frame.type() != SpellForgeProtocol.Type.ARC_RESPONSE) return;
        String action = pending.remove(frame.requestId()); if (action == null) return;
        try {
            JsonObject response = JsonParser.parseString(frame.payload()).getAsJsonObject();
            if (!response.get("ok").getAsBoolean()) {
                JsonObject error = response.getAsJsonObject("error");
                status = error.get("code").getAsString();
                lastResult = status + ": " + error.get("message").getAsString();
                return;
            }
            JsonObject result = response.getAsJsonObject("result");
            if (action.equals("listSpells")) {
                spells.clear(); JsonArray values = result.getAsJsonArray("spells");
                values.forEach(value -> { JsonObject spell = value.getAsJsonObject(); spells.add(new SpellChoice(spell.get("id").getAsString(), spell.get("name").getAsString(), spell.get("revision").getAsLong())); });
                refreshProgramButton();
            } else if (action.equals("inspectCore") || action.equals("bindCore") || action.equals("validateCoreBinding")) {
                status = text(result, "status", "UNKNOWN"); program = text(result, "program", "未設定");
                revision = number(result, "revision"); level = (int) number(result, "level"); mode = text(result, "mode", mode);
                if (modeButton != null) modeButton.setMessage(Component.literal("方式: " + mode)); lastResult = action + ": " + status;
            } else if (action.equals("unbindCore")) { status = "UNBOUND"; program = "未設定"; revision = 0; lastResult = "紐付けを解除しました"; }
            else if (action.equals("logs")) lastResult = "デプロイ・実行ログを取得しました（VS Code出力でも確認できます）";
            else if (action.equals("simulateCore")) lastResult = "Simulation: " + text(result, "status", "UNKNOWN")
                    + " / fuel " + number(result, "fuelUsed") + " / rev." + number(result, "revision");
        } catch (RuntimeException error) { lastResult = "応答を解析できませんでした"; }
    }

    private void cycleProgram() { if (spells.isEmpty()) { lastResult = "先にVS Codeから術式をデプロイしてください"; return; } selected = (selected + 1) % spells.size(); refreshProgramButton(); }
    private void refreshProgramButton() {
        if (programButton == null) return;
        String label = spells.isEmpty() ? "プログラムなし" : spells.get(selected).name + " rev." + spells.get(selected).revision;
        programButton.setMessage(Component.literal(controlMode == ControlMode.EASY ? "② 保存済み術式: " + label : label));
    }
    private void bind() { if (spells.isEmpty()) { lastResult = "プログラムがありません"; return; } SpellChoice spell = spells.get(selected); JsonObject payload = object(); payload.addProperty("spellId", spell.id); payload.addProperty("revision", spell.revision); payload.addProperty("mode", mode); request("bindCore", payload); }
    private void bindLatest() { mode = "LATEST"; bind(); }
    private void reload() { request("listSpells", new JsonObject()); request("inspectCore", object()); }
    private void switchMode(ControlMode next) { if (controlMode == next) return; controlMode = next; modeButton = null; programButton = null; rebuildWidgets(); }
    private void openVsCode() {
        String code = ArcSysDeveloperBridge.start(minecraft, token, circuitId, nodeId);
        if (code.equals("起動失敗")) {
            lastResult = "VS Code Bridgeを起動できませんでした";
            return;
        }
        minecraft.keyboardHandler.setClipboard(code);
        lastResult = "コードをコピーしました — VS Codeの「ArcSys: かんたんスタート」で接続してください";
    }
    private JsonObject object() { JsonObject object = new JsonObject(); object.addProperty("circuitId", circuitId.toString()); object.addProperty("nodeId", nodeId.toString()); return object; }
    private void request(String type, JsonObject payload) { UUID id = UUID.randomUUID(); pending.put(id, type); JsonObject request = new JsonObject(); request.addProperty("version", 1); request.addProperty("id", id.toString()); request.addProperty("type", type); request.add("payload", payload); MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ARC_REQUEST, id, token, circuitId, 0, request.toString())); }
    private static String text(JsonObject value, String key, String fallback) { return value.has(key) ? value.get(key).getAsString() : fallback; }
    private static long number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsLong() : 0; }
    private static String fit(String value, int length) { return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "…"; }
    private int panelWidth() { return Math.min(680, width - 30); }
    private int panelHeight() { return Math.min(350, height - 30); }
    private int panelTop() { return (height - panelHeight()) / 2; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    private enum ControlMode { EASY, ADVANCED }
    private record SpellChoice(String id, String name, long revision) { }
}
