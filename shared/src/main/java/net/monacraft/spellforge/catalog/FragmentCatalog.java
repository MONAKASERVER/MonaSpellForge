package net.monacraft.spellforge.catalog;

import net.monacraft.spellforge.model.SpellModel;
import net.monacraft.spellforge.model.SpellModel.*;

import java.util.*;

public final class FragmentCatalog {
    public static final List<String> TRIGGERS = List.of("RIGHT_CLICK", "SNEAK", "DAMAGE_TAKEN", "LAND", "PROJECTILE_HIT", "KILL_CONFIRM");
    public static final List<String> ELEMENTS = List.of("FIRE", "ICE", "WIND", "EARTH", "LIGHTNING", "WATER", "LIGHT", "DARK", "POISON", "GRAVITY", "HEAL", "ARCANE");
    public static final List<String> SHAPES = List.of("PROJECTILE", "BEAM", "WAVE", "BURST", "BARRIER", "RING", "LINE", "SIGIL");
    public static final List<String> TARGETS = List.of("LOOK_TARGET", "NEAREST_ENEMY", "AREA_TARGETS", "SELF", "ALLIES");
    public static final List<String> MODIFIERS = List.of("POWER", "VELOCITY", "RANGE", "DURATION", "PIERCE", "HOMING", "SPLIT", "AREA_SCALE", "EFFICIENCY",
            "AMPLIFY_X2", "AMPLIFY_X3", "AMPLIFY_X4");
    public static final List<String> LOGIC = List.of("FORK", "MERGE_ANY", "MERGE_ALL", "IF", "AND", "OR", "NOT", "DELAY", "REPEAT", "COMPARE",
            "MOB_SENSOR", "AUTOMATION", "CHAIN", "CALL", "SPELL_VARIABLE", "COUNTER", "MAGIC_GATE", "SEQUENCE", "CHAOS", "MEMORY_CORE",
            "RETURN_CIRCUIT", "PARALLEL", "MULTI_BRANCH", "ENVIRONMENT_OBSERVER", "TIMER", "PERIODIC_SIGNAL");

    private final Map<String, FragmentDefinition> definitions;
    private final long version;

    public FragmentCatalog(Collection<FragmentDefinition> definitions, long version) {
        LinkedHashMap<String, FragmentDefinition> map = new LinkedHashMap<>();
        for (FragmentDefinition definition : definitions) {
            if (map.putIfAbsent(definition.id(), definition) != null) throw new IllegalArgumentException("Duplicate fragment: " + definition.id());
        }
        if (map.size() != 69) throw new IllegalArgumentException("Catalog must contain exactly 69 fragments, got " + map.size());
        this.definitions = Map.copyOf(map);
        this.version = version;
    }

    public FragmentDefinition get(String id) { return definitions.get(SpellModel.normalizeId(id)); }
    public Collection<FragmentDefinition> all() { return definitions.values(); }
    public Set<String> ids() { return definitions.keySet(); }
    public long version() { return version; }

    public static FragmentCatalog defaults() {
        List<FragmentDefinition> all = new ArrayList<>(69);
        TRIGGERS.forEach(id -> all.add(definition(id, Category.TRIGGER, 3)));
        ELEMENTS.forEach(id -> all.add(definition(id, Category.ELEMENT, switch (id) { case "GRAVITY", "HEAL", "ARCANE" -> 5; default -> 4; })));
        SHAPES.forEach(id -> all.add(definition(id, Category.SHAPE, switch (id) { case "PROJECTILE", "BURST" -> 5; default -> 4; })));
        TARGETS.forEach(id -> all.add(definition(id, Category.TARGET, 3)));
        MODIFIERS.forEach(id -> all.add(definition(id, Category.MODIFIER, 5)));
        LOGIC.forEach(id -> all.add(definition(id, Category.LOGIC, switch (id) {
            case "REPEAT", "DELAY", "FORK", "TIMER", "PERIODIC_SIGNAL", "RETURN_CIRCUIT" -> 5;
            default -> 3;
        })));
        return new FragmentCatalog(all, 2);
    }

    private static FragmentDefinition definition(String id, Category category, int maxLevel) {
        List<PortDefinition> ports = portsFor(id, category);
        Map<Integer, LevelParameters> levels = new LinkedHashMap<>();
        double basePower = switch (category) { case ELEMENT -> 8; case SHAPE -> 2; case MODIFIER -> 1.15; default -> 1; };
        double baseMana = switch (category) { case TRIGGER -> 2; case ELEMENT -> 12; case SHAPE -> 8; case TARGET -> 5; case MODIFIER -> 7; case LOGIC -> 9; };
        int baseRuntime = switch (category) { case TRIGGER, TARGET -> 1; case ELEMENT, SHAPE, MODIFIER -> 3; case LOGIC -> 4; };
        for (int level = 1; level <= maxLevel; level++) {
            levels.put(level, new LevelParameters(basePower * (1 + (level - 1) * 0.28), 12 + level * 6,
                    2 + level * 1.5, baseMana * (1 + (level - 1) * 0.35), baseRuntime + level - 1));
        }
        String rarity = switch (maxLevel) { case 5 -> "EPIC"; case 4 -> "RARE"; default -> "UNCOMMON"; };
        return new FragmentDefinition(id, category, maxLevel, ports, levels, rarity, materialFor(category), "");
    }

