package net.monacraft.spellforge.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.catalog.FragmentPresentation;
import net.monacraft.spellforge.compiler.ArcCircuitCodec;
import net.monacraft.spellforge.compiler.ArcCircuitCodec.PortableEdge;
import net.monacraft.spellforge.compiler.ArcCircuitCodec.PortableNode;
import net.monacraft.spellforge.compiler.CircuitCompiler;
import net.monacraft.spellforge.compiler.SpellMetrics;
import net.monacraft.spellforge.model.SpellModel.Category;
import net.monacraft.spellforge.model.SpellModel.Circuit;
import net.monacraft.spellforge.model.SpellModel.CircuitEdge;
import net.monacraft.spellforge.model.SpellModel.CircuitNode;
import net.monacraft.spellforge.model.SpellModel.CompilationResult;
import net.monacraft.spellforge.model.SpellModel.Direction;
import net.monacraft.spellforge.model.SpellModel.EditorPalette;
import net.monacraft.spellforge.model.SpellModel.FragmentDefinition;
import net.monacraft.spellforge.model.SpellModel.LevelParameters;
import net.monacraft.spellforge.model.SpellModel.Multiplicity;
import net.monacraft.spellforge.model.SpellModel.OwnedFragment;
import net.monacraft.spellforge.model.SpellModel.PortDefinition;
import net.monacraft.spellforge.model.SpellModel.PortType;
import net.monacraft.spellforge.model.SpellModel.ValidationIssue;
import net.monacraft.spellforge.protocol.SpellForgeProtocol;

import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

public final class SpellForgeScreen extends Screen {
    private static final FragmentCatalog CATALOG = FragmentCatalog.defaults();
    private static final CircuitCompiler COMPILER = new CircuitCompiler(CATALOG);

    private static final int PANEL_MAX_WIDTH = 1_100;
    private static final int PANEL_MAX_HEIGHT = 650;
    private static final int HEADER_HEIGHT = 34;
    private static final int FOOTER_HEIGHT = 86;
    private static final int PALETTE_WIDTH = 220;
    private static final int NODE_WIDTH = 118;
    private static final int NODE_HEIGHT = 76;
    private static final double WORLD_UNIT = 150.0;
    private static final double WORLD_MIN = -64.0;
    private static final double WORLD_MAX = 68.0;
    private static final double POSITION_SNAP = 0.25;
    private static final int PAN_LIMIT = 24_000;

    private static final int BACKDROP = 0xD0080B12;
    private static final int PANEL_BORDER = 0xFF171B22;
    private static final int PANEL_HIGHLIGHT = 0xFF69717C;
    private static final int PANEL_FACE = 0xFF252B34;
    private static final int CANVAS_FACE = 0xFF151B24;
    private static final int CANVAS_DOT = 0xFF354354;
    private static final int TEXT_PRIMARY = 0xFFF4F4F4;
    private static final int TEXT_MUTED = 0xFF9DA9B7;
    private static final int CONNECTED = 0xFF79E0AC;
    private static final int STATUS_WARNING = 0xFFFFD36A;
    private static final int STATUS_ERROR = 0xFFFF7777;

    private final Screen parent;
    private final String token;
    private final Deque<Circuit> undo = new ArrayDeque<>();
    private final Deque<Circuit> redo = new ArrayDeque<>();

    private Circuit circuit;
    private CompilationResult validation;
    private List<OwnedFragment> palette = List.of();
    private Component status = Component.literal("所持欠片を読み込んでいます…");
    private int statusColor = STATUS_WARNING;
    private EditBox searchBox;
    private String searchQuery = "";
    private Category categoryFilter;
    private int paletteScroll;
    private double zoom = 1.0;
    private int panX;
    private int panY;

    private UUID draggingNode;
    private Circuit dragStartCircuit;
    private OwnedFragment draggingFragment;
    private PortDrag draggingPort;
    private boolean panning;
    private double lastMouseX;
    private double lastMouseY;
    private ContextMenu contextMenu;
    private UUID pendingVsCodeCore;
    private UUID pendingArcFileImport;

    SpellForgeScreen(Screen parent, Circuit circuit, String token) {
        super(Component.translatable("monaspellforge.title"));
        this.parent = parent;
        this.circuit = circuit;
        this.token = token;
        refreshValidation();
    }

    Screen parentScreen() { return parent; }

    @Override
    protected void init() {
        Rect paletteArea = paletteRect();
        searchBox = new EditBox(font, paletteArea.x + 8, paletteArea.y + 29,
                paletteArea.width - 16, 18, Component.literal("欠片検索"));
        searchBox.setHint(Component.literal("名前・属性・機能から検索"));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchQuery);
        searchBox.setResponder(value -> {
            searchQuery = value;
            paletteScroll = 0;
        });
        addRenderableWidget(searchBox);

