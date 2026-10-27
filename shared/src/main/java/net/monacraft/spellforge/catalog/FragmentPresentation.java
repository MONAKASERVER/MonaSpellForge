package net.monacraft.spellforge.catalog;

import net.monacraft.spellforge.model.SpellModel.Category;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Player-facing names and descriptions shared by Paper item lore and the Fabric editor. */
public final class FragmentPresentation {
    private static final Map<String, Entry> ENTRIES = entries();

    private FragmentPresentation() { }

    public static Entry get(String fragmentId) {
        String id = fragmentId.toUpperCase(Locale.ROOT);
        return ENTRIES.getOrDefault(id, new Entry(id, "&f" + id, id, "未知の魔術欠片", "未知"));
    }

    public static Map<String, Entry> all() {
        return ENTRIES;
    }

    public static String categoryName(Category category) {
        return switch (category) {
            case TRIGGER -> "発動条件";
            case ELEMENT -> "属性核";
            case SHAPE -> "魔術形状";
            case TARGET -> "対象指定";
            case MODIFIER -> "術式強化";
            case LOGIC -> "論理演算";
        };
    }

    public record Entry(String id, String legacyName, String plainName, String description, String keywords) {
        public Entry {
            if (!legacyName.matches(".*&[0-9a-fk-or].*")) throw new IllegalArgumentException("A legacy color is required: " + id);
        }

        public String searchText(Category category) {
            return (id + " " + plainName + " " + description + " " + keywords + " " + categoryName(category))
                    .toLowerCase(Locale.ROOT);
        }
    }

