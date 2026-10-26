package net.monacraft.spellforge.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.monacraft.spellforge.model.SpellModel.CircuitLibrary;
import net.monacraft.spellforge.model.SpellModel.CircuitSummary;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Minecraft-styled saved-spell picker opened by /msf open. */
final class SpellLibraryScreen extends Screen {
    private static final int ROWS_PER_PAGE = 8;
    private static final int BACKDROP = 0xD0080B12;
    private static final int PANEL_FACE = 0xFF252B34;
    private static final int TEXT_PRIMARY = 0xFFF4F4F4;
    private static final int TEXT_MUTED = 0xFF9DA9B7;
    private static final int SUCCESS = 0xFF79E0AC;
    private static final int WARNING = 0xFFFFD36A;
    private static final int ERROR = 0xFFFF7777;

    private final Screen parent;
    private final List<CircuitSummary> circuits;
    private final String token;
    private final List<Row> rows = new ArrayList<>();
    private UUID selected;
    private UUID deleteArmed;
    private long deleteDeadline;
    private int page;
    private Component status;
    private int statusColor;
    private Button editButton;
    private Button toggleButton;
    private Button deleteButton;

    SpellLibraryScreen(Screen parent, CircuitLibrary library, String token) {
        super(Component.literal("MonaSpellForge — 魔術一覧"));
        this.parent = parent;
        this.circuits = library.circuits();
        this.token = token;
        this.status = Component.literal(library.message().isBlank()
                ? (circuits.isEmpty() ? "保存済みの魔術はありません" : "再編集する魔術を選択してください")
                : library.message());
        this.statusColor = library.message().isBlank() ? TEXT_MUTED : SUCCESS;
    }

    Screen parentScreen() { return parent; }

    @Override
    protected void init() {
        rows.clear();
        int left = panelLeft();
        int top = panelTop();
        int rowWidth = panelWidth() - 48;
        int start = page * ROWS_PER_PAGE;
        int end = Math.min(circuits.size(), start + ROWS_PER_PAGE);
        for (int index = start; index < end; index++) {
            CircuitSummary summary = circuits.get(index);
            int y = top + 56 + (index - start) * 30;
            Button button = Button.builder(rowLabel(summary, false), ignored -> select(summary.circuitId()))
                    .bounds(left + 24, y, rowWidth, 24).build();
            rows.add(new Row(summary, button));
            addRenderableWidget(button);
        }

        int pages = pageCount();
        Button previous = Button.builder(Component.literal("<"), ignored -> changePage(-1))
                .bounds(left + panelWidth() - 86, top + 17, 28, 20).build();
        previous.active = page > 0;
        addRenderableWidget(previous);
        Button next = Button.builder(Component.literal(">"), ignored -> changePage(1))
                .bounds(left + panelWidth() - 52, top + 17, 28, 20).build();
        next.active = page + 1 < pages;
        addRenderableWidget(next);

        int y = top + panelHeight() - 31;
        int gap = 5;
        int buttonWidth = (panelWidth() - 48 - gap * 5) / 6;
        int x = left + 24;
        addRenderableWidget(Button.builder(Component.literal("新しい魔術"), ignored -> createCircuit())
                .bounds(x, y, buttonWidth, 20).build());
        editButton = addRenderableWidget(Button.builder(Component.literal("再編集"), ignored -> editSelected())
                .bounds(x + (buttonWidth + gap), y, buttonWidth, 20).build());
        toggleButton = addRenderableWidget(Button.builder(Component.literal("有効化"), ignored -> toggleSelected())
                .bounds(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal("削除"), ignored -> deleteSelected())
                .bounds(x + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("更新"), ignored -> requestRefresh())
                .bounds(x + (buttonWidth + gap) * 4, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("閉じる"), ignored -> onClose())
                .bounds(x + (buttonWidth + gap) * 5, y, buttonWidth, 20).build());
        refreshSelectionState();
    }

    void accept(SpellForgeProtocol.Frame frame) {
        if (frame.type() == SpellForgeProtocol.Type.ERROR) {
            status = Component.literal("操作に失敗しました: " + readableError(frame.payload()));
            statusColor = ERROR;
            deleteArmed = null;
            refreshSelectionState();
        }
    }

    void requestRefresh() {
        send(SpellForgeProtocol.Type.CIRCUIT_LIBRARY_REQ, null);
        status = Component.literal("一覧を更新しています…");
        statusColor = WARNING;
    }

    private void select(UUID circuitId) {
        selected = circuitId;
        deleteArmed = null;
        CircuitSummary summary = selectedSummary();
        status = Component.literal(summary == null ? "魔術を選択してください"
                : (summary.active() ? "有効: " : "無効: ") + summary.name() + " — ノード " + summary.nodeCount() + ", 配線 " + summary.edgeCount()
                + ", Revision " + summary.revision());
        statusColor = summary != null && summary.active() ? SUCCESS : TEXT_PRIMARY;
        refreshSelectionState();
    }