    private static String materialFor(Category category) {
        return switch (category) {
            case TRIGGER -> "REDSTONE_TORCH"; case ELEMENT -> "AMETHYST_SHARD"; case SHAPE -> "ECHO_SHARD";
            case TARGET -> "ENDER_EYE"; case MODIFIER -> "PRISMARINE_CRYSTALS"; case LOGIC -> "COMPARATOR";
        };
    }

    private static List<PortDefinition> portsFor(String id, Category category) {
        PortDefinition execIn = new PortDefinition("exec_in", PortType.EXEC, Direction.INPUT, Multiplicity.ONE);
        PortDefinition execOut = new PortDefinition("exec_out", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY);
        return switch (category) {
            case TRIGGER -> List.of(execOut);
            case ELEMENT -> List.of(execIn, execOut, new PortDefinition("element", PortType.ELEMENT, Direction.OUTPUT, Multiplicity.MANY));
            case SHAPE -> List.of(execIn, execOut, new PortDefinition("element", PortType.ELEMENT, Direction.INPUT, Multiplicity.ONE),
                    new PortDefinition("entity", PortType.ENTITY, Direction.INPUT, Multiplicity.ZERO),
                    new PortDefinition("location", PortType.LOCATION, Direction.INPUT, Multiplicity.ZERO),
                    new PortDefinition("projectile", PortType.PROJECTILE, Direction.OUTPUT, Multiplicity.MANY));
            case TARGET -> List.of(new PortDefinition("entity", PortType.ENTITY, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("location", PortType.LOCATION, Direction.OUTPUT, Multiplicity.MANY));
            case MODIFIER -> List.of(execIn, execOut, new PortDefinition("number_in", PortType.NUMBER, Direction.INPUT, Multiplicity.ZERO),
                    new PortDefinition("number_out", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY));
            case LOGIC -> logicPorts(id, execIn, execOut);
        };
    }

    private static List<PortDefinition> logicPorts(String id, PortDefinition execIn, PortDefinition execOut) {
        return switch (id) {
            case "MOB_SENSOR" -> List.of(execIn, execOut,
                    new PortDefinition("detected", PortType.BOOLEAN, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("count", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("entity", PortType.ENTITY, Direction.OUTPUT, Multiplicity.MANY));
            case "ENVIRONMENT_OBSERVER" -> List.of(execIn, execOut,
                    new PortDefinition("enemy_hp", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("distance", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("enemy_count", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY));
            case "SPELL_VARIABLE" -> List.of(execIn, execOut,
                    new PortDefinition("value_in", PortType.NUMBER, Direction.INPUT, Multiplicity.ZERO),
                    new PortDefinition("value_out", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY));
            case "COUNTER", "MEMORY_CORE", "RETURN_CIRCUIT" -> List.of(execIn, execOut,
                    new PortDefinition("value", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY));
            case "MAGIC_GATE" -> List.of(execIn,
                    new PortDefinition("condition", PortType.BOOLEAN, Direction.INPUT, Multiplicity.ZERO), execOut);
            case "CHAOS" -> List.of(execIn,
                    new PortDefinition("path_a", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_b", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_c", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY));
            case "PARALLEL" -> List.of(execIn,
                    new PortDefinition("branch_a", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("branch_b", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY), execOut);
            case "MULTI_BRANCH" -> List.of(execIn,
                    new PortDefinition("path_1", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_2", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_3", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_4", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_5", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_6", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_7", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("path_8", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY));
            case "AND", "OR", "COMPARE" -> List.of(
                    new PortDefinition("a", id.equals("COMPARE") ? PortType.NUMBER : PortType.BOOLEAN, Direction.INPUT, Multiplicity.ONE),
                    new PortDefinition("b", id.equals("COMPARE") ? PortType.NUMBER : PortType.BOOLEAN, Direction.INPUT, Multiplicity.ONE),
                    new PortDefinition("result", PortType.BOOLEAN, Direction.OUTPUT, Multiplicity.MANY));
            case "NOT" -> List.of(new PortDefinition("value", PortType.BOOLEAN, Direction.INPUT, Multiplicity.ONE),
                    new PortDefinition("result", PortType.BOOLEAN, Direction.OUTPUT, Multiplicity.MANY));
            case "IF" -> List.of(execIn, new PortDefinition("condition", PortType.BOOLEAN, Direction.INPUT, Multiplicity.ONE),
                    new PortDefinition("true", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                    new PortDefinition("false", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY));
            case "MERGE_ANY", "MERGE_ALL" -> List.of(new PortDefinition("exec_in", PortType.EXEC, Direction.INPUT, Multiplicity.MANY), execOut);
            default -> List.of(execIn, execOut);
        };
    }
}
