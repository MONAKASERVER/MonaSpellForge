package net.monacraft.spellforge.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;

/** In-game ArcSys source editor for players who do not want to use VS Code. */
final class ArcCodeEditorScreen extends Screen {
    private static final int BG = 0xE0080B12, PANEL = 0xFF1B2130, BORDER = 0xFF77839A;
    private static final int TEXT = 0xFFF3F5FA, MUTED = 0xFF9CA9BA, CYAN = 0xFF62E7FF;
    private static final int OK = 0xFF72E6A5, WARNING = 0xFFFFC66D, ERROR = 0xFFFF7777;
    private static final int MAX_SOURCE = 23_000;

    private final Screen parent;
    private final String token;
    private final UUID circuitId;
    private final UUID nodeId;
    private final Map<UUID, String> pending = new HashMap<>();
    private final List<SpellChoice> spells = new ArrayList<>();
    private List<Diagnostic> diagnostics = List.of();
    private MultiLineEditBox editor;
    private Button spellButton;
    private UUID spellId;
    private long baseRevision;
    private int selectedSpell;
    private String spellName = "新規術式";
    private String coreStatus = "読込中";
    private String status = "ARCSYS_COREの情報を読み込んでいます…";
    private int statusColor = WARNING;
    private String source = "";
    private boolean applyingSource;
    private boolean dirty;

    ArcCodeEditorScreen(Screen parent, String token, UUID circuitId, UUID nodeId) {
        super(Component.literal("ArcSys — Mod内コードエディター"));
        this.parent = parent;
        this.token = token;
        this.circuitId = circuitId;
        this.nodeId = nodeId;
    }

    @Override
    protected void init() {
        boolean wasDirty = dirty;
        int x = panelLeft(), y = panelTop(), panelW = panelWidth(), editorH = Math.max(120, panelHeight() - 158);
        int buttonArea = panelW - 32, gap = 5, buttonW = Math.max(72, (buttonArea - gap * 4) / 5);
        int bx = x + 16, by = y + 35;
        addRenderableWidget(Button.builder(Component.literal("新規"), button -> newSource())
                .bounds(bx, by, buttonW, 20).build());
        spellButton = addRenderableWidget(Button.builder(Component.literal("保存済みを開く"), button -> openNextSpell())
                .bounds(bx + (buttonW + gap), by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("検証  Ctrl+Enter"), button -> validateSource())
                .bounds(bx + (buttonW + gap) * 2, by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存・反映  Ctrl+S"), button -> deployAndBind())
                .bounds(bx + (buttonW + gap) * 3, by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), button -> onClose())
                .bounds(bx + (buttonW + gap) * 4, by, buttonW, 20).build());

        editor = MultiLineEditBox.builder()
                .setX(x + 16).setY(y + 64)
                .setPlaceholder(Component.literal("ここにArcSysコードを入力してください"))
                .setTextColor(TEXT).setCursorColor(CYAN).setTextShadow(false)
                .setShowBackground(true).setShowDecorations(true)
                .build(font, panelW - 32, editorH, Component.literal("ArcSysソースコード"));
        editor.setCharacterLimit(MAX_SOURCE);
        editor.setLineLimit(1_000);
        editor.setValueListener(value -> {
            source = value;
            if (!applyingSource) dirty = true;
        });
        addRenderableWidget(editor);
        applySource(source.isBlank() ? defaultSource() : source, wasDirty);
        setInitialFocus(editor);

        request("listSpells", new JsonObject());
        request("inspectCore", corePayload());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BG);
        int x = panelLeft(), y = panelTop(), panelW = panelWidth(), panelH = panelHeight();
        graphics.fill(x, y, x + panelW, y + panelH, BORDER);
        graphics.fill(x + 2, y + 2, x + panelW - 2, y + panelH - 2, PANEL);
        graphics.text(font, title, x + 16, y + 14, 0xFFC58BFF, false);
        String meta = spellName + "  rev." + baseRevision + (dirty ? "  • 未保存" : "  • 保存済み");
        graphics.text(font, Component.literal(fit(meta, Math.max(20, panelW / 7))), x + 235, y + 14, dirty ? WARNING : CYAN, false);