        int buttonWidth = Math.max(48, Math.min(76, (panelWidth() - 52) / 7));
        int gap = 4;
        int totalWidth = buttonWidth * 7 + gap * 6;
        int x = panelLeft() + (panelWidth() - totalWidth) / 2;
        int y = panelTop() + panelHeight() - 27;
        addRenderableWidget(Button.builder(Component.translatable("monaspellforge.undo"), button -> undo())
                .bounds(x, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monaspellforge.redo"), button -> redo())
                .bounds(x + (buttonWidth + gap), y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monaspellforge.validate"), button -> send(SpellForgeProtocol.Type.VALIDATE_REQ))
                .bounds(x + (buttonWidth + gap) * 2, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monaspellforge.save"), button -> send(SpellForgeProtocol.Type.SAVE_REQ))
                .bounds(x + (buttonWidth + gap) * 3, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(".arc出力"), button -> exportArc())
                .bounds(x + (buttonWidth + gap) * 4, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal(".arc読込"), button -> importArc())
                .bounds(x + (buttonWidth + gap) * 5, y, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("monaspellforge.close"), button -> onClose())
                .bounds(x + (buttonWidth + gap) * 6, y, buttonWidth, 20).build());
    }

    void accept(SpellForgeProtocol.Frame frame) {
        switch (frame.type()) {
            case EDITOR_DATA -> acceptPalette(frame.payload());
            case VALIDATE_RESULT -> acceptValidation(frame.payload());
            case SAVE_RESULT -> {
                try {
                    JsonObject response = JsonParser.parseString(frame.payload()).getAsJsonObject();
                    if (response.has("saved") && !response.get("saved").getAsBoolean()) {
                        pendingVsCodeCore = null;
                        setError("保存できませんでした: " + readableError(response.has("message")
                                ? response.get("message").getAsString() : "SERVER_REJECTED"));
                        return;
                    }
                    circuit = MonaSpellForgeClient.gson().fromJson(response.get("circuit"), Circuit.class);
                } catch (RuntimeException ignored) {
                    circuit = new Circuit(circuit.circuitId(), circuit.ownerId(), circuit.name(), frame.baseRevision(),
                            circuit.schemaVersion(), circuit.nodes(), circuit.edges(), circuit.createdAt(), circuit.updatedAt());
                }
                status = Component.literal("保存しました（Revision " + frame.baseRevision() + "）");
                statusColor = CONNECTED;
                if (pendingVsCodeCore != null) {
                    UUID targetNode = pendingVsCodeCore;
                    pendingVsCodeCore = null;
                    String code = ArcSysDeveloperBridge.start(minecraft, token, circuit.circuitId(), targetNode);
                    if (code.equals("起動失敗")) setError("VS Code Bridgeを起動できませんでした");
                    else {
                        status = Component.literal("VS Codeコード: " + code + " — 保存すると選択中のCoreへ自動反映");
                        statusColor = CONNECTED;
                    }
                }
            }
            case CONFLICT -> setError("別の画面で更新されています。/msf open で開き直してください");
            case ARC_FILE_IMPORT_RESULT -> acceptArcFileImport(frame);
            case ERROR -> setError("通信エラー: " + readableError(frame.payload()));
            default -> { }
        }
    }

    private void acceptPalette(String payload) {
        try {
            EditorPalette data = MonaSpellForgeClient.fromJson(payload, EditorPalette.class);
            palette = data.fragments().stream()
                    .sorted(Comparator.comparingInt((OwnedFragment item) -> definition(item.fragmentId()).category().ordinal())
                            .thenComparing(item -> FragmentPresentation.get(item.fragmentId()).plainName())
                            .thenComparingInt(OwnedFragment::level))
                    .toList();
            status = Component.literal(palette.size() + "個の所持欠片を読み込みました");
            statusColor = CONNECTED;
        } catch (RuntimeException error) {
            setError("所持欠片の一覧を読み込めませんでした");
        }
    }

    private void send(SpellForgeProtocol.Type type) {
        UUID request = UUID.randomUUID();
        MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(type, request, token, circuit.circuitId(),
                circuit.revision(), MonaSpellForgeClient.json(circuit)));
        status = Component.literal(type == SpellForgeProtocol.Type.SAVE_REQ ? "保存要求を送信しました" : "サーバーで検証しています…");
        statusColor = STATUS_WARNING;
    }

    private void exportArc() {
        status = Component.literal("ダウンロードフォルダーへ.arcを保存しています…");
        statusColor = STATUS_WARNING;
        Circuit snapshot = circuit;
        Thread.startVirtualThread(() -> {
            try {
                Path destination = ArcFileTransfer.exportToDownloads(snapshot);
                minecraft.execute(() -> {
                    status = Component.literal(destination.getFileName() + " をダウンロードフォルダーへ保存しました");
                    statusColor = CONNECTED;
                });
            } catch (IOException | RuntimeException error) {
                minecraft.execute(() -> setError(".arcを保存できませんでした: " + readableFileError(error)));
            }
        });
    }

    private void importArc() {
        if (GraphicsEnvironment.isHeadless()) {
            setError("この環境ではファイル選択画面を開けません");
            return;
        }
        status = Component.literal("読み込む.arcファイルを選択してください…");
        statusColor = STATUS_WARNING;
        Thread.startVirtualThread(() -> {
            File selected = chooseArcFile();
            if (selected == null) {
                minecraft.execute(() -> {
                    status = Component.literal(".arcの読み込みをキャンセルしました");
                    statusColor = TEXT_MUTED;
                });
                return;
            }
            try {
                String source = ArcFileTransfer.readArc(selected.toPath());
                minecraft.execute(() -> applyImportedArc(source));
            } catch (IOException | RuntimeException error) {
                minecraft.execute(() -> setError(".arcを読み込めませんでした: " + readableFileError(error)));
            }
        });
    }

    private File chooseArcFile() {
        FileDialog dialog = new FileDialog((Frame) null, "MonaSpellForge — .arcを読み込む", FileDialog.LOAD);
        dialog.setDirectory(ArcFileTransfer.downloadsDirectory().toString());
        dialog.setFile("*.arc");
        dialog.setFilenameFilter((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".arc"));
        dialog.setVisible(true);
        File[] selected = dialog.getFiles();
        dialog.dispose();
        return selected.length == 0 ? null : selected[0];
    }

    private void applyImportedArc(String source) {
        if (!ArcCircuitCodec.hasEmbeddedCircuit(source)) {
            deployImportedArc(source);
            return;
        }
        try {
            ArcCircuitCodec.PortableCircuit imported = ArcCircuitCodec.fromArcSource(source);

            Map<PaletteKey, ArrayDeque<OwnedFragment>> available = new HashMap<>();
            Set<UUID> added = new HashSet<>();
            for (CircuitNode node : circuit.nodes()) {
                OwnedFragment fragment = new OwnedFragment(node.fragmentInstanceId(), node.fragmentId(), node.level());
                available.computeIfAbsent(new PaletteKey(fragment.fragmentId(), fragment.level()), ignored -> new ArrayDeque<>()).add(fragment);
                added.add(fragment.instanceId());
            }
            for (OwnedFragment fragment : palette) if (added.add(fragment.instanceId())) {
                available.computeIfAbsent(new PaletteKey(fragment.fragmentId(), fragment.level()), ignored -> new ArrayDeque<>()).add(fragment);
            }

            Map<String, UUID> nodeIds = new HashMap<>();
            List<CircuitNode> nodes = new ArrayList<>();
            for (PortableNode node : imported.nodes()) {
                String fragmentId = net.monacraft.spellforge.model.SpellModel.normalizeId(node.fragmentId());
                ArrayDeque<OwnedFragment> candidates = available.get(new PaletteKey(fragmentId, node.level()));
                if (candidates == null || candidates.isEmpty()) throw new IllegalArgumentException("missing:" + fragmentId);
                OwnedFragment owned = candidates.removeFirst();
                UUID nodeId = UUID.randomUUID(); nodeIds.put(node.key(), nodeId);
                nodes.add(new CircuitNode(nodeId, nodes.size(), owned.instanceId(), fragmentId,
                        node.level(), node.parameters(), clamp(node.x(), WORLD_MIN, WORLD_MAX), clamp(node.y(), WORLD_MIN, WORLD_MAX)));
            }
            List<CircuitEdge> edges = new ArrayList<>();
            for (PortableEdge edge : imported.edges()) {
                UUID sourceId = nodeIds.get(edge.source()), targetId = nodeIds.get(edge.target());
                if (sourceId == null || targetId == null) throw new IllegalArgumentException("edge");
                edges.add(new CircuitEdge(UUID.randomUUID(), sourceId, edge.sourcePort(), targetId, edge.targetPort()));
            }
            saveHistory();
            circuit = circuit.withContent(nodes, edges);
            refreshValidation();
            status = Component.literal(".arcからノード回路を取り込みました。検証後に保存してください");
            statusColor = STATUS_WARNING;
        } catch (RuntimeException error) {
            setError(error.getMessage() != null && error.getMessage().startsWith("missing:")
                    ? "必要な所持欠片が不足しています: " + error.getMessage().substring(8)
                    : "対応するMonaSpellForge .arcではありません");
        }
    }

    private void deployImportedArc(String source) {
        byte[] encoded = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encoded.length > 23_000) {
            setError("ArcSysソースが転送上限（23 KB）を超えています。VS Code Bridgeを使用してください");
            return;
        }
        UUID requestId = UUID.randomUUID();
        JsonObject payload = new JsonObject();
        payload.addProperty("source", source);
        payload.addProperty("explicit", true);
        pendingArcFileImport = requestId;
        MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(
                SpellForgeProtocol.Type.ARC_FILE_IMPORT_REQ, requestId, token, null, 0, payload.toString()));
        status = Component.literal("ArcSysソースをサーバーで検証・読み込み中です…");
        statusColor = STATUS_WARNING;
    }

    private void acceptArcFileImport(SpellForgeProtocol.Frame frame) {
        if (pendingArcFileImport == null || !pendingArcFileImport.equals(frame.requestId())) return;
        pendingArcFileImport = null;
        try {
            JsonObject response = JsonParser.parseString(frame.payload()).getAsJsonObject();
            if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
                String message = response.has("message") ? response.get("message").getAsString() : "SERVER_REJECTED";
                setError("ArcSysソースを読み込めませんでした: " + message);
                return;
            }
            JsonObject result = response.getAsJsonObject("result");
            if (result == null || !result.has("valid") || !result.get("valid").getAsBoolean()) {
                String message = "構文または型にエラーがあります";
                if (result != null && result.has("diagnostics") && result.getAsJsonArray("diagnostics").size() > 0) {
                    JsonObject diagnostic = result.getAsJsonArray("diagnostics").get(0).getAsJsonObject();
                    message = jsonText(diagnostic, "message", message)
                            + "（" + (diagnostic.has("line") ? diagnostic.get("line").getAsInt() : 1) + "行目）";
                }
                setError("ArcSysソースを読み込めませんでした: " + message);
                return;
            }
            long revision = result.has("revision") ? result.get("revision").getAsLong() : 1;
            status = Component.literal("ArcSys術式として読み込みました（Revision " + revision + "）");
            statusColor = CONNECTED;
        } catch (RuntimeException error) {
            setError("ArcSysソースの応答を処理できませんでした: " + readableFileError(error));
        }
    }

    private static String readableFileError(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void acceptValidation(String payload) {
        try {
            JsonArray issues = JsonParser.parseString(payload).getAsJsonArray();
            if (issues.isEmpty()) {
                status = Component.literal("サーバー検証: 回路は有効です");
                statusColor = CONNECTED;
                return;
            }
            JsonObject first = issues.get(0).getAsJsonObject();
            String message = jsonText(first, "message", jsonText(first, "code", "回路を確認してください"));
            String suggestion = jsonText(first, "suggestion", "");
            setError(message + (suggestion.isBlank() ? "" : " — " + suggestion));
        } catch (RuntimeException ignored) {
            setError("回路を検証できませんでした");
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, BACKDROP);
        int left = panelLeft();
        int top = panelTop();
        drawMinecraftPanel(graphics, left, top, panelWidth(), panelHeight(), PANEL_FACE);
        drawHeader(graphics, left, top, panelWidth());

        Rect canvas = canvasRect();
        Rect paletteArea = paletteRect();
        drawCanvas(graphics, canvas, mouseX, mouseY);
        drawPalette(graphics, paletteArea, mouseX, mouseY);
        drawFooter(graphics, left, top, panelWidth(), panelHeight());
        if (contextMenu != null) drawContextMenu(graphics, contextMenu, mouseX, mouseY);
        drawHoverDetails(graphics, canvas, paletteArea, mouseX, mouseY);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private void drawHeader(GuiGraphicsExtractor graphics, int left, int top, int panelWidth) {
        graphics.fill(left + 2, top + 2, left + panelWidth - 2, top + HEADER_HEIGHT - 2, 0xFF1D222A);
        String name = circuit.name().isBlank() ? "名称未設定の魔術" : circuit.name();
        graphics.text(font, fit(name + ".msf", panelWidth - 150), left + 12, top + 12, TEXT_PRIMARY, false);
        int labelWidth = font.width("接続中");
        int labelX = left + panelWidth - labelWidth - 12;
        drawPort(graphics, labelX - 10, top + 16, CONNECTED, false);
        graphics.text(font, Component.literal("接続中"), labelX, top + 12, CONNECTED, false);
    }

    private void drawCanvas(GuiGraphicsExtractor graphics, Rect canvas, int mouseX, int mouseY) {
        drawInsetPanel(graphics, canvas.x, canvas.y, canvas.width, canvas.height);
        graphics.enableScissor(canvas.x + 2, canvas.y + 2, canvas.right() - 2, canvas.bottom() - 2);
        int dotStep = Math.max(8, (int) Math.round(16 * zoom));
        int dotSize = Math.max(1, (int) Math.round(2 * zoom));
        for (int dotY = canvas.y + 12 + floorMod(panY, dotStep); dotY < canvas.bottom() - 8; dotY += dotStep) {
            for (int dotX = canvas.x + 12 + floorMod(panX, dotStep); dotX < canvas.right() - 8; dotX += dotStep) {
                graphics.fill(dotX, dotY, dotX + dotSize, dotY + dotSize, CANVAS_DOT);
            }
        }

        if (circuit.nodes().isEmpty() && draggingFragment == null) {
            int centerX = canvas.x + canvas.width / 2;
            int centerY = canvas.y + canvas.height / 2;
            drawMinecraftPanel(graphics, centerX - 114, centerY - 31, 228, 62, 0xFF242B35);
            graphics.centeredText(font, Component.literal("右の所持欠片をドラッグ"), centerX, centerY - 14, TEXT_PRIMARY);
            graphics.centeredText(font, Component.literal("好きなマスへ配置してください"), centerX, centerY + 4, TEXT_MUTED);
        }

        Map<UUID, NodeBox> boxes = layoutNodes(canvas);
        Set<UUID> invalidEdges = invalidEdgeIds();
        for (CircuitEdge edge : circuit.edges()) drawEdge(graphics, edge, boxes, invalidEdges.contains(edge.edgeId()));
        for (CircuitNode node : circuit.nodes()) {
            NodeBox box = boxes.get(node.nodeId());
            if (box != null) drawNode(graphics, node, box, mouseX, mouseY);
        }

        if (draggingPort != null) {
            drawCurve(graphics, draggingPort.startX, draggingPort.startY, mouseX, mouseY,
                    portTypeColor(draggingPort.port.type()), false);
        }
        if (draggingFragment != null) {
            FragmentDefinition definition = definition(draggingFragment.fragmentId());
            drawNodePreview(graphics, draggingFragment, definition,
                    mouseX - scaledNodeWidth() / 2, mouseY - scaledNodeHeight() / 2);
        }
        graphics.disableScissor();
    }

    private void drawPalette(GuiGraphicsExtractor graphics, Rect area, int mouseX, int mouseY) {
        drawMinecraftPanel(graphics, area.x, area.y, area.width, area.height, 0xFF222A34);
        graphics.text(font, Component.literal("所持ノード"), area.x + 9, area.y + 10, TEXT_PRIMARY, false);
        drawCategoryTabs(graphics, area, mouseX, mouseY);
        List<PaletteGroup> filtered = filteredPalette();
        int listTop = paletteListTop(area);
        int rowHeight = 36;
        int visible = Math.max(1, (area.bottom() - listTop - 22) / rowHeight);
        int maxScroll = Math.max(0, filtered.size() - visible);
        paletteScroll = Math.min(paletteScroll, maxScroll);
        Set<UUID> used = usedFragmentIds();

        for (int row = 0; row < visible; row++) {
            int index = paletteScroll + row;
            if (index >= filtered.size()) break;
            PaletteGroup group = filtered.get(index);
            OwnedFragment fragment = group.representative();
            int rowY = listTop + row * rowHeight;
            boolean hovered = mouseX >= area.x + 6 && mouseX <= area.right() - 6
                    && mouseY >= rowY && mouseY < rowY + 32;
            int placedCount = group.placedCount(used);
            boolean placed = placedCount >= group.fragments().size();
            int accent = categoryColor(definition(fragment.fragmentId()).category());
            graphics.fill(area.x + 6, rowY, area.right() - 6, rowY + 32,
                    hovered ? 0xFF46515F : 0xFF303A46);
            graphics.fill(area.x + 6, rowY, area.x + 9, rowY + 32, accent);
            FragmentPresentation.Entry presentation = FragmentPresentation.get(fragment.fragmentId());
            String amount = "×" + group.fragments().size();
            graphics.text(font, fit(presentation.plainName(), area.width - 42 - font.width(amount)), area.x + 14, rowY + 6,
                    placed ? 0xFF808993 : TEXT_PRIMARY, false);
            graphics.text(font, Component.literal(amount), area.right() - 12 - font.width(amount), rowY + 6,
                    placed ? 0xFF808993 : TEXT_PRIMARY, false);
            String detail = "Lv." + fragment.level() + " ・ " + FragmentPresentation.categoryName(definition(fragment.fragmentId()).category());
            graphics.text(font, fit(detail, area.width - 67), area.x + 14, rowY + 19, TEXT_MUTED, false);
            if (placedCount > 0) {
                String placement = placedCount + "/" + group.fragments().size();
                graphics.text(font, Component.literal(placement), area.right() - 12 - font.width(placement), rowY + 19,
                        placed ? 0xFF6FCA9B : 0xFFFFD36A, false);
            }
        }

        if (filtered.isEmpty()) {
            graphics.centeredText(font, Component.literal(palette.isEmpty() ? "所持欠片がありません" : "検索結果なし"),
                    area.x + area.width / 2, listTop + 18, TEXT_MUTED);
        }
        String page = filtered.isEmpty() ? "0 / 0" : (paletteScroll + 1) + "-" + Math.min(filtered.size(), paletteScroll + visible) + " / " + filtered.size();
        graphics.centeredText(font, Component.literal(page), area.x + area.width / 2, area.bottom() - 13, TEXT_MUTED);
    }

    private void drawCategoryTabs(GuiGraphicsExtractor graphics, Rect area, int mouseX, int mouseY) {
        CategoryTab[] tabs = CategoryTab.values();
        int gap = 2;
        int tabWidth = (area.width - 12 - gap * (tabs.length - 1)) / tabs.length;
        int x = area.x + 6;
        int y = area.y + 51;
        for (CategoryTab tab : tabs) {
            boolean selected = tab.category == categoryFilter;
            boolean hovered = mouseX >= x && mouseX < x + tabWidth && mouseY >= y && mouseY < y + 18;
            int accent = tab.category == null ? 0xFF607080 : categoryColor(tab.category);
            graphics.fill(x, y, x + tabWidth, y + 18, selected ? accent : hovered ? 0xFF46515F : 0xFF303A46);
            graphics.centeredText(font, Component.literal(tab.label), x + tabWidth / 2, y + 5,
                    selected ? 0xFFFFFFFF : TEXT_MUTED);
            x += tabWidth + gap;
        }
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int left, int top, int panelWidth, int panelHeight) {
        int statusX = left + 9;
        int metricsY = top + panelHeight - FOOTER_HEIGHT + 5;
        int statusWidth = panelWidth - 18;
        drawInsetPanel(graphics, statusX, metricsY, statusWidth, 22);
        String metricsText;
        int metricsColor = 0xFFB8DFFF;
        if (validation != null && validation.valid()) {
            SpellMetrics.CombatMetrics metrics = SpellMetrics.calculate(validation.spell());
            metricsText = "攻撃力: " + decimal(metrics.attackPower())
                    + "  |  DPS: " + decimal(metrics.dps())
                    + "  |  連射力: " + decimal(metrics.fireRate()) + "/秒"
                    + "  |  消費魔力: " + (int) Math.ceil(metrics.manaCost());
            if (metrics.armorPenetrationPercent() > 0) metricsText += "  |  防御貫通: " + (int) Math.round(metrics.armorPenetrationPercent()) + "%";
            if (metrics.trueDamage()) { metricsText += "  |  TRUE DAMAGE"; metricsColor = STATUS_ERROR; }
        } else {
            metricsText = "攻撃力: --  |  DPS: --  |  連射力: --  |  消費魔力: --";
            metricsColor = TEXT_MUTED;
        }
        graphics.centeredText(font, Component.literal(fit(metricsText, statusWidth - 16)),
                statusX + statusWidth / 2, metricsY + 7, metricsColor);

        int statusY = metricsY + 24;
        drawInsetPanel(graphics, statusX, statusY, statusWidth, 22);
        String count = "ノード " + circuit.nodes().size() + "/25 ・ 配線 " + circuit.edges().size() + "/64"
                + " ・ " + Math.round(zoom * 100) + "%";
        graphics.text(font, Component.literal(count), statusX + 8, statusY + 7, 0xFF9EC7E8, false);
        String message = fit(status.getString(), statusWidth - font.width(count) - 34);
        graphics.text(font, Component.literal(message), statusX + statusWidth - font.width(message) - 8,
                statusY + 7, statusColor, false);
    }

    private void drawNode(GuiGraphicsExtractor graphics, CircuitNode node, NodeBox box, int mouseX, int mouseY) {
        FragmentDefinition definition = definition(node.fragmentId());
        FragmentPresentation.Entry presentation = FragmentPresentation.get(node.fragmentId());
        boolean hovered = box.contains(mouseX, mouseY);
        boolean invalid = invalidNodeIds().contains(node.nodeId());
        int accent = invalid ? STATUS_ERROR : categoryColor(definition.category());
        graphics.pose().pushMatrix();
        graphics.pose().translate(box.x, box.y);
        graphics.pose().scale((float) zoom);
        graphics.fill(3, 4, NODE_WIDTH + 3, NODE_HEIGHT + 4, 0x80000000);
        graphics.fill(0, 0, NODE_WIDTH, NODE_HEIGHT, hovered ? 0xFFF2F2F2 : 0xFF66717E);
        graphics.fill(2, 2, NODE_WIDTH - 2, NODE_HEIGHT - 2, 0xFF283240);
        graphics.fill(2, 2, NODE_WIDTH - 2, 23, accent);
        graphics.text(font, fit(presentation.plainName(), NODE_WIDTH - 14), 7, 8, TEXT_PRIMARY, true);
        graphics.text(font, Component.literal(FragmentPresentation.categoryName(definition.category())),
                8, 31, 0xFFD7DFE8, false);
        LevelParameters parameters = definition.levels().get(node.level());
        graphics.text(font, Component.literal("Lv." + node.level() + "  MP " + (int) Math.ceil(parameters.manaCost())),
                8, 48, TEXT_MUTED, false);
        if (invalid) graphics.text(font, Component.literal("!"), NODE_WIDTH - 12, 31, STATUS_ERROR, true);
        graphics.pose().popMatrix();

        for (PortHit port : portsFor(node, box)) {
            boolean portHover = port.contains(mouseX, mouseY);
            drawPort(graphics, port.x, port.y, portTypeColor(port.port.type()), portHover, zoom);
        }
    }

    private void drawNodePreview(GuiGraphicsExtractor graphics, OwnedFragment fragment, FragmentDefinition definition, int x, int y) {
        int accent = categoryColor(definition.category());
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale((float) zoom);
        graphics.fill(0, 0, NODE_WIDTH, NODE_HEIGHT, 0xE0EEF3F8);
        graphics.fill(2, 2, NODE_WIDTH - 2, NODE_HEIGHT - 2, 0xE0283240);
        graphics.fill(2, 2, NODE_WIDTH - 2, 23, accent);
        graphics.text(font, fit(FragmentPresentation.get(fragment.fragmentId()).plainName(), NODE_WIDTH - 14),
                7, 8, TEXT_PRIMARY, true);
        graphics.text(font, Component.literal("Lv." + fragment.level()), 8, 36, TEXT_MUTED, false);
        graphics.pose().popMatrix();
    }

    private void drawEdge(GuiGraphicsExtractor graphics, CircuitEdge edge, Map<UUID, NodeBox> boxes, boolean invalid) {
        CircuitNode sourceNode = node(edge.sourceNodeId());
        CircuitNode targetNode = node(edge.targetNodeId());
        if (sourceNode == null || targetNode == null) return;
        PortHit source = port(sourceNode, boxes.get(sourceNode.nodeId()), edge.sourcePortId());
        PortHit target = port(targetNode, boxes.get(targetNode.nodeId()), edge.targetPortId());
        if (source == null || target == null) return;
        drawCurve(graphics, source.x, source.y, target.x, target.y,
                invalid ? STATUS_ERROR : portTypeColor(source.port.type()), invalid);
    }

    private void drawCurve(GuiGraphicsExtractor graphics, int startX, int startY, int endX, int endY, int color, boolean dashed) {
        double direction = endX >= startX ? 1.0 : -1.0;
        double bend = Math.max(24, Math.abs(endX - startX) * 0.45) * direction;
        double previousX = startX;
        double previousY = startY;
        for (int step = 1; step <= 28; step++) {
            double t = step / 28.0;
            double inverse = 1.0 - t;
            double nextX = inverse * inverse * inverse * startX
                    + 3 * inverse * inverse * t * (startX + bend)
                    + 3 * inverse * t * t * (endX - bend)
                    + t * t * t * endX;
            double nextY = inverse * inverse * inverse * startY
                    + 3 * inverse * inverse * t * startY
                    + 3 * inverse * t * t * endY
                    + t * t * t * endY;
            if (!dashed || step % 3 != 0) {
                drawPixelLine(graphics, (int) Math.round(previousX), (int) Math.round(previousY),
                        (int) Math.round(nextX), (int) Math.round(nextY), color);
            }
            previousX = nextX;
            previousY = nextY;
        }
    }

    private void drawHoverDetails(GuiGraphicsExtractor graphics, Rect canvas, Rect paletteArea, int mouseX, int mouseY) {
        if (contextMenu != null || draggingFragment != null || draggingPort != null) return;
        OwnedFragment paletteHover = paletteAt(mouseX, mouseY);
        Map<UUID, NodeBox> boxes = layoutNodes(canvas);
        CircuitNode nodeHover = nodeAt(mouseX, mouseY, boxes);
        PortHit portHover = portAt(mouseX, mouseY, boxes);
        if (portHover != null) {
            List<String> lines = List.of(
                    "端子: " + portHover.port.id(),
                    "型: " + portHover.port.type() + " / " + (portHover.port.direction() == Direction.INPUT ? "入力" : "出力"),
                    "接続数: " + connectedCount(portHover.nodeId, portHover.port.id())
            );
            drawTooltip(graphics, mouseX, mouseY, "魔術ポート", lines, portTypeColor(portHover.port.type()));
            return;
        }
        if (nodeHover != null) {
            drawFragmentTooltip(graphics, mouseX, mouseY, nodeHover.fragmentId(), nodeHover.level(), issueFor(nodeHover.nodeId()));
        } else if (canvas.contains(mouseX, mouseY)) {
            CircuitEdge edgeHover = edgeAt(mouseX, mouseY, boxes);
            if (edgeHover != null) {
                drawTooltip(graphics, mouseX, mouseY, "魔術配線",
                        List.of("右クリック: この配線を切断"), 0xFF6DB7F0);
            }
        } else if (paletteHover != null) {
            drawFragmentTooltip(graphics, mouseX, mouseY, paletteHover.fragmentId(), paletteHover.level(), null);
        }
    }

    private void drawFragmentTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, String id, int level, ValidationIssue issue) {
        FragmentDefinition definition = definition(id);
        FragmentPresentation.Entry presentation = FragmentPresentation.get(id);
        LevelParameters parameters = definition.levels().get(level);
        List<String> lines = new ArrayList<>();
        lines.add(presentation.description());
        lines.add("威力 " + decimal(parameters.power()) + " ・ 射程 " + decimal(parameters.range()) + " ・ 持続 " + decimal(parameters.duration()));
        lines.add("消費魔力 " + (int) Math.ceil(parameters.manaCost()) + " ・ 実行負荷 " + parameters.runtimeCost());
        if (issue != null) lines.add("警告: " + issue.message());
        drawTooltip(graphics, mouseX, mouseY, presentation.plainName(), lines, categoryColor(definition.category()));
    }

    private void drawTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, String title, List<String> lines, int accent) {
        int tooltipWidth = 248;
        int tooltipHeight = 25 + lines.size() * 13;
        int x = Math.min(width - tooltipWidth - 6, mouseX + 12);
        int y = Math.min(height - tooltipHeight - 6, mouseY + 10);
        x = Math.max(6, x);
        y = Math.max(6, y);
        drawMinecraftPanel(graphics, x, y, tooltipWidth, tooltipHeight, 0xFF202832);
        graphics.fill(x + 3, y + 3, x + 7, y + tooltipHeight - 3, accent);
        graphics.text(font, fit(title, tooltipWidth - 24), x + 13, y + 8, TEXT_PRIMARY, true);
        for (int index = 0; index < lines.size(); index++) {
            int color = lines.get(index).startsWith("警告") ? STATUS_ERROR : TEXT_MUTED;
            graphics.text(font, fit(lines.get(index), tooltipWidth - 24), x + 13, y + 23 + index * 13, color, false);
        }
    }

    private void drawContextMenu(GuiGraphicsExtractor graphics, ContextMenu menu, int mouseX, int mouseY) {
        CircuitNode contextNode = menu.nodeId == null ? null : node(menu.nodeId);
        List<String> labels = menu.nodeId == null ? List.of("ノードを追加")
                : contextNode != null && contextNode.fragmentId().equals("ARCSYS_CORE")
                ? List.of("VS Codeで編集", "ArcSys制御パネル", "ノードを複製", "ノードをしまう", "ノードを追加")
                : List.of("ノードを複製", "ノードをしまう", "ノードを追加");
        int menuWidth = 156;
        int menuHeight = labels.size() * 22 + 6;
        int x = Math.min(menu.x, width - menuWidth - 4);
        int y = Math.min(menu.y, height - menuHeight - 4);
        drawMinecraftPanel(graphics, x, y, menuWidth, menuHeight, 0xFF252D38);
        for (int i = 0; i < labels.size(); i++) {
            int rowY = y + 3 + i * 22;
            boolean hover = mouseX >= x + 3 && mouseX <= x + menuWidth - 3 && mouseY >= rowY && mouseY < rowY + 22;
            if (hover) graphics.fill(x + 3, rowY, x + menuWidth - 3, rowY + 22, 0xFF536275);
            graphics.text(font, Component.literal(labels.get(i)), x + 9, rowY + 7, TEXT_PRIMARY, false);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        int button = event.button();

        if (contextMenu != null) {
            if (button == 0 && handleContextClick(mouseX, mouseY)) return true;
            contextMenu = null;
        }
        if (super.mouseClicked(event, doubleClick)) return true;

        Category category = categoryAt(mouseX, mouseY);
        if (button == 0 && categoryTabRect().contains(mouseX, mouseY)) {
            categoryFilter = category;
            paletteScroll = 0;
            return true;
        }

        Rect canvas = canvasRect();
        Map<UUID, NodeBox> boxes = layoutNodes(canvas);
        if (button == 2 && canvas.contains(mouseX, mouseY)) {
            beginPanning(event);
            return true;
        }
        if (button == 1) {
            CircuitNode node = nodeAt(mouseX, mouseY, boxes);
            if (node == null && canvas.contains(mouseX, mouseY)) {
                CircuitEdge edge = edgeAt(mouseX, mouseY, boxes);
                if (edge != null) {
                    disconnectEdge(edge.edgeId());
                    return true;
                }
            }
            contextMenu = new ContextMenu(mouseX, mouseY, node == null ? null : node.nodeId());
            return true;
        }
        if (button != 0) return false;

        PortHit port = portAt(mouseX, mouseY, boxes);
        if (port != null) {
            draggingPort = new PortDrag(port.nodeId, port.port, port.x, port.y);
            return true;
        }
        CircuitNode node = nodeAt(mouseX, mouseY, boxes);
        if (node != null) {
            draggingNode = node.nodeId();
            dragStartCircuit = circuit;
            return true;
        }
        OwnedFragment fragment = paletteAt(mouseX, mouseY);
        if (fragment != null && !usedFragmentIds().contains(fragment.instanceId())) {
            draggingFragment = fragment;
            return true;
        }
        if (canvas.contains(mouseX, mouseY)) {
            beginPanning(event);
            return true;
        }
        return false;
    }

    private void beginPanning(MouseButtonEvent event) {
        panning = true;
        lastMouseX = event.x();
        lastMouseY = event.y();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        if (panning) {
            panX = (int) clamp(panX + Math.round(event.x() - lastMouseX), -PAN_LIMIT, PAN_LIMIT);
            panY = (int) clamp(panY + Math.round(event.y() - lastMouseY), -PAN_LIMIT, PAN_LIMIT);
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }
        if (draggingNode != null) {
            moveNodePreview(draggingNode, mouseX, mouseY);
            return true;
        }
        if (draggingFragment != null || draggingPort != null) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        int mouseX = (int) event.x();
        int mouseY = (int) event.y();
        boolean handled = false;
        if (panning) {
            panning = false;
            handled = true;
        }
        if (draggingFragment != null) {
            if (canvasRect().contains(mouseX, mouseY)) addFragment(draggingFragment, mouseX, mouseY);
            draggingFragment = null;
            handled = true;
        }
        if (draggingPort != null) {
            finishPortDrag(mouseX, mouseY);
            draggingPort = null;
            handled = true;
        }
        if (draggingNode != null) {
            Circuit before = dragStartCircuit;
            draggingNode = null;
            dragStartCircuit = null;
            if (before != null && !before.equals(circuit)) {
                undo.push(before);
                redo.clear();
                refreshValidation();
            }
            handled = true;
        }
        return handled || super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (paletteRect().contains(mouseX, mouseY)) {
            int direction = vertical > 0 ? -1 : 1;
            paletteScroll = Math.max(0, paletteScroll + direction);
            return true;
        }
        if (canvasRect().contains(mouseX, mouseY)) {
            zoom = clamp(zoom + vertical * 0.08, 0.65, 1.55);
            status = Component.literal("表示倍率 " + Math.round(zoom * 100) + "%");
            statusColor = TEXT_MUTED;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == GLFW_KEY_Z) {
            undo();
            return true;
        }
        if (event.hasControlDown() && event.key() == GLFW_KEY_Y) {
            redo();
            return true;
        }
        if (event.isEscape() && contextMenu != null) {
            contextMenu = null;
            return true;
        }
        return super.keyPressed(event);
    }

    private void addFragment(OwnedFragment fragment, int mouseX, int mouseY) {
        if (circuit.nodes().size() >= CircuitCompiler.MAX_NODES) {
            setError("ノード上限25に達しています");
            return;
        }
        if (usedFragmentIds().contains(fragment.instanceId())) {
            setError("この欠片はすでに配置されています");
            return;
        }
        int slot = closestFreeSlot(0, null);
        if (slot < 0) {
            setError("配置できる空きマスがありません");
            return;
        }
        WorldPoint point = screenToWorld(mouseX, mouseY);
        saveHistory();
        CircuitNode node = new CircuitNode(UUID.randomUUID(), slot, fragment.instanceId(), fragment.fragmentId(),
                fragment.level(), Map.of(), point.x, point.y);
        List<CircuitNode> nodes = new ArrayList<>(circuit.nodes());
        nodes.add(node);
        circuit = circuit.withContent(nodes, circuit.edges());
        refreshValidation();
    }

    private void moveNodePreview(UUID nodeId, int mouseX, int mouseY) {
        WorldPoint point = screenToWorld(mouseX, mouseY);
        List<CircuitNode> nodes = circuit.nodes().stream().map(node -> node.nodeId().equals(nodeId)
                ? new CircuitNode(node.nodeId(), node.slotIndex(), node.fragmentInstanceId(), node.fragmentId(), node.level(),
                node.parameters(), point.x, point.y) : node).toList();
        circuit = circuit.withContent(nodes, circuit.edges());
    }

    private void finishPortDrag(int mouseX, int mouseY) {
        PortHit target = portAt(mouseX, mouseY, layoutNodes(canvasRect()));
        if (target == null || target.nodeId.equals(draggingPort.nodeId) && target.port.id().equals(draggingPort.port.id())) return;

        UUID sourceNode;
        PortDefinition sourcePort;
        UUID targetNode;
        PortDefinition targetPort;
        if (draggingPort.port.direction() == Direction.OUTPUT && target.port.direction() == Direction.INPUT) {
            sourceNode = draggingPort.nodeId;
            sourcePort = draggingPort.port;
            targetNode = target.nodeId;
            targetPort = target.port;
        } else if (draggingPort.port.direction() == Direction.INPUT && target.port.direction() == Direction.OUTPUT) {
            sourceNode = target.nodeId;
            sourcePort = target.port;
            targetNode = draggingPort.nodeId;
            targetPort = draggingPort.port;
        } else {
            setError("出力ポートから入力ポートへ接続してください");
            return;
        }
        if (sourcePort.type() != targetPort.type()) {
            setError(sourcePort.type() + " と " + targetPort.type() + " は接続できません");
            return;
        }
        boolean duplicate = circuit.edges().stream().anyMatch(edge -> edge.sourceNodeId().equals(sourceNode)
                && edge.sourcePortId().equals(sourcePort.id()) && edge.targetNodeId().equals(targetNode)
                && edge.targetPortId().equals(targetPort.id()));
        if (duplicate) {
            setError("同じ配線がすでに存在します");
            return;
        }
        if (circuit.edges().size() >= CircuitCompiler.MAX_EDGES) {
            setError("配線上限64に達しています");
            return;
        }
        saveHistory();
        List<CircuitEdge> edges = new ArrayList<>(circuit.edges());
        edges.add(new CircuitEdge(UUID.randomUUID(), sourceNode, sourcePort.id(), targetNode, targetPort.id()));
        circuit = circuit.withContent(circuit.nodes(), edges);
        refreshValidation();
    }

    private void disconnectEdge(UUID edgeId) {
        if (circuit.edges().stream().noneMatch(edge -> edge.edgeId().equals(edgeId))) return;
        saveHistory();
        List<CircuitEdge> edges = circuit.edges().stream()
                .filter(edge -> !edge.edgeId().equals(edgeId))
                .toList();
        circuit = circuit.withContent(circuit.nodes(), edges);
        refreshValidation();
        status = Component.literal("配線を切断しました");
        statusColor = CONNECTED;
    }

    private boolean handleContextClick(int mouseX, int mouseY) {
        ContextMenu menu = contextMenu;
        CircuitNode contextNode = menu.nodeId == null ? null : node(menu.nodeId);
        List<ContextAction> actions = menu.nodeId == null ? List.of(ContextAction.SEARCH)
                : contextNode != null && contextNode.fragmentId().equals("ARCSYS_CORE")
                ? List.of(ContextAction.EDIT_CORE_VSCODE, ContextAction.CONFIGURE_CORE, ContextAction.DUPLICATE, ContextAction.STOW, ContextAction.SEARCH)
                : List.of(ContextAction.DUPLICATE, ContextAction.STOW, ContextAction.SEARCH);
        int width = 156;
        int x = Math.min(menu.x, this.width - width - 4);
        int height = actions.size() * 22 + 6;
        int y = Math.min(menu.y, this.height - height - 4);
        if (mouseX < x || mouseX > x + width || mouseY < y + 3 || mouseY >= y + height - 3) return false;
        int index = (mouseY - y - 3) / 22;
        if (index < 0 || index >= actions.size()) return false;
        ContextAction action = actions.get(index);
        contextMenu = null;
        switch (action) {
            case SEARCH -> {
                searchBox.setFocused(true);
                setFocused(searchBox);
            }
            case STOW -> stowNode(menu.nodeId);
            case DUPLICATE -> duplicateNode(menu.nodeId);
            case CONFIGURE_CORE -> minecraft.setScreen(new ArcCoreScreen(this, token, circuit.circuitId(), menu.nodeId));
            case EDIT_CORE_VSCODE -> openCoreInVsCode(menu.nodeId);
        }
        return true;
    }

    private void openCoreInVsCode(UUID nodeId) {
        if (nodeId == null || node(nodeId) == null) {
            setError("術式演算核が見つかりません");
            return;
        }
        pendingVsCodeCore = nodeId;
        send(SpellForgeProtocol.Type.SAVE_REQ);
        status = Component.literal("回路を保存してVS Code接続を準備しています…");
        statusColor = STATUS_WARNING;
    }

    private void stowNode(UUID nodeId) {
        if (nodeId == null) return;
        saveHistory();
        List<CircuitNode> nodes = circuit.nodes().stream().filter(node -> !node.nodeId().equals(nodeId)).toList();
        List<CircuitEdge> edges = circuit.edges().stream().filter(edge -> !edge.sourceNodeId().equals(nodeId)
                && !edge.targetNodeId().equals(nodeId)).toList();
        circuit = circuit.withContent(nodes, edges);
        refreshValidation();
        if (circuit.revision() == 0) {
            status = Component.literal("未保存ノードを所持一覧へ戻しました");
            statusColor = CONNECTED;
            return;
        }
        JsonObject request = new JsonObject();
        request.addProperty("nodeId", nodeId.toString());
        request.add("circuit", MonaSpellForgeClient.gson().toJsonTree(circuit));
        MonaSpellForgeClient.send(new SpellForgeProtocol.Frame(SpellForgeProtocol.Type.STOW_NODE_REQ,
                UUID.randomUUID(), token, circuit.circuitId(), circuit.revision(), request.toString()));
        status = Component.literal("ノードを所持一覧へ戻しています…");
        statusColor = STATUS_WARNING;
    }

    private void duplicateNode(UUID nodeId) {
        CircuitNode source = node(nodeId);
        if (source == null) return;
        Set<UUID> used = usedFragmentIds();
        OwnedFragment duplicate = palette.stream().filter(fragment -> fragment.fragmentId().equals(source.fragmentId())
                && !used.contains(fragment.instanceId())).findFirst().orElse(null);
        if (duplicate == null) {
            setError("複製には同種の未配置欠片がもう1個必要です");
            return;
        }
        int preferred = Math.min(24, source.slotIndex() + 1);
        int slot = closestFreeSlot(preferred, null);
        if (slot < 0) {
            setError("配置できる空きマスがありません");
            return;
        }
        saveHistory();
        List<CircuitNode> nodes = new ArrayList<>(circuit.nodes());
        nodes.add(new CircuitNode(UUID.randomUUID(), slot, duplicate.instanceId(), duplicate.fragmentId(),
                duplicate.level(), source.parameters(),
                clamp(snap(source.uiX() + 0.75), WORLD_MIN, WORLD_MAX),
                clamp(snap(source.uiY() + 0.75), WORLD_MIN, WORLD_MAX)));
        circuit = circuit.withContent(nodes, circuit.edges());
        refreshValidation();
    }

    private void saveHistory() {
        undo.push(circuit);
        while (undo.size() > 80) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(circuit);
        circuit = undo.pop();
        refreshValidation();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(circuit);
        circuit = redo.pop();
        refreshValidation();
    }

    private void refreshValidation() {
        validation = COMPILER.compile(circuit);
        if (validation.valid()) {
            status = Component.literal("有効な回路 ・ 消費魔力 " + (int) validation.spell().manaCost()
                    + " ・ 実行負荷 " + validation.spell().runtimeCost());
            statusColor = CONNECTED;
        } else if (!validation.issues().isEmpty()) {
            ValidationIssue issue = validation.issues().get(0);
            status = Component.literal(issue.message() + " — " + issue.suggestion());
            statusColor = STATUS_ERROR;
        }
    }

    private Map<UUID, NodeBox> layoutNodes(Rect canvas) {
        Map<UUID, NodeBox> result = new HashMap<>();
        double pivotX = canvas.x + canvas.width / 2.0;
        double pivotY = canvas.y + canvas.height / 2.0;
        for (CircuitNode node : circuit.nodes()) {
            double worldX = (clamp(node.uiX(), WORLD_MIN, WORLD_MAX) - 2.0) * WORLD_UNIT;
            double worldY = (clamp(node.uiY(), WORLD_MIN, WORLD_MAX) - 2.0) * WORLD_UNIT;
            int nodeX = (int) Math.round(pivotX + worldX * zoom + panX);
            int nodeY = (int) Math.round(pivotY + worldY * zoom + panY);
            result.put(node.nodeId(), new NodeBox(nodeX, nodeY, scaledNodeWidth(), scaledNodeHeight()));
        }
        return result;
    }

    private WorldPoint screenToWorld(int mouseX, int mouseY) {
        Rect canvas = canvasRect();
        double pivotX = canvas.x + canvas.width / 2.0;
        double pivotY = canvas.y + canvas.height / 2.0;
        double topX = mouseX - scaledNodeWidth() / 2.0;
        double topY = mouseY - scaledNodeHeight() / 2.0;
        double worldX = 2.0 + (topX - pivotX - panX) / zoom / WORLD_UNIT;
        double worldY = 2.0 + (topY - pivotY - panY) / zoom / WORLD_UNIT;
        return new WorldPoint(clamp(snap(worldX), WORLD_MIN, WORLD_MAX),
                clamp(snap(worldY), WORLD_MIN, WORLD_MAX));
    }

    private static double snap(double value) { return Math.round(value / POSITION_SNAP) * POSITION_SNAP; }

    private int closestFreeSlot(int preferred, UUID excludingNode) {
        Set<Integer> occupied = new HashSet<>();
        circuit.nodes().stream().filter(node -> !node.nodeId().equals(excludingNode)).forEach(node -> occupied.add(node.slotIndex()));
        if (!occupied.contains(preferred)) return preferred;
        int px = preferred % 5;
        int py = preferred / 5;
        int best = -1;
        int distance = Integer.MAX_VALUE;
        for (int slot = 0; slot < 25; slot++) {
            if (occupied.contains(slot)) continue;
            int dx = slot % 5 - px;
            int dy = slot / 5 - py;
            int candidate = dx * dx + dy * dy;
            if (candidate < distance) {
                best = slot;
                distance = candidate;
            }
        }
        return best;
    }

    private List<PortHit> portsFor(CircuitNode node, NodeBox box) {
        if (box == null) return List.of();
        FragmentDefinition definition = definition(node.fragmentId());
        List<PortDefinition> inputs = definition.ports().stream().filter(port -> port.direction() == Direction.INPUT).toList();
        List<PortDefinition> outputs = definition.ports().stream().filter(port -> port.direction() == Direction.OUTPUT).toList();
        List<PortHit> result = new ArrayList<>();
        addPorts(result, node.nodeId(), inputs, box.x, box.y);
        addPorts(result, node.nodeId(), outputs, box.right(), box.y);
        return result;
    }

    private void addPorts(List<PortHit> target, UUID nodeId, List<PortDefinition> ports, int x, int y) {
        for (int index = 0; index < ports.size(); index++) {
            int localY = 30 + (index + 1) * Math.max(8, (NODE_HEIGHT - 34) / (ports.size() + 1));
            int portY = y + (int) Math.round(localY * zoom);
            target.add(new PortHit(nodeId, ports.get(index), x, portY));
        }
    }

    private PortHit port(CircuitNode node, NodeBox box, String portId) {
        return portsFor(node, box).stream().filter(port -> port.port.id().equals(portId)).findFirst().orElse(null);
    }

    private PortHit portAt(int mouseX, int mouseY, Map<UUID, NodeBox> boxes) {
        for (CircuitNode node : circuit.nodes()) {
            for (PortHit port : portsFor(node, boxes.get(node.nodeId()))) if (port.contains(mouseX, mouseY)) return port;
        }
        return null;
    }

    private CircuitNode nodeAt(int mouseX, int mouseY, Map<UUID, NodeBox> boxes) {
        for (int index = circuit.nodes().size() - 1; index >= 0; index--) {
            CircuitNode node = circuit.nodes().get(index);
            NodeBox box = boxes.get(node.nodeId());
            if (box != null && box.contains(mouseX, mouseY)) return node;
        }
        return null;
    }

    private OwnedFragment paletteAt(int mouseX, int mouseY) {
        Rect area = paletteRect();
        if (!area.contains(mouseX, mouseY)) return null;
        int row = (mouseY - paletteListTop(area)) / 36;
        if (row < 0) return null;
        List<PaletteGroup> filtered = filteredPalette();
        int index = paletteScroll + row;
        if (index < 0 || index >= filtered.size()) return null;
        PaletteGroup group = filtered.get(index);
        Set<UUID> used = usedFragmentIds();
        return group.fragments().stream().filter(fragment -> !used.contains(fragment.instanceId()))
                .findFirst().orElse(group.representative());
    }

    private List<PaletteGroup> filteredPalette() {
        String query = searchQuery.trim().toLowerCase(Locale.ROOT);
        Map<PaletteKey, List<OwnedFragment>> groups = new LinkedHashMap<>();
        palette.stream().filter(fragment -> {
            FragmentDefinition definition = definition(fragment.fragmentId());
            if (categoryFilter != null && definition.category() != categoryFilter) return false;
            if (query.isEmpty()) return true;
            String search = FragmentPresentation.get(fragment.fragmentId()).searchText(definition.category())
                    + " " + definition.rarity().toLowerCase(Locale.ROOT) + " lv" + fragment.level();
            for (String token : query.split("\\s+")) if (!search.contains(token)) return false;
            return true;
        }).forEach(fragment -> groups.computeIfAbsent(new PaletteKey(fragment.fragmentId(), fragment.level()), ignored -> new ArrayList<>())
                .add(fragment));
        return groups.entrySet().stream().map(entry -> new PaletteGroup(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    private Category categoryAt(int mouseX, int mouseY) {
        Rect rect = categoryTabRect();
        if (!rect.contains(mouseX, mouseY)) return categoryFilter;
        CategoryTab[] tabs = CategoryTab.values();
        int gap = 2;
        int tabWidth = (rect.width - gap * (tabs.length - 1)) / tabs.length;
        int relative = mouseX - rect.x;
        int index = relative / (tabWidth + gap);
        if (index < 0 || index >= tabs.length || relative % (tabWidth + gap) >= tabWidth) return categoryFilter;
        return tabs[index].category;
    }

    private Rect categoryTabRect() {
        Rect area = paletteRect();
        return new Rect(area.x + 6, area.y + 51, area.width - 12, 18);
    }

    private int paletteListTop(Rect area) { return area.y + 75; }

    private Set<UUID> usedFragmentIds() {
        Set<UUID> used = new LinkedHashSet<>();
        circuit.nodes().forEach(node -> used.add(node.fragmentInstanceId()));
        return used;
    }

    private Set<UUID> invalidNodeIds() {
        Set<UUID> result = new HashSet<>();
        if (validation == null) return result;
        for (ValidationIssue issue : validation.issues()) {
            try { result.add(UUID.fromString(issue.nodeOrEdgeId())); } catch (Exception ignored) { }
        }
        return result;
    }

    private Set<UUID> invalidEdgeIds() {
        Set<UUID> edges = circuit.edges().stream().map(CircuitEdge::edgeId).collect(java.util.stream.Collectors.toSet());
        Set<UUID> result = new HashSet<>();
        if (validation == null) return result;
        for (ValidationIssue issue : validation.issues()) {
            try {
                UUID id = UUID.fromString(issue.nodeOrEdgeId());
                if (edges.contains(id)) result.add(id);
            } catch (Exception ignored) { }
        }
        return result;
    }

    private CircuitEdge edgeAt(int mouseX, int mouseY, Map<UUID, NodeBox> boxes) {
        List<CircuitEdge> edges = circuit.edges();
        for (int index = edges.size() - 1; index >= 0; index--) {
            CircuitEdge edge = edges.get(index);
            CircuitNode sourceNode = node(edge.sourceNodeId());
            CircuitNode targetNode = node(edge.targetNodeId());
            if (sourceNode == null || targetNode == null) continue;
            PortHit source = port(sourceNode, boxes.get(sourceNode.nodeId()), edge.sourcePortId());
            PortHit target = port(targetNode, boxes.get(targetNode.nodeId()), edge.targetPortId());
            if (source != null && target != null && curveContains(source.x, source.y, target.x, target.y, mouseX, mouseY)) {
                return edge;
            }
        }
        return null;
    }

    private static boolean curveContains(int startX, int startY, int endX, int endY, int mouseX, int mouseY) {
        double direction = endX >= startX ? 1.0 : -1.0;
        double bend = Math.max(24, Math.abs(endX - startX) * 0.45) * direction;
        double previousX = startX;
        double previousY = startY;
        for (int step = 1; step <= 36; step++) {
            double t = step / 36.0;
            double inverse = 1.0 - t;
            double nextX = inverse * inverse * inverse * startX
                    + 3 * inverse * inverse * t * (startX + bend)
                    + 3 * inverse * t * t * (endX - bend)
                    + t * t * t * endX;
            double nextY = inverse * inverse * inverse * startY
                    + 3 * inverse * inverse * t * startY
                    + 3 * inverse * t * t * endY
                    + t * t * t * endY;
            if (distanceToSegment(mouseX, mouseY, previousX, previousY, nextX, nextY) <= 5.0) return true;
            previousX = nextX;
            previousY = nextY;
        }
        return false;
    }

    private static double distanceToSegment(double pointX, double pointY, double startX, double startY,
                                            double endX, double endY) {
        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double lengthSquared = deltaX * deltaX + deltaY * deltaY;
        if (lengthSquared == 0) return Math.hypot(pointX - startX, pointY - startY);
        double projection = ((pointX - startX) * deltaX + (pointY - startY) * deltaY) / lengthSquared;
        projection = clamp(projection, 0.0, 1.0);
        return Math.hypot(pointX - (startX + projection * deltaX), pointY - (startY + projection * deltaY));
    }

    private ValidationIssue issueFor(UUID nodeId) {
        if (validation == null) return null;
        return validation.issues().stream().filter(issue -> nodeId.toString().equals(issue.nodeOrEdgeId())).findFirst().orElse(null);
    }

    private int connectedCount(UUID nodeId, String portId) {
        return (int) circuit.edges().stream().filter(edge -> edge.sourceNodeId().equals(nodeId) && edge.sourcePortId().equals(portId)
                || edge.targetNodeId().equals(nodeId) && edge.targetPortId().equals(portId)).count();
    }

    private CircuitNode node(UUID id) {
        return circuit.nodes().stream().filter(node -> node.nodeId().equals(id)).findFirst().orElse(null);
    }

    private static FragmentDefinition definition(String id) {
        FragmentDefinition definition = CATALOG.get(id);
        if (definition == null) throw new IllegalArgumentException("Unknown fragment: " + id);
        return definition;
    }

    private static String jsonText(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static String readableError(String code) {
        return switch (code) {
            case "INVALID_SESSION" -> "編集セッションの有効期限が切れました";
            case "OWNER_MISMATCH" -> "この回路を編集する権限がありません";
            case "MISSING_FRAGMENT" -> "必要な欠片がインベントリにありません";
            default -> code;
        };
    }

    private void setError(String message) {
        status = Component.literal(message);
        statusColor = STATUS_ERROR;
    }

    private String fit(String text, int maxWidth) {
        if (maxWidth <= 0) return "";
        if (font.width(text) <= maxWidth) return text;
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end) + "…") > maxWidth) end--;
        return text.substring(0, end) + "…";
    }

    private static String decimal(double value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static int categoryColor(Category category) {
        return switch (category) {
            case TRIGGER -> 0xFF3F9D83;
            case ELEMENT -> 0xFFC86A3C;
            case SHAPE -> 0xFF4D8FC4;
            case TARGET -> 0xFF478F9D;
            case MODIFIER -> 0xFF8E70C9;
            case LOGIC -> 0xFFC99A3E;
        };
    }

    private static int portTypeColor(PortType type) {
        return switch (type) {
            case EXEC -> 0xFFF2A955;
            case ENTITY, LOCATION -> 0xFF70C7AE;
            case NUMBER -> 0xFFAB8AFF;
            case BOOLEAN -> 0xFFE7C35D;
            case ELEMENT -> 0xFF6DB7F0;
            case PROJECTILE -> 0xFFFF8D72;
        };
    }

    private void drawMinecraftPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight, int face) {
        graphics.fill(x + 3, y + 4, x + panelWidth + 3, y + panelHeight + 4, 0xA0000000);
        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1, PANEL_HIGHLIGHT);
        graphics.fill(x + 3, y + 3, x + panelWidth - 3, y + panelHeight - 3, face);
        graphics.fill(x + 3, y + panelHeight - 4, x + panelWidth - 3, y + panelHeight - 3, 0xFF0B0E13);
        graphics.fill(x + panelWidth - 4, y + 3, x + panelWidth - 3, y + panelHeight - 3, 0xFF0B0E13);
    }

    private void drawInsetPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight) {
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xFF080B10);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + panelHeight - 2, CANVAS_FACE);
        graphics.fill(x + 2, y + 2, x + panelWidth - 2, y + 3, 0xFF0E1219);
        graphics.fill(x + 2, y + 2, x + 3, y + panelHeight - 2, 0xFF0E1219);
    }

    private static void drawPort(GuiGraphicsExtractor graphics, int centerX, int centerY, int color, boolean hovered) {
        drawPort(graphics, centerX, centerY, color, hovered, 1.0);
    }

    private static void drawPort(GuiGraphicsExtractor graphics, int centerX, int centerY, int color,
                                 boolean hovered, double scale) {
        int border = hovered ? 0xFFFFFFFF : 0xFFEEF3F8;
        int outer = Math.max(3, (int) Math.round(4 * scale));
        int inner = Math.max(2, (int) Math.round(2.5 * scale));
        graphics.fill(centerX - outer + 1, centerY - outer, centerX + outer, centerY + outer + 1, border);
        graphics.fill(centerX - outer, centerY - outer + 1, centerX + outer + 1, centerY + outer, border);
        graphics.fill(centerX - inner + 1, centerY - inner, centerX + inner, centerY + inner + 1, color);
        graphics.fill(centerX - inner, centerY - inner + 1, centerX + inner + 1, centerY + inner, color);
    }

    private static void drawPixelLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int stepX = x0 < x1 ? 1 : -1;
        int stepY = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        while (true) {
            graphics.fill(x0, y0, x0 + 2, y0 + 2, color);
            if (x0 == x1 && y0 == y1) break;
            int twice = 2 * error;
            if (twice >= dy) { error += dy; x0 += stepX; }
            if (twice <= dx) { error += dx; y0 += stepY; }
        }
    }

    private int panelWidth() { return Math.max(560, Math.min(PANEL_MAX_WIDTH, width - 24)); }
    private int panelHeight() { return Math.max(360, Math.min(PANEL_MAX_HEIGHT, height - 30)); }
    private int panelLeft() { return (width - panelWidth()) / 2; }
    private int panelTop() { return (height - panelHeight()) / 2; }

    private Rect canvasRect() {
        int x = panelLeft() + 8;
        int y = panelTop() + HEADER_HEIGHT;
        return new Rect(x, y, panelWidth() - PALETTE_WIDTH - 24, panelHeight() - HEADER_HEIGHT - FOOTER_HEIGHT);
    }

    private Rect paletteRect() {
        Rect canvas = canvasRect();
        return new Rect(canvas.right() + 8, canvas.y, PALETTE_WIDTH, canvas.height);
    }

    private int scaledNodeWidth() { return Math.max(1, (int) Math.round(NODE_WIDTH * zoom)); }
    private int scaledNodeHeight() { return Math.max(1, (int) Math.round(NODE_HEIGHT * zoom)); }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static int floorMod(int value, int modulus) {
        return (value % modulus + modulus) % modulus;
    }

    @Override
    public void onClose() {
        if (parent instanceof SpellLibraryScreen library) library.requestRefresh();
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private enum ContextAction { EDIT_CORE_VSCODE, CONFIGURE_CORE, DUPLICATE, STOW, SEARCH }
    private enum CategoryTab {
        ALL("全部", null), TRIGGER("発動", Category.TRIGGER), ELEMENT("属性", Category.ELEMENT),
        SHAPE("形状", Category.SHAPE), TARGET("対象", Category.TARGET), MODIFIER("補助", Category.MODIFIER),
        LOGIC("論理", Category.LOGIC);

        final String label;
        final Category category;
        CategoryTab(String label, Category category) { this.label = label; this.category = category; }
    }
    private record PaletteKey(String fragmentId, int level) { }
    private record PaletteGroup(PaletteKey key, List<OwnedFragment> fragments) {
        OwnedFragment representative() { return fragments.getFirst(); }
        int placedCount(Set<UUID> used) {
            return (int) fragments.stream().filter(fragment -> used.contains(fragment.instanceId())).count();
        }
    }
    private record Rect(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX <= right() && mouseY >= y && mouseY <= bottom();
        }
    }
    private record NodeBox(int x, int y, int width, int height) {
        int right() { return x + width; }
        int bottom() { return y + height; }
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX <= right() && mouseY >= y && mouseY <= bottom();
        }
    }
    private record PortHit(UUID nodeId, PortDefinition port, int x, int y) {
        boolean contains(int mouseX, int mouseY) { return Math.abs(mouseX - x) <= 7 && Math.abs(mouseY - y) <= 7; }
    }
    private record PortDrag(UUID nodeId, PortDefinition port, int startX, int startY) { }
    private record ContextMenu(int x, int y, UUID nodeId) { }
    private record WorldPoint(double x, double y) { }
}
