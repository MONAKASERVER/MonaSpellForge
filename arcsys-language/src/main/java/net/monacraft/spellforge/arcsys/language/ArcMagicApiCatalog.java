package net.monacraft.spellforge.arcsys.language;

import java.util.*;

/**
 * The single source of truth for the ArcSys magic standard library.
 *
 * <p>The compiler, static checker and developer tooling must consume this
 * catalog instead of maintaining separate lists. Entries describe only host
 * operations that Paper can validate and execute; they are not Java hooks.</p>
 */
public final class ArcMagicApiCatalog {
    private ArcMagicApiCatalog() { }

    public enum Kind {
        ANY, NONE, BOOL, INT, FLOAT, NUMBER, STRING,
        DURATION, DISTANCE, MANA, PERCENT,
        ENTITY, LOCATION, WORLD, VECTOR, BLOCK, ITEM,
        LIST, OPTION, RESULT
    }

    public record ValueType(Kind kind, List<ValueType> arguments) {
        public ValueType {
            Objects.requireNonNull(kind, "kind");
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
        }
        public static ValueType of(Kind kind) { return new ValueType(kind, List.of()); }
        public static ValueType list(ValueType element) { return new ValueType(Kind.LIST, List.of(element)); }
        public static ValueType option(ValueType element) { return new ValueType(Kind.OPTION, List.of(element)); }
        public static ValueType result(ValueType ok, ValueType error) { return new ValueType(Kind.RESULT, List.of(ok, error)); }
        public ValueType argument(int index) { return index < arguments.size() ? arguments.get(index) : of(Kind.ANY); }
        public String displayName() {
            if (arguments.isEmpty()) return title(kind);
            return title(kind) + "<" + String.join(", ", arguments.stream().map(ValueType::displayName).toList()) + ">";
        }
        private static String title(Kind kind) {
            String lower = kind.name().toLowerCase(Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    public record Parameter(String name, ValueType type, boolean optional, String description) {
        public Parameter {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(type, "type");
            description = description == null ? "" : description;
        }
    }

    public record Api(String name, String capability, List<Parameter> parameters, ValueType returns,
                      int fuel, int baseMana, boolean effectful, String description) {
        public Api {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(capability, "capability");
            parameters = List.copyOf(parameters); Objects.requireNonNull(returns, "returns");
            description = description == null ? "" : description;
        }
        public int minArgs() { return (int) parameters.stream().filter(parameter -> !parameter.optional()).count(); }
        public int maxArgs() { return parameters.size(); }
        public Optional<Parameter> parameter(String name) {
            return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst();
        }
        public String signature() {
            return name + "(" + String.join(", ", parameters.stream()
                    .map(p -> p.name() + (p.optional() ? "?" : "") + ": " + p.type().displayName()).toList())
                    + ") -> " + returns.displayName();
        }
    }

    private static final ValueType ANY = ValueType.of(Kind.ANY), NONE = ValueType.of(Kind.NONE);
    private static final ValueType BOOL = ValueType.of(Kind.BOOL), INT = ValueType.of(Kind.INT);
    private static final ValueType FLOAT = ValueType.of(Kind.FLOAT), NUMBER = ValueType.of(Kind.NUMBER);
    private static final ValueType STRING = ValueType.of(Kind.STRING), DURATION = ValueType.of(Kind.DURATION);
    private static final ValueType DISTANCE = ValueType.of(Kind.DISTANCE), MANA = ValueType.of(Kind.MANA);
    private static final ValueType PERCENT = ValueType.of(Kind.PERCENT), ENTITY = ValueType.of(Kind.ENTITY);
    private static final ValueType LOCATION = ValueType.of(Kind.LOCATION), BLOCK = ValueType.of(Kind.BLOCK);
    private static final ValueType ITEM = ValueType.of(Kind.ITEM);
    private static final ValueType RESULT = ValueType.result(ANY, STRING);
    private static final Map<String, Api> APIS = build();

    public static Map<String, Api> all() { return APIS; }
    public static Optional<Api> find(String name) { return Optional.ofNullable(APIS.get(name)); }
    public static Set<String> namespaces() {
        return APIS.keySet().stream().map(name -> name.substring(0, name.indexOf('.')))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public static ValueType parseType(String source) {
        if (source == null || source.isBlank()) return ANY;
        String value = source.replace(" ", "").trim();
        int open = value.indexOf('<');
        String raw = (open < 0 ? value : value.substring(0, open)).toUpperCase(Locale.ROOT);
        List<ValueType> args = open < 0 || !value.endsWith(">") ? List.of()
                : splitGenerics(value.substring(open + 1, value.length() - 1)).stream().map(ArcMagicApiCatalog::parseType).toList();
        Kind kind;
        try { kind = Kind.valueOf(raw); }
        catch (IllegalArgumentException ignored) { return ANY; }
        return new ValueType(kind, args);
    }

    private static List<String> splitGenerics(String value) {
        List<String> result = new ArrayList<>(); int depth = 0, start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '<') depth++; else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { result.add(value.substring(start, i)); start = i + 1; }
        }
        result.add(value.substring(start)); return result;
    }

    private static Map<String, Api> build() {
        Map<String, Api> apis = new TreeMap<>();
        add(apis, "mobs.hostile", "sense.hostile", ValueType.list(ENTITY), 8, 4, false, "範囲内の敵対Mobを安全に検索します",
                opt("radius", DISTANCE, "検索半径"), opt("limit", INT, "最大件数"));
        add(apis, "entities.hostile", "sense.hostile", ValueType.list(ENTITY), 8, 4, false, "条件付きで敵対Entityを検索します",
                opt("radius", DISTANCE, "検索半径"), opt("limit", INT, "最大件数"), opt("origin", LOCATION, "検索中心"));
        add(apis, "target.nearest", "sense.target", ValueType.option(ENTITY), 6, 2, false, "最も近い有効対象を取得します", opt("radius", DISTANCE, "検索半径"));
        add(apis, "entity.distance", "sense.distance", DISTANCE, 2, 0, false, "2つのEntity間の距離を取得します", req("from", ENTITY, "始点"), req("to", ENTITY, "終点"));
        add(apis, "raycast.entities", "sense.raycast", ValueType.list(ENTITY), 12, 2, false, "視線上のEntityを取得します",
                req("distance", DISTANCE, "最大距離"), opt("limit", INT, "最大件数"), opt("origin", LOCATION, "始点"), opt("width", FLOAT, "判定幅"));
        add(apis, "raycast.blocks", "sense.raycast", ValueType.option(BLOCK), 12, 2, false, "視線上のBlockを取得します",
                req("distance", DISTANCE, "最大距離"), opt("fluid", BOOL, "液体を含める"), opt("origin", LOCATION, "始点"), opt("passable", BOOL, "通過可能ブロックを含める"));
        add(apis, "blocks.query", "sense.block", ValueType.list(BLOCK), 8, 1, false, "周辺ブロックを制限付きで検索します",
                req("radius", DISTANCE, "検索半径"), opt("material", STRING, "素材"), opt("limit", INT, "最大件数"));
        add(apis, "items.inspect", "sense.item", ITEM, 4, 0, false, "許可されたアイテム情報を読み取ります", req("item", ITEM, "対象アイテム"));
        add(apis, "combat.can_attack", "combat.inspect", BOOL, 3, 0, false, "保護・PvP規則を含め攻撃可否を検査します", req("target", ENTITY, "対象"));
        add(apis, "combat.damage", "combat.damage", RESULT, 16, 12, true, "検証済み魔術ダメージを要求します",
                req("target", ENTITY, "対象"), req("amount", NUMBER, "威力"), opt("kind", STRING, "ダメージ種別"));
        add(apis, "combat.apply", "combat.effect", RESULT, 12, 8, true, "許可済み状態異常を要求します",
                req("target", ENTITY, "対象"), req("effect", STRING, "効果ID"), req("duration", DURATION, "持続時間"), opt("amplifier", INT, "強度"));
        add(apis, "spell.cast", "spell.cast", RESULT, 16, 12, true, "登録済み術式を対象または入力値付きで要求します", req("spell", STRING, "術式ID"), opt("target", ANY, "対象または入力値"));
        add(apis, "spell.effect", "spell.effect", RESULT, 12, 8, true, "安全な効果要求を生成します",
                req("effect", STRING, "効果ID"), req("target", ENTITY, "対象"), opt("power", NUMBER, "強度"), opt("duration", DURATION, "持続"));
        add(apis, "world.particle", "visual.particle", RESULT, 8, 2, true, "上限制御されたパーティクルを表示します",
                req("particle", STRING, "種類"), req("location", LOCATION, "位置"), opt("count", INT, "個数"), opt("spread", FLOAT, "広がり"));
        for (String shape : List.of("spiral", "ring", "sphere")) add(apis, "particles." + shape, "visual.particle", RESULT,
                shape.equals("sphere") ? 12 : shape.equals("spiral") ? 10 : 8, 2, true, shape + "形状のパーティクルを表示します",
                req("location", LOCATION, "中心"), opt("radius", DISTANCE, "半径"), opt("count", INT, "個数"), opt("particle", STRING, "種類"));
        add(apis, "display.magic_circle", "visual.display", RESULT, 20, 4, true, "一時的な魔法陣Displayを生成します",
                req("location", LOCATION, "位置"), opt("radius", DISTANCE, "半径"), opt("duration", DURATION, "持続"), opt("color", STRING, "色"), opt("rotation", FLOAT, "回転"));
        add(apis, "display.item", "visual.display", RESULT, 12, 3, true, "一時的なItem Displayを生成します", req("item", ITEM, "アイテム"), req("location", LOCATION, "位置"), opt("duration", DURATION, "持続"), opt("scale", FLOAT, "倍率"));
        add(apis, "display.text", "visual.display", RESULT, 12, 3, true, "一時的なText Displayを生成します", req("text", STRING, "文字"), req("location", LOCATION, "位置"), opt("duration", DURATION, "持続"), opt("scale", FLOAT, "倍率"));
        add(apis, "virtual_structure.show", "visual.structure", RESULT, 24, 8, true, "仮想構造を対象プレイヤーへ表示します", req("id", STRING, "構造ID"), req("location", LOCATION, "位置"), opt("duration", DURATION, "持続"), opt("target", ENTITY, "表示対象"));
        add(apis, "mana.current", "mana.read", MANA, 1, 0, false, "現在のMPを取得します");
        add(apis, "mana.consume", "mana.consume", RESULT, 4, 0, true, "MP消費を安全に要求します", req("amount", MANA, "消費MP"));
        add(apis, "math.clamp", "math", NUMBER, 2, 0, false, "数値を範囲内へ制限します", req("value", NUMBER, "値"), req("min", NUMBER, "最小"), req("max", NUMBER, "最大"));
        add(apis, "random.chance", "random", BOOL, 2, 0, false, "確率判定を行います", req("chance", PERCENT, "確率"));
        add(apis, "pdc.get", "storage.read", ANY, 3, 0, false, "ArcSys専用領域から値を読みます", req("key", STRING, "キー"), opt("default", ANY, "既定値"));
        add(apis, "pdc.set", "storage.write", RESULT, 5, 2, true, "ArcSys専用領域へ容量制限付きで保存します", req("key", STRING, "キー"), req("value", ANY, "値"), opt("ttl", DURATION, "有効期限"));
        add(apis, "state.get", "state.read", ANY, 2, 0, false, "術式永続状態を読みます", req("default", ANY, "既定値"), req("name", STRING, "状態名"), opt("scope", STRING, "範囲"), opt("ttl", DURATION, "有効期限"));
        add(apis, "state.set", "state.write", RESULT, 3, 1, true, "術式永続状態を保存します", req("name", STRING, "状態名"), req("value", ANY, "値"), opt("scope", STRING, "範囲"), opt("ttl", DURATION, "有効期限"));
        add(apis, "time.wait", "time.schedule", RESULT, 4, 0, true, "指定時間だけ安全に実行を中断します", req("duration", DURATION, "待機時間"));
        add(apis, "time.after", "time.schedule", RESULT, 4, 1, true, "遅延処理を予約します", req("duration", DURATION, "遅延"), req("signal", STRING, "シグナル"));
        add(apis, "event.emit", "event.emit", RESULT, 4, 0, true, "回路シグナルを送信します", req("signal", STRING, "シグナル"));
        add(apis, "event.cancel", "event.cancel", RESULT, 2, 0, true, "対応イベントをキャンセルします");
        add(apis, "skript.emit", "operator.skript.bridge", RESULT, 8, 2, true, "許可リスト登録済みSkriptイベントを呼びます", req("event", STRING, "イベント"), opt("payload", ANY, "データ"));
        return Collections.unmodifiableMap(apis);
    }

    private static Parameter req(String name, ValueType type, String description) { return new Parameter(name, type, false, description); }
    private static Parameter opt(String name, ValueType type, String description) { return new Parameter(name, type, true, description); }
    private static void add(Map<String, Api> target, String name, String capability, ValueType returns, int fuel,
                            int mana, boolean effectful, String description, Parameter... parameters) {
        target.put(name, new Api(name, capability, List.of(parameters), returns, fuel, mana, effectful, description));
    }
}