        int diagnosticsY = y + panelH - 66;
        graphics.fill(x + 12, diagnosticsY - 5, x + panelW - 12, y + panelH - 10, 0xFF111722);
        graphics.text(font, Component.literal("Core: " + coreStatus + "  |  " + lineCount() + "行 / " + source.length() + "文字"),
                x + 18, diagnosticsY, MUTED, false);
        graphics.text(font, Component.literal(fit(status, Math.max(20, panelW / 7))), x + 18, diagnosticsY + 15, statusColor, false);
        for (int index = 0; index < Math.min(2, diagnostics.size()); index++) {
            Diagnostic diagnostic = diagnostics.get(index);
            graphics.text(font, Component.literal(fit("L" + diagnostic.line + ":" + diagnostic.column + " [" + diagnostic.code + "] " + diagnostic.message,
                    Math.max(20, panelW / 7))), x + 18, diagnosticsY + 30 + index * 13, ERROR, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == GLFW_KEY_S) {
            deployAndBind();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_ENTER) {
            validateSource();
            return true;
        }
        return super.keyPressed(event);
    }

    void accept(SpellForgeProtocol.Frame frame) {
        if (frame.type() != SpellForgeProtocol.Type.ARC_RESPONSE) return;
        String action = pending.remove(frame.requestId());
        if (action == null) return;
        try {
            JsonObject response = JsonParser.parseString(frame.payload()).getAsJsonObject();
            if (!response.get("ok").getAsBoolean()) {
                JsonObject error = response.getAsJsonObject("error");
                fail(error.get("code").getAsString() + ": " + error.get("message").getAsString());
                return;
            }
            JsonObject result = response.getAsJsonObject("result");
            switch (action) {
                case "listSpells" -> acceptSpellList(result);
                case "inspectCore" -> acceptCore(result);
                case "openSpell" -> acceptOpen(result);
                case "validate" -> acceptValidation(result, false);
                case "deploy" -> acceptDeploy(result);
                case "bindCore" -> {
                    coreStatus = text(result, "status", "READY");
                    status = "保存した術式をARCSYS_COREへ反映しました";
                    statusColor = OK;
                    dirty = false;
                }
                default -> { }
            }
        } catch (RuntimeException error) {
            fail("サーバー応答を解析できませんでした");
        }
    }

    private void acceptSpellList(JsonObject result) {
        spells.clear();
        JsonArray values = result.getAsJsonArray("spells");
        if (values != null) values.forEach(value -> {
            JsonObject spell = value.getAsJsonObject();
            spells.add(new SpellChoice(UUID.fromString(spell.get("id").getAsString()),
                    spell.get("name").getAsString(), spell.get("revision").getAsLong()));
        });
        refreshSpellButton();
    }

    private void acceptCore(JsonObject result) {
        coreStatus = text(result, "status", "UNKNOWN");
        String id = text(result, "spellId", "00000000-0000-0000-0000-000000000000");
        UUID bound = UUID.fromString(id);
        if (bound.getMostSignificantBits() == 0 && bound.getLeastSignificantBits() == 0) {
            status = "新規術式を編集できます。Ctrl+Sで保存・Core反映します";
            statusColor = CYAN;
            return;
        }
        spellId = bound;
        baseRevision = number(result, "revision");
        JsonObject payload = new JsonObject();
        payload.addProperty("spellId", spellId.toString());
        request("openSpell", payload);
    }

    private void acceptOpen(JsonObject result) {
        spellId = UUID.fromString(result.get("spellId").getAsString());
        baseRevision = number(result, "revision");
        spellName = text(result, "name", "ArcSys術式");
        applySource(text(result, "source", defaultSource()), false);
        diagnostics = List.of();
        status = spellName + " を読み込みました";
        statusColor = OK;
    }

    private void acceptValidation(JsonObject result, boolean deploying) {
        diagnostics = diagnostics(result);
        boolean valid = result.has("valid") && result.get("valid").getAsBoolean();
        if (valid) {
            status = deploying ? "検証成功。保存処理を続行します…" : "検証成功 — エラーはありません";
            statusColor = OK;
        } else {
            status = "検証失敗 — 下のエラーを修正してください";
            statusColor = ERROR;
        }
    }

