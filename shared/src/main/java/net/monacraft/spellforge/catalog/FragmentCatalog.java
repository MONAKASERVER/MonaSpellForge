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
            "AMPLIFY_X2", "AMPLIFY_X3", "AMPLIFY_X4", "CIRCUIT_SPEED", "ARMOR_PENETRATION", "TRUE_DAMAGE");
    public static final List<String> LOGIC = List.of("FORK", "MERGE_ANY", "MERGE_ALL", "IF", "AND", "OR", "NOT", "DELAY", "REPEAT", "COMPARE",
            "MOB_SENSOR", "AUTOMATION", "CHAIN", "CALL", "SPELL_VARIABLE", "COUNTER", "MAGIC_GATE", "SEQUENCE", "CHAOS", "MEMORY_CORE",
            "RETURN_CIRCUIT", "PARALLEL", "MULTI_BRANCH", "ENVIRONMENT_OBSERVER", "TIMER", "PERIODIC_SIGNAL",
            "ARCSYS_CORE");

    private final Map<String, FragmentDefinition> definitions;
    private final long version;

    public FragmentCatalog(Collection<FragmentDefinition> definitions, long version) {
        LinkedHashMap<String, FragmentDefinition> map = new LinkedHashMap<>();
        for (FragmentDefinition definition : definitions) {
            if (map.putIfAbsent(definition.id(), definition) != null) throw new IllegalArgumentException("Duplicate fragment: " + definition.id());
        }
        if (map.size() != 73) throw new IllegalArgumentException("Catalog must contain exactly 73 fragments, got " + map.size());
        this.definitions = Map.copyOf(map);
        this.version = version;
    }

    public FragmentDefinition get(String id) { return definitions.get(SpellModel.normalizeId(id)); }
    public Collection<FragmentDefinition> all() { return definitions.values(); }
    public Set<String> ids() { return definitions.keySet(); }
    public long version() { return version; }

    public FragmentCatalog withArcSysCoreLevels(Map<Integer, LevelParameters> levels) {
        if (levels == null || levels.size() != 5 || !levels.keySet().containsAll(Set.of(1, 2, 3, 4, 5))) {
            throw new IllegalArgumentException("ARCSYS_CORE requires levels 1..5");
        }
        FragmentDefinition current = get("ARCSYS_CORE");
        FragmentDefinition configured = new FragmentDefinition(current.id(), current.category(), current.maxLevel(),
                current.ports(), levels, current.rarity(), current.displayItem(), current.permission());
        List<FragmentDefinition> updated = new ArrayList<>(definitions.size());
        for (FragmentDefinition definition : definitions.values())
            updated.add(definition.id().equals("ARCSYS_CORE") ? configured : definition);
        return new FragmentCatalog(updated, version);
    }

    public static FragmentCatalog defaults() {
        List<FragmentDefinition> all = new ArrayList<>(73);
        TRIGGERS.forEach(id -> all.add(definition(id, Category.TRIGGER, 3)));
        ELEMENTS.forEach(id -> all.add(definition(id, Category.ELEMENT, switch (id) { case "GRAVITY", "HEAL", "ARCANE" -> 5; default -> 4; })));
        SHAPES.forEach(id -> all.add(definition(id, Category.SHAPE, switch (id) { case "PROJECTILE", "BURST" -> 5; default -> 4; })));
        TARGETS.forEach(id -> all.add(definition(id, Category.TARGET, 3)));
        MODIFIERS.forEach(id -> all.add(definition(id, Category.MODIFIER, id.equals("CIRCUIT_SPEED") ? 4 : 5)));
        LOGIC.forEach(id -> all.add(definition(id, Category.LOGIC, switch (id) {
            case "ARCSYS_CORE" -> 5;
            case "REPEAT", "DELAY", "FORK", "TIMER", "PERIODIC_SIGNAL", "RETURN_CIRCUIT" -> 5;
            default -> 3;
        })));
        return new FragmentCatalog(all, 5);
    }

    private static FragmentDefinition definition(String id, Category category, int maxLevel) {
        if (id.equals("ARCSYS_CORE")) return arcSysCoreDefinition();
        List<PortDefinition> ports = portsFor(id, category);
        Map<Integer, LevelParameters> levels = new LinkedHashMap<>();
        double basePower = switch (category) { case ELEMENT -> 8; case SHAPE -> 2; case MODIFIER -> 1.15; default -> 1; };
        double baseMana = switch (category) { case TRIGGER -> 2; case ELEMENT -> 12; case SHAPE -> 8; case TARGET -> 5; case MODIFIER -> 7; case LOGIC -> 9; };
        int baseRuntime = switch (category) { case TRIGGER, TARGET -> 1; case ELEMENT, SHAPE, MODIFIER -> 3; case LOGIC -> 4; };
        for (int level = 1; level <= maxLevel; level++) {
            if (id.equals("CIRCUIT_SPEED")) {
                double speedMultiplier = level * 2.0;
                levels.put(level, new LevelParameters(speedMultiplier, 0, 0,
                        18.0 * level, 12 * level));
            } else if (id.equals("ARMOR_PENETRATION")) {
                levels.put(level, new LevelParameters(0.15 + (level - 1) * 0.10, 0, 0,
                        10.0 + level * 6.0, 4 + level));
            } else if (id.equals("TRUE_DAMAGE")) {
                levels.put(level, new LevelParameters(50 + level * level * 10.0, 0, 0,
                        80.0 + level * 55.0, 72 + level * 30));
            } else if (id.equals("HEAL")) {
                // Balance invariant: every 5 HP of base healing costs at least 200 MP.
                levels.put(level, new LevelParameters(5.0 * level, 12 + level * 6,
                        2 + level * 1.5, 200.0 * level, baseRuntime + level - 1));
            } else levels.put(level, new LevelParameters(basePower * (1 + (level - 1) * 0.28), 12 + level * 6,
                    2 + level * 1.5, baseMana * (1 + (level - 1) * 0.35), baseRuntime + level - 1));
        }
        String rarity = switch (maxLevel) { case 5 -> "EPIC"; case 4 -> "RARE"; default -> "UNCOMMON"; };
        return new FragmentDefinition(id, category, maxLevel, ports, levels, rarity, materialFor(category), "");
    }

    private static FragmentDefinition arcSysCoreDefinition() {
        List<PortDefinition> ports = List.of(
                new PortDefinition("exec_in", PortType.EXEC, Direction.INPUT, Multiplicity.ONE),
                new PortDefinition("exec_out", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                new PortDefinition("exec_fail", PortType.EXEC, Direction.OUTPUT, Multiplicity.MANY),
                new PortDefinition("entity_in", PortType.ENTITY, Direction.INPUT, Multiplicity.ZERO),
                new PortDefinition("location_in", PortType.LOCATION, Direction.INPUT, Multiplicity.ZERO),
                new PortDefinition("number_in", PortType.NUMBER, Direction.INPUT, Multiplicity.ZERO),
                new PortDefinition("boolean_in", PortType.BOOLEAN, Direction.INPUT, Multiplicity.ZERO),
                new PortDefinition("entity_out", PortType.ENTITY, Direction.OUTPUT, Multiplicity.MANY),
                new PortDefinition("number_out", PortType.NUMBER, Direction.OUTPUT, Multiplicity.MANY),
                new PortDefinition("boolean_out", PortType.BOOLEAN, Direction.OUTPUT, Multiplicity.MANY));
        int[] fuel = {64, 96, 128, 192, 256};
        int[] effects = {2, 3, 4, 6, 8};
        double[] mana = {40, 55, 75, 100, 130};
        int[] load = {12, 16, 24, 32, 40};
        Map<Integer, LevelParameters> levels = new LinkedHashMap<>();
        for (int level = 1; level <= 5; level++) {
            // power=fuel, range=max effects, duration=cooldown ticks, mana/runtime keep their normal meaning.
            levels.put(level, new LevelParameters(fuel[level - 1], effects[level - 1],
                    90 - level * 10, mana[level - 1], load[level - 1]));
        }
        return new FragmentDefinition("ARCSYS_CORE", Category.LOGIC, 5, ports, levels,
                "LEGENDARY", "RESPAWN_ANCHOR", "monaspellforge.arcsys.core.use");
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