    private static Map<String, Entry> entries() {
        LinkedHashMap<String, Entry> values = new LinkedHashMap<>();

        put(values, "RIGHT_CLICK", "&6開闢の右印《アレフ・イグニッション》", "右クリックで魔術回路を起動", "右クリック 起動 発動");
        put(values, "SNEAK", "&8潜影誓式《ノクス・ヴェール》", "スニークを契機に魔術回路を起動", "スニーク しゃがむ 起動");
        put(values, "DAMAGE_TAKEN", "&4血盟反響《ブラッド・リヴァーサル》", "被弾を契機に反応魔術を起動", "被弾 ダメージ 反撃");
        put(values, "LAND", "&2墜天震式《ガイア・フォール》", "着地した瞬間に魔術回路を起動", "着地 落下 起動");
        put(values, "PROJECTILE_HIT", "&e必中因果《アストラル・ピアス》", "飛翔体の命中を契機に魔術を起動", "矢 飛翔体 命中");
        put(values, "KILL_CONFIRM", "&4終焉宣告《モルス・シグナ》", "敵の討伐確定を契機に魔術を起動", "討伐 キル 撃破");

        put(values, "FIRE", "&4煉獄焔核《イグニス・レクイエム》", "対象を焼き尽くす煉獄の属性核", "炎 火炎 燃焼 攻撃");
        put(values, "ICE", "&b絶零晶核《ニヴルヘイム》", "熱と動きを奪う絶対零度の属性核", "氷 冷気 凍結 鈍足");
        put(values, "WIND", "&f天穿風核《シルフィード・エッジ》", "真空の刃を生む疾風の属性核", "風 疾風 斬撃");
        put(values, "EARTH", "&6星骸地核《テラ・グラビタス》", "大地を震わせる星骸の属性核", "土 大地 岩 衝撃");
        put(values, "LIGHTNING", "&e神鳴雷核《ケラウノス》", "神速の雷霆を招く属性核", "雷 電撃 落雷 神鳴");
        put(values, "WATER", "&b蒼淵水核《レヴィアタン》", "深淵の奔流を操る属性核", "水 海 蒼 波");
        put(values, "LIGHT", "&e聖煌光核《ルクス・サンクトゥス》", "邪を灼く聖なる光の属性核", "光 聖 浄化");
        put(values, "DARK", "&8冥蝕闇核《ノクティス・アビス》", "生命を蝕む深淵の属性核", "闇 暗黒 深淵");
        put(values, "POISON", "&2蝕命毒核《ヴェノム・ミアズマ》", "命脈を腐食させる猛毒の属性核", "毒 腐食 継続ダメージ");
        put(values, "GRAVITY", "&8星葬重核《グラビトン・ネメシス》", "重力場で万物を圧壊する属性核", "重力 吸引 圧壊");
        put(values, "HEAL", "&f生命聖核《アスクレピオス》", "傷と穢れを癒やす生命の属性核", "回復 治癒 生命");
        put(values, "ARCANE", "&b根源魔核《アルス・マグナ》", "純粋魔力を現世へ顕現する根源核", "魔力 無属性 根源");

        put(values, "PROJECTILE", "&6天翔術式《アストラル・ボルト》", "魔力を飛翔体として射出", "弾 射撃 遠距離");
        put(values, "BEAM", "&e断界術式《ラディウス・レイ》", "一直線の魔力光条を照射", "ビーム 光線 直線");
        put(values, "WAVE", "&b蒼波術式《カタクリズム》", "前方へ広がる魔力波を放出", "波 前方 範囲");
        put(values, "BURST", "&4爆界術式《ノヴァ・テンペスト》", "一点から魔力を爆発させる", "爆発 範囲 バースト");
        put(values, "BARRIER", "&f絶対防壁《アイギス・コード》", "術者を守る魔力障壁を展開", "防壁 防御 バリア");
        put(values, "RING", "&6輪廻術環《ウロボロス》", "術者を中心に環状術式を展開", "輪 円 環状 範囲");
        put(values, "LINE", "&7境界斬線《ディバイド・ライン》", "空間を断つ線状術式を形成", "線 直線 斬撃");
        put(values, "SIGIL", "&8禁呪刻印《アルカナ・シール》", "地に残留する魔法陣を刻む", "刻印 魔法陣 設置");

        put(values, "LOOK_TARGET", "&e慧眼指定《オムニサイト》", "視線上の対象を術式へ指定", "視線 照準 対象");
        put(values, "NEAREST_ENEMY", "&4猟犬選定《ハウンド・ロック》", "最も近い敵を自動捕捉", "近い敵 自動 ロック");
        put(values, "AREA_TARGETS", "&6万象捕捉《パンデモニウム》", "範囲内の複数対象を捕捉", "範囲 複数 対象");
        put(values, "SELF", "&b自己回帰《エゴ・リライト》", "術者自身を対象に指定", "自分 自己 術者");
        put(values, "ALLIES", "&2盟約共鳴《アライアンス》", "周囲の味方を対象に指定", "味方 仲間 支援");

        put(values, "POWER", "&4破滅増幅《オーバーロード》", "威力を増幅する代わりに魔力消費を増加", "威力 攻撃 増幅");
        put(values, "VELOCITY", "&b神速加護《アクセラレータ》", "飛翔体と術式の速度を加速", "速度 高速 加速");
        put(values, "RANGE", "&e無限射界《ホライゾン》", "魔術の有効射程を拡張", "射程 距離 遠距離");
        put(values, "DURATION", "&6永劫固定《エタニティ》", "魔術効果の持続時間を延長", "時間 持続 延長");
        put(values, "PIERCE", "&f因果貫通《ロンギヌス》", "防御と複数対象を貫通", "貫通 防御 無視");
        put(values, "HOMING", "&2宿命追尾《ファタリス》", "対象を追尾する因果を付与", "追尾 誘導 ホーミング");
        put(values, "SPLIT", "&4多重顕現《レギオン》", "術式を複数へ分裂", "分裂 複数 多重");
        put(values, "AREA_SCALE", "&6領域侵食《ドミニオン》", "魔術の効果領域を拡大", "範囲 拡大 領域");
        put(values, "EFFICIENCY", "&b無窮循環《ペルペトゥム》", "魔力消費と実行負荷を軽減", "効率 魔力 軽減");
        put(values, "AMPLIFY_X2", "&6双極増幅《デュアル・レゾナンス》", "攻撃力と効果量を2倍へ増幅", "2倍 威力 効果 増幅");
        put(values, "AMPLIFY_X3", "&4三界増幅《トリニティ・オーバー》", "攻撃力と効果量を3倍へ増幅", "3倍 威力 効果 増幅");
        put(values, "AMPLIFY_X4", "&8四劫増幅《テトラ・カタストロフ》", "攻撃力と効果量を4倍へ増幅", "4倍 威力 効果 増幅");
        put(values, "CIRCUIT_SPEED", "&b時界加速《クロノ・アクセラレータ》", "回路の遅延・周期処理を階位に応じて2～8倍へ加速", "回路 速度 2倍 4倍 6倍 8倍 高速 加速");
        put(values, "ARMOR_PENETRATION", "&6破城穿孔《アーマー・アナイアレイター》", "低負荷で防御の15～55%を貫き、多段術式の火力を安定化", "防御 貫通 鎧 低負荷 多段 攻撃");
        put(values, "TRUE_DAMAGE", "&4絶命真理《アポカリプス・ゼロ》", "莫大な魔力と実行負荷を代償に、防御不能の確定ダメージを与える", "確定 真 ダメージ 防御無視 一撃必殺 高負荷");

        put(values, "FORK", "&e運命分岐《ディヴァージェンス》", "実行経路を複数へ分岐", "分岐 経路 フォーク");
        put(values, "MERGE_ANY", "&6選択収束《コンヴァージェンス》", "いずれかの経路を受けて合流", "合流 いずれか OR");
        put(values, "MERGE_ALL", "&f万象統合《アカシック・ユニオン》", "すべての経路を待って統合", "合流 すべて AND");
        put(values, "IF", "&7因果審判《ジャッジメント》", "条件の真偽で実行経路を選択", "条件 分岐 真偽");
        put(values, "AND", "&2双律連結《デュアル・コード》", "二つの条件が共に真か判定", "論理積 AND 条件");
        put(values, "OR", "&e可能性解放《オルタナティヴ》", "いずれかの条件が真か判定", "論理和 OR 条件");
        put(values, "NOT", "&8理外反転《アンチノミー》", "条件の真偽を反転", "否定 NOT 反転");
        put(values, "DELAY", "&b時牢遅延《クロノ・プリズン》", "次の術式発動を遅延", "遅延 時間 待機");
        put(values, "REPEAT", "&4永劫輪廻《エターナル・リカー》", "接続した術式を反復実行", "反復 繰り返し ループ");
        put(values, "COMPARE", "&f天秤演算《リブラ・カリキュラス》", "二つの数値を比較して真偽を出力", "比較 数値 演算");
        put(values, "MOB_SENSOR", "&2獣界感知《ビースト・レーダー》", "半径3m以上の範囲にいるMobを検知", "センサー Mob 敵 周囲 3m");
        put(values, "AUTOMATION", "&e自律術式《デウス・オートマタ》", "起動後の術式を自律的に制御", "自動化 自律 オート");
        put(values, "CHAIN", "&6連鎖刻印《チェイン・ゲイボルグ》", "命中した術式を周囲の敵へ連鎖", "連鎖 チェイン 複数");
        put(values, "CALL", "&b召喚接続《アポクリファ・コール》", "術式の追加実行を呼び出す", "呼び出し コール 再実行");
        put(values, "SPELL_VARIABLE", "&9魔導変数《アルカナ・レジスタ》", "回路内の数値と状態を保存", "変数 保存 数値 状態");
        put(values, "COUNTER", "&f因果計数《カルマ・カウンタ》", "発動回数と命中回数を計測", "計数器 回数 命中");
        put(values, "MAGIC_GATE", "&8封魔門《ゲート・オブ・ソロモン》", "条件を満たすまで後続処理を停止", "魔術門 条件 停止 ゲート");
        put(values, "SEQUENCE", "&e天序制御《オルド・セクエンティア》", "複数の術式を定めた順番で実行", "順序 順番 シーケンス");
        put(values, "CHAOS", "&4混沌演算《カオス・アルゴリズム》", "確率で実行経路と効果を変化", "混沌 確率 ランダム 分岐");
        put(values, "MEMORY_CORE", "&b魔術記憶核《メモリア・アカシア》", "前回の魔術実行結果を記憶", "記憶 前回 結果 メモリ");
        put(values, "RETURN_CIRCUIT", "&4帰還回路《ウロボロス・リターン》", "前回結果を次回へ返し反復ごとに増幅", "帰還 回路 前回 増幅");
        put(values, "PARALLEL", "&f並列演算《パラレル・エイドロン》", "複数術式を独立実行して結果を統合", "並列 同時 統合");
        put(values, "MULTI_BRANCH", "&6万岐分流《オクタ・ディヴァイド》", "入力値に応じて3～8経路を切り替え", "多重 分岐 3 8 経路");
        put(values, "ENVIRONMENT_OBSERVER", "&2万象観測《ラプラス・アイ》", "敵HP・距離・周囲の敵数を取得", "環境 観測 HP 距離 敵数");
        put(values, "TIMER", "&9刻限計測《クロノス・ウォッチ》", "指定秒後に後続術式を実行", "時間 計測 秒 遅延");
        put(values, "PERIODIC_SIGNAL", "&e周期信号《パルス・ジェネシス》", "一定間隔で後続術式を反復実行", "周期 信号 間隔 自動");

        return Map.copyOf(values);
    }

    private static void put(Map<String, Entry> values, String id, String legacyName, String description, String keywords) {
        values.put(id, new Entry(id, legacyName, stripLegacy(legacyName), description, keywords));
    }

    private static String stripLegacy(String value) {
        return value.replaceAll("(?i)&[0-9A-FK-OR]", "");
    }
}