    private void refreshSelectionState() {
        boolean hasSelection = selectedSummary() != null;
        if (editButton != null) editButton.active = hasSelection;
        if (toggleButton != null) {
            toggleButton.active = hasSelection;
            CircuitSummary summary = selectedSummary();
            toggleButton.setMessage(Component.literal(summary != null && summary.active() ? "無効化" : "有効化"));
        }
        if (deleteButton != null) {
            deleteButton.active = hasSelection;
            deleteButton.setMessage(Component.literal(deleteArmed != null ? "削除を確定" : "削除"));
        }
        for (Row row : rows) row.button.setMessage(rowLabel(row.summary, row.summary.circuitId().equals(selected)));
    }

    private void createCircuit() {
        send(SpellForgeProtocol.Type.CIRCUIT_CREATE_REQ, null);
        status = Component.literal("新しい魔術を準備しています…");
        statusColor = WARNING;
    }

    private void editSelected() {
        CircuitSummary summary = selectedSummary();
        if (summary == null) return;
        send(SpellForgeProtocol.Type.CIRCUIT_OPEN_REQ, summary.circuitId());
        status = Component.literal("「" + summary.name() + "」を開いています…");
        statusColor = WARNING;
    }

    private void toggleSelected() {
        CircuitSummary summary = selectedSummary();
        if (summary == null) return;
        send(SpellForgeProtocol.Type.CIRCUIT_TOGGLE_REQ, summary.circuitId());
        status = Component.literal("「" + summary.name() + "」を" + (summary.active() ? "無効化" : "有効化") + "しています…");
        statusColor = WARNING;
    }

    private void deleteSelected() {
        CircuitSummary summary = selectedSummary();
        if (summary == null) return;
        long now = System.currentTimeMillis();
        if (!summary.circuitId().equals(deleteArmed) || now > deleteDeadline) {
            deleteArmed = summary.circuitId();
            deleteDeadline = now + 5_000;
            status = Component.literal("削除する場合は5秒以内に「削除を確定」を押してください");
            statusColor = ERROR;
            refreshSelectionState();
            return;
        }
        send(SpellForgeProtocol.Type.CIRCUIT_DELETE_REQ, summary.circuitId());
        status = Component.literal("削除しています…");
        statusColor = WARNING;
        deleteArmed = null;
        refreshSelectionState();
    }

    private void changePage(int amount) {
        page = Math.max(0, Math.min(page + amount, pageCount() - 1));
        selected = null;
        deleteArmed = null;
        rebuildWidgets();
    }

    private CircuitSummary selectedSummary() {
        if (selected == null) return null;
        return circuits.stream().filter(value -> value.circuitId().equals(selected)).findFirst().orElse(null);
    }

    private void send(SpellForgeProtocol.Type type, UUID circuitId) {
        MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(type, UUID.randomUUID(), token,
                circuitId, 0, ""));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKDROP);
        int left = panelLeft();
        int top = panelTop();
        drawPanel(graphics, left, top, panelWidth(), panelHeight());
        graphics.fill(left + 3, top + 3, left + panelWidth() - 3, top + 45, 0xFF1D222A);
        graphics.text(font, Component.literal("MonaSpellForge — 魔術一覧"), left + 14, top + 15, TEXT_PRIMARY, false);
        String pageText = (page + 1) + " / " + pageCount();
        graphics.text(font, Component.literal(pageText), left + panelWidth() - 128, top + 22, TEXT_MUTED, false);
        graphics.text(font, status, left + 24, top + panelHeight() - 50, statusColor, false);
        if (circuits.isEmpty()) {
            graphics.centeredText(font, Component.literal("まだ保存された魔術がありません"),
                    left + panelWidth() / 2, top + 155, TEXT_MUTED);
            graphics.centeredText(font, Component.literal("「新しい魔術」から作成できます"),
                    left + panelWidth() / 2, top + 174, SUCCESS);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x + 3, y + 4, x + w + 3, y + h + 4, 0xA0000000);
        graphics.fill(x, y, x + w, y + h, 0xFF171B22);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF69717C);
        graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, PANEL_FACE);
    }

    private Component rowLabel(CircuitSummary summary, boolean selectedRow) {
        return Component.literal((selectedRow ? "▶ " : "  ") + (summary.active() ? "[ON] " : "[OFF] ") + summary.name()
                + "   [ノード " + summary.nodeCount() + " / Rev." + summary.revision() + "]");
    }

    private int pageCount() { return Math.max(1, (circuits.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE); }
    private int panelWidth() { return Math.max(500, Math.min(680, width - 24)); }
    private int panelHeight() { return Math.max(350, Math.min(390, height - 30)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }

    private String readableError(String code) {
        return switch (code) {
            case "CIRCUIT_NOT_FOUND" -> "魔術が見つからないか、所有者が一致しません";
            case "INVALID_SESSION" -> "編集セッションの期限が切れました。/msf open を実行してください";
            default -> code;
        };
    }

    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }

    private record Row(CircuitSummary summary, Button button) { }
}
