package net.monacraft.spellforge.compiler;

import net.monacraft.spellforge.catalog.FragmentCatalog;
import net.monacraft.spellforge.model.SpellModel.*;

import java.util.*;

public final class CircuitCompiler {
    public static final int MAX_NODES = 25;
    public static final int MAX_EDGES = 64;
    public static final int MAX_RUNTIME_COST = 512;

    private final FragmentCatalog catalog;

    public CircuitCompiler(FragmentCatalog catalog) { this.catalog = Objects.requireNonNull(catalog); }

    public CompilationResult compile(Circuit circuit) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (circuit.nodes().size() > MAX_NODES) error(issues, "NODE_LIMIT", null, "ノード数が上限25を超えています", "不要なノードを削除してください");
        if (circuit.edges().size() > MAX_EDGES) error(issues, "EDGE_LIMIT", null, "配線数が上限64を超えています", "不要な配線を削除してください");

        Map<UUID, CircuitNode> nodes = new LinkedHashMap<>();
        Set<Integer> slots = new HashSet<>();
        Map<UUID, FragmentDefinition> resolved = new LinkedHashMap<>();
        for (CircuitNode node : circuit.nodes()) {
            if (nodes.putIfAbsent(node.nodeId(), node) != null) error(issues, "DUPLICATE_NODE", node.nodeId().toString(), "ノードIDが重複しています", "ノードを配置し直してください");
            if (node.slotIndex() < 0 || node.slotIndex() >= MAX_NODES) error(issues, "INVALID_SLOT", node.nodeId().toString(), "スロットは0～24である必要があります", "有効なスロットへ移動してください");
            if (!slots.add(node.slotIndex())) error(issues, "OCCUPIED_SLOT", node.nodeId().toString(), "同じスロットに複数ノードがあります", "空きスロットへ移動してください");
            FragmentDefinition definition = catalog.get(node.fragmentId());
            if (definition == null) error(issues, "UNKNOWN_FRAGMENT", node.nodeId().toString(), "欠片IDが存在しません: " + node.fragmentId(), "現在の欠片一覧から選び直してください");
            else {
                resolved.put(node.nodeId(), definition);
                if (node.level() < 1 || node.level() > definition.maxLevel()) error(issues, "LEVEL_RANGE", node.nodeId().toString(), "欠片レベルが範囲外です", "Lv1～" + definition.maxLevel() + "を指定してください");
            }
        }

        Map<UUID, Integer> indegree = new HashMap<>();
        Map<UUID, List<UUID>> graph = new HashMap<>();
        Map<String, Integer> inputUse = new HashMap<>();
        nodes.keySet().forEach(id -> { indegree.put(id, 0); graph.put(id, new ArrayList<>()); });
        for (CircuitEdge edge : circuit.edges()) {
            CircuitNode source = nodes.get(edge.sourceNodeId());
            CircuitNode target = nodes.get(edge.targetNodeId());
            if (source == null || target == null) { error(issues, "MISSING_ENDPOINT", edge.edgeId().toString(), "配線先ノードが存在しません", "壊れた配線を削除してください"); continue; }
            FragmentDefinition sourceDefinition = resolved.get(source.nodeId());
            FragmentDefinition targetDefinition = resolved.get(target.nodeId());
            if (sourceDefinition == null || targetDefinition == null) continue;
            PortDefinition sourcePort = sourceDefinition.port(edge.sourcePortId());
            PortDefinition targetPort = targetDefinition.port(edge.targetPortId());
            if (sourcePort == null || targetPort == null) { error(issues, "UNKNOWN_PORT", edge.edgeId().toString(), "端子が存在しません", "端子を選び直してください"); continue; }
            if (sourcePort.direction() != Direction.OUTPUT || targetPort.direction() != Direction.INPUT) error(issues, "PORT_DIRECTION", edge.edgeId().toString(), "出力端子から入力端子へ接続してください", "配線方向を反転してください");
            if (sourcePort.type() != targetPort.type()) error(issues, "PORT_TYPE", edge.edgeId().toString(), sourcePort.type() + "と" + targetPort.type() + "は接続できません", "同じ型の端子へ接続してください");
            String inputKey = target.nodeId() + ":" + targetPort.id();
            int count = inputUse.merge(inputKey, 1, Integer::sum);
            if (targetPort.multiplicity() != Multiplicity.MANY && count > 1) error(issues, "INPUT_MULTIPLICITY", edge.edgeId().toString(), "入力端子へ複数の配線があります", "余分な配線を削除してください");
            graph.get(source.nodeId()).add(target.nodeId());
            indegree.merge(target.nodeId(), 1, Integer::sum);
        }