    private void acceptDeploy(JsonObject result) {
        acceptValidation(result, true);
        if (!result.has("valid") || !result.get("valid").getAsBoolean()) return;
        spellId = UUID.fromString(result.get("spellId").getAsString());
        baseRevision = number(result, "revision");
        spellName = text(result, "spell", spellName);
        JsonObject bind = corePayload();
        bind.addProperty("spellId", spellId.toString());
        bind.addProperty("revision", baseRevision);
        bind.addProperty("mode", "LATEST");
        request("bindCore", bind);
    }

    private void newSource() {
        spellId = null;
        baseRevision = 0;
        spellName = "新規術式";
        diagnostics = List.of();
        applySource(defaultSource(), true);
        status = "新しい術式を作成しました";
        statusColor = CYAN;
    }

    private void openNextSpell() {
        if (spells.isEmpty()) {
            fail("保存済み術式がありません");
            return;
        }
        SpellChoice choice = spells.get(selectedSpell);
        JsonObject payload = new JsonObject();
        payload.addProperty("spellId", choice.id.toString());
        request("openSpell", payload);
        selectedSpell = (selectedSpell + 1) % spells.size();
        refreshSpellButton();
    }

    private void validateSource() {
        if (!sourceReady()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source);
        request("validate", payload);
        status = "Paperサーバーで検証しています…";
        statusColor = WARNING;
    }

    private void deployAndBind() {
        if (!sourceReady()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source);
        if (spellId != null) payload.addProperty("spellId", spellId.toString());
        payload.addProperty("baseRevision", baseRevision);
        request("deploy", payload);
        status = "Paperサーバーで検証・保存しています…";
        statusColor = WARNING;
    }

    private boolean sourceReady() {
        if (source == null || source.isBlank()) {
            fail("ArcSysコードを入力してください");
            return false;
        }
        if (source.length() > MAX_SOURCE) {
            fail("コードが23,000文字の上限を超えています");
            return false;
        }
        return true;
    }

    private void applySource(String value, boolean changed) {
        source = value;
        if (editor != null) {
            applyingSource = true;
            editor.setValue(value);
            applyingSource = false;
        }
        dirty = changed;
    }

    private void refreshSpellButton() {
        if (spellButton == null) return;
        if (spells.isEmpty()) spellButton.setMessage(Component.literal("保存済みなし"));
        else spellButton.setMessage(Component.literal("開く: " + fit(spells.get(selectedSpell).name, 15)));
    }

    private void request(String type, JsonObject payload) {
        UUID id = UUID.randomUUID();
        pending.put(id, type);
        JsonObject request = new JsonObject();
        request.addProperty("version", 1);
        request.addProperty("id", id.toString());
        request.addProperty("type", type);
        request.add("payload", payload);
        MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.ARC_REQUEST,
                id, token, circuitId, baseRevision, request.toString()));
    }

    private JsonObject corePayload() {
        JsonObject payload = new JsonObject();
        payload.addProperty("circuitId", circuitId.toString());
        payload.addProperty("nodeId", nodeId.toString());
        return payload;
    }

    private List<Diagnostic> diagnostics(JsonObject result) {
        if (!result.has("diagnostics") || !result.get("diagnostics").isJsonArray()) return List.of();
        List<Diagnostic> values = new ArrayList<>();
        result.getAsJsonArray("diagnostics").forEach(value -> {
            JsonObject item = value.getAsJsonObject();
            values.add(new Diagnostic(text(item, "code", "ERROR"), text(item, "message", "不明なエラー"),
                    (int) number(item, "line"), (int) number(item, "column")));
        });
        return List.copyOf(values);
    }

    private String defaultSource() {
        String suffix = nodeId.toString().replace("-", "").substring(0, 8);
        return "spell CoreSpell_" + suffix + " {\n"
                + "  on cast {\n"
                + "    // ここに魔術処理を書きます\n"
                + "    return true;\n"
                + "  }\n"
                + "}\n";
    }

    private int lineCount() { return source == null || source.isEmpty() ? 0 : source.split("\\R", -1).length; }
    private void fail(String message) { status = message; statusColor = ERROR; }
    private static String text(JsonObject value, String key, String fallback) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback; }
    private static long number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsLong() : 0; }
    private static String fit(String value, int length) { return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "…"; }
    private int panelWidth() { return Math.max(420, Math.min(1_000, width - 24)); }
    private int panelHeight() { return Math.max(300, Math.min(650, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record SpellChoice(UUID id, String name, long revision) { }
    private record Diagnostic(String code, String message, int line, int column) { }
}
