package net.monacraft.spellforge.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SpellModel {
    private SpellModel() {}

    public enum Category { TRIGGER, ELEMENT, SHAPE, TARGET, MODIFIER, LOGIC }
    public enum PortType { EXEC, ENTITY, LOCATION, NUMBER, BOOLEAN, ELEMENT, PROJECTILE }
    public enum Direction { INPUT, OUTPUT }
    public enum Multiplicity { ZERO, ONE, MANY }
    public enum Severity { ERROR, WARNING }

    public record PortDefinition(String id, PortType type, Direction direction, Multiplicity multiplicity) {
        public PortDefinition {
            Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(direction); Objects.requireNonNull(multiplicity);
        }
    }

    public record LevelParameters(double power, double range, double duration, double manaCost, int runtimeCost) {
        public LevelParameters {
            if (power < 0 || range < 0 || duration < 0 || manaCost < 0 || runtimeCost < 0) {
                throw new IllegalArgumentException("Level parameters must be non-negative");
            }
        }
    }

    public record FragmentDefinition(
            String id,
            Category category,
            int maxLevel,
            List<PortDefinition> ports,
            Map<Integer, LevelParameters> levels,
            String rarity,
            String displayItem,
            String permission
    ) {
        public FragmentDefinition {
            id = normalizeId(id);
            Objects.requireNonNull(category); Objects.requireNonNull(ports); Objects.requireNonNull(levels);
            rarity = Objects.requireNonNullElse(rarity, "COMMON");
            displayItem = Objects.requireNonNullElse(displayItem, "AMETHYST_SHARD");
            permission = Objects.requireNonNullElse(permission, "");
            ports = List.copyOf(ports); levels = Map.copyOf(levels);
            if (maxLevel < 1 || maxLevel > 5 || levels.size() != maxLevel) throw new IllegalArgumentException("Invalid maxLevel for " + id);
            for (int level = 1; level <= maxLevel; level++) if (!levels.containsKey(level)) throw new IllegalArgumentException("Missing level " + level + " for " + id);
        }

        public PortDefinition port(String portId) {
            return ports.stream().filter(port -> port.id().equals(portId)).findFirst().orElse(null);
        }
    }

    public record FragmentInstance(UUID instanceId, String fragmentId, int level, UUID ownerId, String issuer, Instant issuedAt) {
        public FragmentInstance {
            Objects.requireNonNull(instanceId); fragmentId = normalizeId(fragmentId); Objects.requireNonNull(ownerId);
            issuer = Objects.requireNonNullElse(issuer, "unknown"); Objects.requireNonNull(issuedAt);
        }
    }

    /** Minimal, non-sensitive fragment data sent to the client editor palette. */
    public record OwnedFragment(UUID instanceId, String fragmentId, int level) {
        public OwnedFragment {
            Objects.requireNonNull(instanceId);
            fragmentId = normalizeId(fragmentId);
            if (level < 1 || level > 5) throw new IllegalArgumentException("Invalid fragment level");
        }
    }

    public record EditorPalette(List<OwnedFragment> fragments) {
        public EditorPalette {
            fragments = fragments == null ? List.of() : List.copyOf(fragments);
        }
    }

    /** Compact metadata used by the spell library. Circuit contents are sent only after selection. */
    public record CircuitSummary(UUID circuitId, String name, long revision, int nodeCount, int edgeCount,
                                 boolean active, Instant updatedAt) {
        public CircuitSummary {
            Objects.requireNonNull(circuitId);
            name = Objects.requireNonNullElse(name, "名称未設定の魔術");
            updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        }
    }

    public record CircuitLibrary(List<CircuitSummary> circuits, String message) {
        public CircuitLibrary {
            circuits = circuits == null ? List.of() : List.copyOf(circuits);
            message = Objects.requireNonNullElse(message, "");
        }
    }

    public record CircuitNode(UUID nodeId, int slotIndex, UUID fragmentInstanceId, String fragmentId, int level,
                              Map<String, String> parameters, double uiX, double uiY) {
        public CircuitNode {
            Objects.requireNonNull(nodeId); Objects.requireNonNull(fragmentInstanceId); fragmentId = normalizeId(fragmentId);
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    public record CircuitEdge(UUID edgeId, UUID sourceNodeId, String sourcePortId, UUID targetNodeId, String targetPortId) {
        public CircuitEdge {
            Objects.requireNonNull(edgeId); Objects.requireNonNull(sourceNodeId); Objects.requireNonNull(sourcePortId);
            Objects.requireNonNull(targetNodeId); Objects.requireNonNull(targetPortId);
        }
    }

    public record Circuit(UUID circuitId, UUID ownerId, String name, long revision, int schemaVersion,
                          List<CircuitNode> nodes, List<CircuitEdge> edges, Instant createdAt, Instant updatedAt) {
        public Circuit {
            Objects.requireNonNull(circuitId); Objects.requireNonNull(ownerId);
            name = Objects.requireNonNullElse(name, "名称未設定の魔術");
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            edges = edges == null ? List.of() : List.copyOf(edges);
            createdAt = createdAt == null ? Instant.now() : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }

        public static Circuit empty(UUID ownerId) {
            Instant now = Instant.now();
            return new Circuit(UUID.randomUUID(), ownerId, "新しい魔術", 0, 1, List.of(), List.of(), now, now);
        }

        public Circuit withContent(List<CircuitNode> newNodes, List<CircuitEdge> newEdges) {
            return new Circuit(circuitId, ownerId, name, revision, schemaVersion, newNodes, newEdges, createdAt, Instant.now());
        }
    }

    public record ValidationIssue(Severity severity, String code, String nodeOrEdgeId, String message, String suggestion) {}

    public record CompiledSpell(UUID circuitId, long revision, List<CircuitNode> executionOrder,
                                Map<UUID, FragmentDefinition> resolvedDefinitions, double manaCost, int runtimeCost) {
        public CompiledSpell {
            executionOrder = List.copyOf(executionOrder); resolvedDefinitions = Map.copyOf(resolvedDefinitions);
        }
    }

    public record CompilationResult(CompiledSpell spell, List<ValidationIssue> issues) {
        public CompilationResult { issues = List.copyOf(issues); }
        public boolean valid() { return spell != null && issues.stream().noneMatch(i -> i.severity() == Severity.ERROR); }
    }

    public static String normalizeId(String id) {
        Objects.requireNonNull(id, "id");
        String normalized = id.trim().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,64}")) throw new IllegalArgumentException("Invalid id: " + id);
        return normalized;
    }
}