        long triggers = resolved.values().stream().filter(d -> d.category() == Category.TRIGGER).count();
        long shapes = resolved.values().stream().filter(d -> d.category() == Category.SHAPE).count();
        if (triggers == 0) error(issues, "NO_TRIGGER", null, "発動条件がありません", "TRIGGER欠片を配置してください");
        if (triggers > 1) error(issues, "MULTIPLE_TRIGGERS", null, "1回路に配置できる発動条件は1つです", "TRIGGER欠片を1つだけ残してください");
        if (shapes == 0) error(issues, "NO_SHAPE", null, "魔術形状がありません", "SHAPE欠片を配置してください");

        ArrayDeque<UUID> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> { if (degree == 0) queue.add(id); });
        List<UUID> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            UUID id = queue.removeFirst(); order.add(id);
            for (UUID next : graph.getOrDefault(id, List.of())) if (indegree.merge(next, -1, Integer::sum) == 0) queue.addLast(next);
        }
        if (order.size() != nodes.size()) error(issues, "CYCLE", null, "REPEAT以外の循環配線は禁止されています", "循環する配線を削除し、反復にはREPEATを使用してください");

        for (CircuitNode node : circuit.nodes()) {
            FragmentDefinition definition = resolved.get(node.nodeId());
            if (definition != null) for (PortDefinition port : definition.ports()) {
                if (port.direction() == Direction.INPUT && port.multiplicity() == Multiplicity.ONE
                        && inputUse.getOrDefault(node.nodeId() + ":" + port.id(), 0) == 0) {
                    error(issues, "REQUIRED_INPUT", node.nodeId().toString(), definition.id() + " の必須端子 " + port.id() + " が未接続です", "対応する出力端子を接続してください");
                }
            }
            if (definition != null && definition.id().equals("MERGE_ALL")) {
                int incoming = inputUse.getOrDefault(node.nodeId() + ":exec_in", 0);
                if (incoming < 2) error(issues, "MERGE_ALL_BRANCHES", node.nodeId().toString(), "MERGE_ALLには2本以上の入力が必要です", "合流させる分岐を接続してください");
            }
        }

        double mana = 0;
        int runtime = 0;
        for (CircuitNode node : circuit.nodes()) {
            FragmentDefinition definition = resolved.get(node.nodeId());
            if (definition == null || node.level() < 1 || node.level() > definition.maxLevel()) continue;
            LevelParameters level = definition.levels().get(node.level());
            mana += level.manaCost(); runtime += level.runtimeCost();
            if (definition.id().equals("REPEAT")) runtime *= Math.min(8, Math.max(1, parseInt(node.parameters().get("count"), node.level() + 1)));
        }
        CircuitNode heal = circuit.nodes().stream().filter(node -> node.fragmentId().equals("HEAL")).findFirst().orElse(null);
        if (heal != null && resolved.containsKey(heal.nodeId()) && resolved.get(heal.nodeId()).levels().containsKey(heal.level())) {
            double healing = resolved.get(heal.nodeId()).levels().get(heal.level()).power();
            CircuitNode power = circuit.nodes().stream().filter(node -> node.fragmentId().equals("POWER")).findFirst().orElse(null);
            if (power != null) healing *= 1 + power.level() * 0.22;
            healing *= circuit.nodes().stream().anyMatch(node -> node.fragmentId().equals("AMPLIFY_X4")) ? 4
                    : circuit.nodes().stream().anyMatch(node -> node.fragmentId().equals("AMPLIFY_X3")) ? 3
                    : circuit.nodes().stream().anyMatch(node -> node.fragmentId().equals("AMPLIFY_X2")) ? 2 : 1;
            mana = Math.max(mana, Math.ceil(healing / 5.0) * 200.0);
        }
        if (runtime > MAX_RUNTIME_COST) error(issues, "RUNTIME_BUDGET", null, "実行負荷 " + runtime + " が上限 " + MAX_RUNTIME_COST + " を超えています", "反復・分裂・高負荷ノードを減らしてください");
        if (issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR)) return new CompilationResult(null, issues);
        List<CircuitNode> execution = order.stream().map(nodes::get).toList();
        return new CompilationResult(new CompiledSpell(circuit.circuitId(), circuit.revision(), execution, resolved, Math.ceil(mana), runtime), issues);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static void error(List<ValidationIssue> issues, String code, String id, String message, String suggestion) {
        issues.add(new ValidationIssue(Severity.ERROR, code, id, message, suggestion));
    }
}
