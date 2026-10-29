package net.monacraft.spellforge.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.MultilineTextField;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

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
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private List<Diagnostic> diagnostics = List.of();
    private MultiLineEditBox editor;
    private EditBox searchBox;
    private Button searchNextButton;
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
    private boolean draftChecked;
    private boolean draftRestored;
    private boolean explicitOpen;
    private boolean searchVisible;
    private long lastEditAt;
    private long draftDueAt;
    private String draftState = "下書き未保存";

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
        loadDraftOnce();
        int x = panelLeft(), y = panelTop(), panelW = panelWidth(), editorH = Math.max(120, panelHeight() - 158);
        int buttonArea = panelW - 32, gap = 5, buttonW = Math.max(62, (buttonArea - gap * 5) / 6);
        int bx = x + 16, by = y + 35;
        addRenderableWidget(Button.builder(Component.literal("新規"), button -> newSource())
                .bounds(bx, by, buttonW, 20).build());
        spellButton = addRenderableWidget(Button.builder(Component.literal("保存済みを開く"), button -> openNextSpell())
                .bounds(bx + (buttonW + gap), by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("下書き保存"), button -> saveDraft(true))
                .bounds(bx + (buttonW + gap) * 2, by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("検証  Ctrl+Enter"), button -> validateSource())
                .bounds(bx + (buttonW + gap) * 3, by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("保存・反映  Ctrl+S"), button -> deployAndBind())
                .bounds(bx + (buttonW + gap) * 4, by, buttonW, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), button -> onClose())
                .bounds(bx + (buttonW + gap) * 5, by, buttonW, 20).build());

        searchBox = new EditBox(font, x + panelW - 264, y + 8, 190, 20, Component.literal("コード検索"));
        searchBox.setHint(Component.literal("検索…"));
        searchBox.setMaxLength(128);
        searchBox.visible = searchVisible;
        addRenderableWidget(searchBox);
        searchNextButton = addRenderableWidget(Button.builder(Component.literal("次へ"), button -> findNext())
                .bounds(x + panelW - 69, y + 8, 52, 20).build());
        searchNextButton.visible = searchVisible;

        editor = MultiLineEditBox.builder()
                .setX(x + 16).setY(y + 64)
                .setPlaceholder(Component.literal("ここにArcSysコードを入力してください"))
                .setTextColor(TEXT).setCursorColor(CYAN).setTextShadow(false)
                .setShowBackground(true).setShowDecorations(true)
                .build(font, panelW - 32, editorH, Component.literal("ArcSysソースコード"));
        editor.setCharacterLimit(MAX_SOURCE);
        editor.setLineLimit(1_000);
        editor.setValueListener(value -> {
            if (applyingSource) {
                source = value;
                return;
            }
            long now = System.currentTimeMillis();
            if (!value.equals(source) && now - lastEditAt > 700) pushUndo(source);
            source = value;
            dirty = true;
            redo.clear();
            lastEditAt = now;
            draftDueAt = now + 1_500;
            draftState = "下書き保存待ち";
        });
        addRenderableWidget(editor);
        applySource(source.isBlank() ? defaultSource() : source, wasDirty || draftRestored);
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
        if (!searchVisible) graphics.text(font, Component.literal(fit(meta, Math.max(20, panelW / 7))), x + 235, y + 14, dirty ? WARNING : CYAN, false);

        int diagnosticsY = y + panelH - 66;
        graphics.fill(x + 12, diagnosticsY - 5, x + panelW - 12, y + panelH - 10, 0xFF111722);
        graphics.text(font, Component.literal("Core: " + coreStatus + "  |  " + cursorPosition() + "  |  " + lineCount() + "行 / " + source.length() + "文字  |  " + draftState),
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
        if (searchVisible && searchBox != null && searchBox.isFocused()) {
            if (event.key() == GLFW_KEY_ESCAPE) {
                toggleSearch(false);
                return true;
            }
            if (event.key() == GLFW_KEY_ENTER) {
                findNext();
                return true;
            }
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_F) {
            toggleSearch(true);
            return true;
        }
        if (event.hasControlDown() && event.hasShiftDown() && event.key() == GLFW_KEY_S) {
            saveDraft(true);
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_S) {
            deployAndBind();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_ENTER) {
            validateSource();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_Z) {
            undo();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_Y) {
            redo();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_SLASH) {
            toggleLineComment();
            return true;
        }
        if (editor != null && editor.isFocused() && event.key() == GLFW_KEY_TAB) {
            if (textField().hasSelection()) indentSelection(event.hasShiftDown());
            else if (event.hasShiftDown()) outdentCurrentLine();
            else textField().insertText("  ");
            return true;
        }
        if (editor != null && editor.isFocused() && event.key() == GLFW_KEY_ENTER) {
            insertSmartNewline();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (editor != null && editor.isFocused() && !searchVisible) {
            String typed = event.codepointAsString();
            if (typed.equals("{") || typed.equals("(") || typed.equals("[")) {
                String close = typed.equals("{") ? "}" : typed.equals("(") ? ")" : "]";
                textField().insertText(typed + close);
                textField().seekCursor(Whence.RELATIVE, -1);
                return true;
            }
            if (typed.equals("}") || typed.equals(")") || typed.equals("]")) {
                int cursor = textField().cursor();
                if (cursor < source.length() && source.startsWith(typed, cursor)) {
                    textField().seekCursor(Whence.RELATIVE, 1);
                    return true;
                }
            }
        }
        return super.charTyped(event);
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty && draftDueAt > 0 && System.currentTimeMillis() >= draftDueAt) {
            draftDueAt = 0;
            saveDraft(false);
        }
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
                    draftRestored = false;
                    deleteDraft();
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
        if (draftRestored && !explicitOpen) {
            status = "端末に一時保存されていた下書きを復元しました";
            statusColor = WARNING;
        } else {
            applySource(text(result, "source", defaultSource()), false);
            draftRestored = false;
            status = spellName + " を読み込みました";
            statusColor = OK;
        }
        explicitOpen = false;
        diagnostics = List.of();
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
        draftRestored = false;
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
        explicitOpen = true;
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
        if (changed) draftDueAt = System.currentTimeMillis() + 1_500;
    }

    private void insertSmartNewline() {
        MultilineTextField field = textField();
        int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
        int lineStart = source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        String current = source.substring(lineStart, cursor);
        String indent = current.substring(0, current.length() - current.stripLeading().length());
        boolean opening = cursor > 0 && source.charAt(cursor - 1) == '{';
        boolean closing = cursor < source.length() && source.charAt(cursor) == '}';
        if (opening && closing) {
            field.insertText("\n" + indent + "  \n" + indent);
            field.seekCursor(Whence.RELATIVE, -(indent.length() + 1));
        } else field.insertText("\n" + indent + (opening ? "  " : ""));
    }

    private void outdentCurrentLine() {
        MultilineTextField field = textField();
        int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
        int lineStart = source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int remove = source.startsWith("  ", lineStart) ? 2 : source.startsWith(" ", lineStart) ? 1 : 0;
        if (remove == 0) return;
        pushUndo(source);
        String updated = source.substring(0, lineStart) + source.substring(lineStart + remove);
        applySourceAtCursor(updated, Math.max(lineStart, cursor - remove), true);
    }

    private void indentSelection(boolean reverse) {
        MultilineTextField field = textField();
        String selectedText = field.getSelectedText();
        int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
        int selectionStart;
        int selectionEnd;
        if (cursor >= selectedText.length() && source.regionMatches(cursor - selectedText.length(), selectedText, 0, selectedText.length())) {
            selectionStart = cursor - selectedText.length();
            selectionEnd = cursor;
        } else {
            selectionStart = cursor;
            selectionEnd = Math.min(source.length(), cursor + selectedText.length());
        }
        int blockStart = source.lastIndexOf('\n', Math.max(0, selectionStart - 1)) + 1;
        int newline = source.indexOf('\n', selectionEnd);
        int blockEnd = newline < 0 ? source.length() : newline;
        String block = source.substring(blockStart, blockEnd);
        String[] lines = block.split("\\n", -1);
        StringBuilder changed = new StringBuilder(block.length() + lines.length * 2);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (reverse) {
                if (line.startsWith("  ")) line = line.substring(2);
                else if (line.startsWith(" ")) line = line.substring(1);
            } else line = "  " + line;
            if (index > 0) changed.append('\n');
            changed.append(line);
        }
        pushUndo(source);
        String updated = source.substring(0, blockStart) + changed + source.substring(blockEnd);
        applySource(updated, true);
        field = textField();
        field.seekCursor(Whence.ABSOLUTE, blockStart);
        field.setSelecting(true);
        field.seekCursor(Whence.ABSOLUTE, blockStart + changed.length());
        field.setSelecting(false);
    }

    private void toggleLineComment() {
        if (editor == null || !editor.isFocused()) return;
        MultilineTextField field = textField();
        int cursor = Math.max(0, Math.min(field.cursor(), source.length()));
        int lineStart = source.lastIndexOf('\n', Math.max(0, cursor - 1)) + 1;
        int lineEnd = source.indexOf('\n', cursor);
        if (lineEnd < 0) lineEnd = source.length();
        int contentStart = lineStart;
        while (contentStart < lineEnd && source.charAt(contentStart) == ' ') contentStart++;
        pushUndo(source);
        if (source.startsWith("// ", contentStart)) {
            String updated = source.substring(0, contentStart) + source.substring(contentStart + 3);
            applySourceAtCursor(updated, Math.max(contentStart, cursor - 3), true);
        } else if (source.startsWith("//", contentStart)) {
            String updated = source.substring(0, contentStart) + source.substring(contentStart + 2);
            applySourceAtCursor(updated, Math.max(contentStart, cursor - 2), true);
        } else {
            String updated = source.substring(0, contentStart) + "// " + source.substring(contentStart);
            applySourceAtCursor(updated, cursor + 3, true);
        }
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(source);
        applySourceAtCursor(undo.pop(), 0, true);
        status = "元に戻しました";
        statusColor = CYAN;
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(source);
        applySourceAtCursor(redo.pop(), 0, true);
        status = "やり直しました";
        statusColor = CYAN;
    }

    private void pushUndo(String value) {
        if (value == null || (!undo.isEmpty() && undo.peek().equals(value))) return;
        undo.push(value);
        while (undo.size() > 100) undo.removeLast();
    }

    private void applySourceAtCursor(String value, int cursor, boolean changed) {
        applySource(value, changed);
        MultilineTextField field = textField();
        field.seekCursor(Whence.ABSOLUTE, Math.max(0, Math.min(cursor, value.length())));
    }

    private void toggleSearch(boolean visible) {
        searchVisible = visible;
        if (searchBox != null) searchBox.visible = visible;
        if (searchNextButton != null) searchNextButton.visible = visible;
        if (visible) {
            setFocused(searchBox);
            searchBox.setFocused(true);
        } else {
            setFocused(editor);
            editor.setFocused(true);
        }
    }

    private void findNext() {
        if (searchBox == null || searchBox.getValue().isBlank()) return;
        String query = searchBox.getValue();
        MultilineTextField field = textField();
        int start = source.indexOf(query, Math.min(source.length(), field.cursor() + 1));
        if (start < 0) start = source.indexOf(query);
        if (start < 0) {
            fail("「" + query + "」は見つかりませんでした");
            return;
        }
        field.seekCursor(Whence.ABSOLUTE, start);
        field.setSelecting(true);
        field.seekCursor(Whence.ABSOLUTE, start + query.length());
        field.setSelecting(false);
        editor.setScrollAmount(Math.max(0, (field.getLineAtCursor() - 2) * font.lineHeight));
        status = "検索結果を選択しました";
        statusColor = CYAN;
    }

    private void loadDraftOnce() {
        if (draftChecked) return;
        draftChecked = true;
        try {
            var draft = ArcDraftStore.load(minecraft.gameDirectory.toPath(), circuitId, nodeId);
            if (draft.isPresent()) {
                source = draft.get();
                dirty = true;
                draftRestored = true;
                draftState = "一時保存を復元";
            }
        } catch (IOException error) {
            draftState = "下書き読込失敗";
        }
    }

    private void saveDraft(boolean manual) {
        if (source == null || source.isBlank()) return;
        try {
            ArcDraftStore.save(minecraft.gameDirectory.toPath(), circuitId, nodeId, source);
            draftState = manual ? "手動で一時保存済み" : "自動一時保存済み";
            if (manual) {
                status = "端末へ下書きを一時保存しました";
                statusColor = OK;
            }
        } catch (IOException error) {
            draftState = "下書き保存失敗";
            if (manual) fail("下書きを一時保存できませんでした");
        }
    }

    private void deleteDraft() {
        try {
            ArcDraftStore.delete(minecraft.gameDirectory.toPath(), circuitId, nodeId);
            draftState = "サーバーへ保存済み";
        } catch (IOException error) {
            draftState = "保存済み（下書き削除失敗）";
        }
    }

    private MultilineTextField textField() {
        return EditorTextAccess.get(editor);
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
    private String cursorPosition() {
        if (editor == null || source == null) return "行 1, 列 1";
        int cursor = Math.max(0, Math.min(textField().cursor(), source.length()));
        int line = 1, column = 1;
        for (int index = 0; index < cursor; index++) {
            if (source.charAt(index) == '\n') { line++; column = 1; }
            else column++;
        }
        return "行 " + line + ", 列 " + column;
    }
    private void fail(String message) { status = message; statusColor = ERROR; }
    private static String text(JsonObject value, String key, String fallback) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback; }
    private static long number(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsLong() : 0; }
    private static String fit(String value, int length) { return value.length() <= length ? value : value.substring(0, Math.max(1, length - 1)) + "…"; }
    private int panelWidth() { return Math.max(420, Math.min(1_000, width - 24)); }
    private int panelHeight() { return Math.max(300, Math.min(650, height - 24)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }

    @Override public void onClose() {
        if (dirty) saveDraft(false);
        minecraft.setScreen(parent);
    }
    @Override public boolean isPauseScreen() { return false; }

    private record SpellChoice(UUID id, String name, long revision) { }
    private record Diagnostic(String code, String message, int line, int column) { }
}
