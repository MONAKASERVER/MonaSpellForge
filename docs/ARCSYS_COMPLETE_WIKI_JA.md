# ArcSys 2.0 完全Wiki

> 対象: MonaSpellForge `2.0.0-alpha.12`
>
> Minecraft: Paper / Fabric 26.1.2、Java 25
>
> 最終更新: 2026-10-29
> 言語は開発中です。本書は企画案ではなく、リポジトリの実装とサーバー設定を照合した現行仕様です。

ArcSysは、MonaSpellForge専用のサーバー権威型魔術プログラミング言語です。プレイヤーは`.arc`ファイルに条件分岐、対象選択、状態記憶、時間制御、並列処理、Minecraft効果などを記述し、Paperサーバー上の安全な仮想マシン`ArcVM`で実行できます。

クライアントが送ったコンパイル結果は信用されません。ソースコードは毎回Paper側で字句解析、構文解析、型検査、Capability検査、バイトコード検証を受け、許可された効果だけがMinecraftへ反映されます。

---

## 目次

1. [ArcSysの全体像](#1-arcsysの全体像)
2. [必要環境と導入方法](#2-必要環境と導入方法)
3. [最初の魔術を作る](#3-最初の魔術を作る)
4. [`.arc`ファイルの種類](#4-arcファイルの種類)
5. [言語の基本規則](#5-言語の基本規則)
6. [型システム](#6-型システム)
7. [変数・定数・状態](#7-変数定数状態)
8. [関数とEffect関数](#8-関数とeffect関数)
9. [イベントハンドラ](#9-イベントハンドラ)
10. [演算子と制御構文](#10-演算子と制御構文)
11. [Option・Result・match](#11-optionresultmatch)
12. [時間・並列・継続実行](#12-時間並列継続実行)
13. [永続状態](#13-永続状態)
14. [struct・enum・module](#14-structenummodule)
15. [契約・必要条件・Capability](#15-契約必要条件capability)
16. [実行コンテキスト](#16-実行コンテキスト)
17. [標準魔術API完全一覧](#17-標準魔術api完全一覧)
18. [ARCSYS_CORE](#18-arcsys_core)
19. [VS Code連携](#19-vs-code連携)
20. [Minecraft Mod・Web・統合版](#20-minecraft-modweb統合版)
21. [コマンドと権限](#21-コマンドと権限)
22. [デバッグ・シミュレーション・プロファイル](#22-デバッグシミュレーションプロファイル)
23. [実行制限と負荷対策](#23-実行制限と負荷対策)
24. [セキュリティ](#24-セキュリティ)
25. [実用サンプル](#25-実用サンプル)
26. [診断コードとトラブル対処](#26-診断コードとトラブル対処)
27. [運営向け設定](#27-運営向け設定)
28. [現在の制限事項](#28-現在の制限事項)
29. [クイックリファレンス](#29-クイックリファレンス)

---

## 1. ArcSysの全体像

### 1.1 実行経路

```text
.arcソース
  ↓
字句解析・構文解析
  ↓
型検査・必要欠片・権限・Capability検査
  ↓
決定的なArcVMバイトコード生成
  ↓
バイトコード検証
  ↓
燃料・効果数を制限したArcVM実行
  ↓
Effect Requestを生成
  ↓
Paperが対象・距離・MP・PvP・保護領域・予算を再検証
  ↓
Minecraftへ効果を反映
```

ArcVMはBukkit APIを直接操作しません。VMが生成するのは「ダメージを与えたい」「粒子を出したい」といったデータだけです。Paperがその要求を再検証してから実際の処理を行います。

### 1.2 構成要素

| 要素 | 役割 |
|---|---|
| `arcsys-language` | Lexer、Parser、AST、型検査、標準API定義 |
| `arcsys-compiler` | Capability検査、モジュール連結、バイトコード生成、ソースマップ |
| `arcsys-vm` | 検証済みバイトコードの制限付き実行、継続、並列、プロファイル |
| `arcsys-cli` | オフライン検査、コンパイル、逆アセンブル |
| Paperプラグイン | 所有権、Revision、DB、MP、保護、Minecraft効果の最終判断 |
| Fabric Mod | 回路GUI、Mod内ArcSysコードエディター、`.arc`入出力、VS Codeとのlocalhost Bridge |
| VS Code拡張 | ハイライト、補完、Hover、検証、Deploy、デバッグ、ArcSys Studio |

### 1.3 信頼境界

- Paperが唯一の正本です。
- Fabric、Web、VS Codeの検証結果は編集支援にすぎません。
- Deploy時はPaperがソースから再コンパイルします。
- 実行中のRevisionは途中で書き換わりません。新しいRevisionは次回実行から使われます。

---

## 2. 必要環境と導入方法

### 2.1 プレイヤー環境

| 環境 | 回路編集 | Mod内コード編集 | ArcSys術式実行 | VS Codeライブ連携 |
|---|---:|---:|---:|---:|
| Java + Fabric Mod | 対応 | 対応 | 対応 | 対応 |
| Javaバニラ | サーバーGUI / Web | 非対応 | 対応 | 非対応 |
| 統合版 + Geyser/Floodgate | サーバーGUI / Web | 非対応 | 対応 | 非対応 |
| VS Codeのみ | ローカル編集 | 非対応 | サーバー実行不可 | 非対応 |

### 2.2 VS Code連携に必要なもの

1. サーバーと同じ版のMonaSpellForge Fabric Modを導入します。
2. VS Codeへ`ArcSys 2.0 — Magic Engine` VSIXを導入します。
3. Fabricでサーバーへログインします。
4. `/msf open`を実行します。
5. ライブラリ画面の`VS Code接続`、またはARCSYS_COREの`VS Codeで編集`を選択します。
6. 6桁コードがクリップボードへ自動コピーされます。
7. VS Codeのステータスバーで`ArcSys: かんたん接続`、またはArcSys Studioで`かんたんスタート`を選択します。
8. テンプレート、保存済み術式、現在開いている`.arc`のいずれかを選びます。

Bridgeは`127.0.0.1:27128`だけで待受します。コードは10分で失効し、Minecraftから切断するとセッションも失効します。

### 2.3 Mod内コードエディターに必要なもの

VS Codeを使わずに編集する場合は、サーバーと同じ版のFabric Modだけが必要です。

1. `/msf open`で回路を開きます。
2. `ARCSYS_CORE`を配置して回路を保存します。
3. Coreを右クリックして`Mod内でコード編集`を選びます。
4. コードを編集し、`Ctrl+Enter`で検証します。
5. `Ctrl+S`でPaperへ保存し、そのCoreへ`LATEST`で反映します。

コンパイル、所有権、Capability、必要欠片、RevisionはPaper側で再検証されます。Mod内で表示された成功結果だけを信用してサーバー検証を省略することはありません。

### 2.4 バージョンを揃える

PaperとFabricのプロトコル項目は追加されることがあります。Mod内コードエディターはalpha.12で追加されたため、PaperとFabricを同じalpha.12へ揃えてください。

---

## 3. 最初の魔術を作る

### 3.1 最小構成

```arc
spell HelloArcSys {
  on cast {
    return true;
  }
}
```

### 3.2 敵へダメージを与える

```arc
spell FirstStrike {
  on cast {
    let selected = target.nearest(radius: 16b);

    if (selected.is_some) {
      let enemy: Entity = selected.value;
      if (combat.can_attack(enemy)) {
        combat.damage(enemy, 8)?;
      }
    }

    return true;
  }
}
```

### 3.3 Mod内エディターからDeploy

1. ARCSYS_COREを右クリックし、`Mod内でコード編集`を開きます。
2. 上のコードを入力します。
3. `Ctrl+Enter`でPaper側検証を実行します。
4. エラーがなければ`Ctrl+S`で保存・Deploy・Core紐付けを実行します。
5. 回路のTriggerからCoreの`exec_in`へEXEC配線し、回路を発動します。

### 3.4 VS CodeからDeployと単体発動

1. VS Codeで`.arc`を開きます。
2. Minecraftとペアリングします。
3. `ArcSys: Validate Spell`で検証します。
4. `ArcSys: Deploy Spell to Minecraft`で保存します。
5. `/msf arc list`で術式UUIDを確認します。
6. `/msf arc cast <spell-uuid>`で発動します。

ARCSYS_COREへ紐付ける場合は、[ARCSYS_CORE](#18-arcsys_core)を参照してください。

---

## 4. `.arc`ファイルの種類

同じ`.arc`拡張子には2種類あります。

### 4.1 通常のArcSysソース

人間が記述するプログラムです。

```arc
spell FlameShot {
  on cast {
    let target = target.nearest(20b);
    if (target.is_some) {
      combat.damage(target.value, 12)?;
    }
  }
}
```

alpha.8以降、Fabricの`.arc読込`で選ぶとPaperへ送られ、サーバー側の検証後に保存済みArcSys術式として登録されます。

### 4.2 ノード回路キャリア

Fabricの`.arc出力`で生成される、ノード、配線、座標、パラメータを含むファイルです。先頭付近に次の署名付きマーカーがあります。

```text
// @msf-circuit-v2 ...
```

`.arc読込`で選ぶとノード回路として編集画面へ復元されます。欠片個体UUIDはファイルへ保存されません。読み込み先プレイヤーが正規に所有する同種・同Lvの欠片へ再割当てされます。

### 4.3 読み込み時の判定

| ファイル | 処理 |
|---|---|
| 有効な`@msf-circuit-v2`を含む | ノード回路として復元 |
| 通常のArcSysソース | Paperで再コンパイルし、新規術式として保存 |
| 改ざんされた回路キャリア | チェックサム不一致で拒否 |
| 必要な欠片が不足 | 回路の復元を拒否 |
| 通常ソースが23KB超 | Mod転送を拒否し、VS Code Bridgeを案内 |
| ファイル全体が1MiB超 | ローカル読込を拒否 |

通常のArcSysコードから任意のノード配置へ完全変換する機能はありません。通常ソースは「術式」として取り込まれ、回路キャリアだけが「ノード画面」へ復元されます。

---

## 5. 言語の基本規則

### 5.1 文字コードと大文字・小文字

- UTF-8を使用します。
- 識別子とキーワードは大文字・小文字を区別します。
- 識別子はUnicode文字、数字、`_`を使用できます。
- 識別子の先頭に数字は使えません。
- 1識別子は最大256文字、1文字列トークンは最大2,048文字です。

### 5.2 コメント

```arc
// 1行コメント

/*
  ブロックコメント
  /* 最大16段までネスト可能 */
*/
```

### 5.3 文末

通常の文は`;`で終えます。ブロックの最後であっても、可読性と互換性のため`;`を付けることを推奨します。

### 5.4 トップレベル宣言

```arc
module monaka.example;
use monaka.shared;

const BASE_POWER: Number = 8;

struct TargetInfo {
  target: Entity,
  power: Number
}

enum Decision {
  Strike(TargetInfo),
  Wait
}

fn helper(value: Number) -> Number {
  return value * 2;
}

spell Example {
  on cast {
    return true;
  }
}
```

root moduleには実行対象の`spell`を1つ定義します。

---

## 6. 型システム

### 6.1 基本型

| 型 | 内容 | 例 |
|---|---|---|
| `Int` | 整数 | `10` |
| `Float` | 小数 | `0.25` |
| `Number` | 数値共通 | `12`, `1.5` |
| `Bool` | 真偽値 | `true`, `false` |
| `String` | 文字列 | `"flame"` |
| `Any` | 動的な安全値 | API payloadなど |
| `None` | 値なし | `none` |

### 6.2 Minecraft参照型

| 型 | 内容 |
|---|---|
| `Entity` | サーバーが検証するEntity参照 |
| `Location` | ワールドと座標を持つ位置参照 |
| `World` | ワールド参照 |
| `Vector` | 方向・ベクトル |
| `Block` | ブロック参照 |
| `Item` | アイテム参照 |

これらはBukkitオブジェクトではありません。UUIDや座標を取得しても、それだけで対象を操作する権限は得られません。

### 6.3 単位型

| 型 | リテラル | 意味 |
|---|---|---|
| `Duration` | `20t`, `5s`, `7d` | tick、秒、日 |
| `Distance` | `24b` | ブロック距離 |
| `Mana` | `100mp` | MP |
| `Percent` | `35%` | 確率・割合 |

`5s`は100tickへ変換されます。異なる単位を誤って足したり比較したりすると型エラーになります。

### 6.4 ジェネリック型

```arc
let enemies: List<Entity> = mobs.hostile(radius: 16b, limit: 8);
let selected: Option<Entity> = target.nearest(radius: 16b);
let result: Result<Any, String> = combat.damage(selected.value, 10);
```

利用可能な型:

- `List<T>`
- `Option<T>`
- `Result<T, E>`

### 6.5 共通プロパティ

| 値 | プロパティ |
|---|---|
| List | `.count`, `.length`, `.is_empty` |
| Option | `.is_some`, `.is_none`, `.value` |
| Result | `.is_ok`, `.is_err`, `.value`, `.error` |
| Entity | `.hp_ratio`, `.location`, `.world`, `.is_hostile`, `.is_attackable` |
| Location / Vector | `.x`, `.y`, `.z` |
| Block | `.location` |

---

## 7. 変数・定数・状態

### 7.1 `let`

```arc
let power: Number = 12;
```

通常の`let`は再代入できません。

### 7.2 `let mut`

```arc
let mut hits: Int = 0;
hits += 1;
```

### 7.3 `const`

```arc
const MAX_TARGETS: Int = 8;
```

トップレベルまたはブロック内で利用できます。再代入できません。

### 7.4 `state`

```arc
spell Counter {
  state casts: Int = 0;

  on cast {
    casts += 1;
    return casts;
  }
}
```

`state`は書き換え可能です。`persist`を付けない状態の保存範囲は実行方式に依存するため、再起動をまたいで残したい場合は明示的な永続化を使用してください。

### 7.5 代入演算子

```arc
value = 10;
value += 2;
value -= 2;
value *= 2;
value /= 2;
```

---

## 8. 関数とEffect関数

### 8.1 純粋関数

```arc
fn is_execution_range(target: Entity) -> Bool {
  return target.hp_ratio < 0.30;
}
```

通常の`fn`から、ダメージ、MP消費、PDC書込などの副作用APIは呼べません。

### 8.2 Effect関数

```arc
effect fn strike(target: Entity, power: Number) -> Result<Any, String>
  uses [COMBAT_DAMAGE] {
  return combat.damage(target, power);
}
```

副作用を持つ関数は`effect fn`として宣言し、`uses`へ必要Capabilityを大文字＋`_`形式で列挙します。

例:

| API Capability | `uses`表記 |
|---|---|
| `combat.damage` | `COMBAT_DAMAGE` |
| `mana.consume` | `MANA_CONSUME` |
| `visual.particle` | `VISUAL_PARTICLE` |
| `storage.write` | `STORAGE_WRITE` |

Effect関数を別のEffect関数から呼ぶ場合、呼び出し元にも必要な`uses`が必要です。

### 8.3 制限

- 引数は最大16個です。
- 1回の呼び出しに渡せる引数は最大32個です。
- ArcVMの関数呼び出し深度は最大32です。
- 無制限再帰は燃料または呼び出し深度で停止します。

---

## 9. イベントハンドラ

### 9.1 構文として認識されるイベント

```arc
on cast { }
on pulse(20t) { }
on hit { }
on kill { }
on damage { }
on damage_taken { }
on death { }
on enter { }
on mana_below(100mp) { }
on spell_end { }
on signal { }
on projectile_impact { }
```

### 9.2 alpha.8で実戦起動経路が接続済みのイベント

| イベント | 起動方法 | 状態 |
|---|---|---|
| `on cast` | `/msf arc cast`、ARCSYS_CORE | 実戦接続済み |
| `on signal` | allowlist済みSkript Bridge / 管理コマンド | 実戦接続済み |

その他のイベントはParserとCompilerでは認識されますが、Paperの一般イベントリスナーから保存済みArcSys術式を自動起動する経路はalpha.8では未接続です。`on pulse`を書くだけで自動定期発動するとは限りません。

---

## 10. 演算子と制御構文

### 10.1 演算子

| 種類 | 演算子 |
|---|---|
| 算術 | `+`, `-`, `*`, `/`, `%` |
| 比較 | `==`, `!=`, `<`, `<=`, `>`, `>=` |
| 論理 | `!`, `&&`, `||` |
| 代入 | `=`, `+=`, `-=`, `*=`, `/=` |
| 範囲パターン | `..`, `..=` |
| Result伝播 | `?` |

### 10.2 `if / else`

```arc
if (target.hp_ratio < 0.3) {
  combat.damage(target, 30)?;
} else {
  combat.damage(target, 8)?;
}
```

### 10.3 `guard`

```arc
guard selected.is_some else {
  return false;
}
```

`guard 条件 else { ... }`は条件がfalseのときだけ失敗側ブロックを実行します。

### 10.4 `for`

```arc
let enemies = mobs.hostile(radius: 16b, limit: 8);
for enemy in enemies {
  combat.damage(enemy, 4)?;
}
```

### 10.5 `repeat`

```arc
repeat 3 {
  wait 5t;
}
```

反復回数は実行時予算の対象です。無制限ループは提供されません。

### 10.6 `break / continue`

```arc
for enemy in enemies {
  if (enemy.hp_ratio <= 0) {
    continue;
  }
  if (enemy.hp_ratio < 0.1) {
    break;
  }
}
```

### 10.7 `chance`

```arc
chance 25% {
  combat.damage(target, 20)?;
}
```

### 10.8 `defer`

```arc
defer {
  emit signal "spell_finished";
}
```

`defer`は通常終了、`return`、`break`、`continue`時にLIFO順で実行されます。cleanup内の複雑な制御フローには制限があります。

### 10.9 簡易構文

```arc
cast "Thunder" at target;
apply "slowness" to target for 40t;
emit signal "phase_changed";
wait 20t;
```

簡易構文も最終的には標準API呼び出しへ変換されます。接続先APIが未実装または簡略実装の場合、その制限を引き継ぎます。

---

## 11. Option・Result・match

### 11.1 Option

```arc
let selected: Option<Entity> = target.nearest(16b);

match selected {
  Some(enemy) => enemy.hp_ratio,
  None => 0
}
```

### 11.2 Result

```arc
let result = combat.damage(target, 10);

match result {
  Ok(value) => true,
  Err(reason) => false
}
```

### 11.3 `?`演算子

```arc
effect fn damage_checked(target: Entity) -> Result<Any, String>
  uses [COMBAT_DAMAGE] {
  combat.damage(target, 10)?;
  return Ok(true);
}
```

`?`は`Err`または値なしを早期伝播します。

### 11.4 `match`パターン

```arc
let rank = match value {
  0 => "zero",
  1..=10 => "small",
  11..=100 => "large",
  _ => "overflow"
};
```

`Option`、`Result`、ユーザー定義enumでは、全パターンを網羅するか`_`を用意してください。

---

## 12. 時間・並列・継続実行

### 12.1 `wait`

```arc
combat.damage(target, 4)?;
wait 20t;
combat.damage(target, 8)?;
```

ArcVMは待機地点で状態をContinuationへ保存し、指定tick後に再開します。

### 12.2 `after`

```arc
after 40t {
  combat.damage(target, 12)?;
}
```

現在の`after`は、ブロック冒頭へ安全な`wait`を挿入する形で動作します。完全に独立したバックグラウンドタスクではありません。

### 12.3 `every`

```arc
every 20t for 5s {
  combat.damage(target, 2)?;
}
```

- 間隔と期間は固定Durationで指定します。
- 展開後の回数は1～256回です。

### 12.4 `parallel all`

```arc
let result = await parallel all {
  combat.damage(target, 4)?;
  wait 10t;
  world.particle("END_ROD", target.location, 12, 0.2)?;
};
```

全経路の完了を待ちます。

### 12.5 `parallel any`

```arc
let result = await parallel any {
  wait 20t;
  wait 40t;
};
```

最初に完了した経路で合流します。

### 12.6 `race`

```arc
let winner = race {
  wait 10t;
  wait 30t;
};
```

勝者確定後の未採用経路は破棄されます。実行は決定的な協調スケジューラ上で行われ、Javaスレッドを自由生成するものではありません。

### 12.7 上限

- 並列経路は最大16個です。
- 1実行のContinuation停止回数は最大256回です。
- 1回の待機は最大1,728,000tickです。
- サーバー全体の既定同時タスク数は1,024です。
- 1tickの既定再開数は128です。

---

## 13. 永続状態

### 13.1 `persist`

```arc
spell HitCounter {
  state hits: Int = 0 persist(player, ttl: 7d);

  on cast {
    hits += 1;
    return hits;
  }
}
```

### 13.2 Scope

| Scope | 内容 |
|---|---|
| `player` | プレイヤー・術式単位でSQLiteへ保存 |
| `spell` | 術式スコープでSQLiteへ保存 |
| `session` | ログイン中だけメモリ保持。ログアウトで消去 |

### 13.3 TTL

```arc
state combo: Int = 0 persist(player, ttl: 30s);
```

TTLは1tick～10年の範囲です。期限切れデータは読み取り時に無効になります。

### 13.4 PDCとの違い

| 方式 | 保存先 | 主な用途 |
|---|---|---|
| `state ... persist` | ArcSys専用SQLite | 術式のカウンタ、状態機械、履歴 |
| `pdc.get / pdc.set` | プレイヤーPDCの専用namespace | 他の許可済み処理と共有する小さな値 |

PDCキーは`user_`で始まり、英小文字、数字、`.`, `_`, `-`だけを使い、48文字以内にしてください。値は文字列表現で最大512文字です。

---

## 14. struct・enum・module

### 14.1 struct

```arc
struct TargetInfo {
  target: Entity,
  power: Number
}

let info = TargetInfo(target: enemy, power: 20);
let amount = info.power;
```

- fieldは最大32個です。
- 名前付き引数または宣言順の引数で生成できます。
- 存在しないfieldは型エラーです。

### 14.2 enum

```arc
enum Decision {
  Strike(TargetInfo),
  Retreat,
  Wait
}

let decision = Decision::Strike(info);

let power = match decision {
  Decision::Strike(value) => value.power,
  Decision::Retreat => 0,
  Decision::Wait => 0
};
```

- variantは最大64個です。
- payload付きvariantを作れます。
- `match`でpayloadを変数へ束縛できます。

### 14.3 moduleとuse

共有側:

```arc
module monaka.shared;

pub fn double_power(value: Number) -> Number {
  return value * 2;
}
```

root側:

```arc
module monaka.main;
use monaka.shared;

spell ModularSpell {
  on cast {
    return double_power(10);
  }
}
```

VS Codeはrootソースに`use`があると、ワークスペース内の`.arc`を最大32ファイルまで収集し、Paperへproject bundleとして送ります。

制限:

- module数は最大32です。
- projectの合計文字数は最大524,288です。
- module名の重複と循環importはエラーです。
- root moduleには`spell`を1つだけ定義します。
- alpha.8の単体ファイルMod読込はproject収集を行いません。複数moduleはVS Code Bridgeを使用してください。

`type`、`trait`、`impl`は予約構文として読み飛ばされる部分があり、一般用途の完成した型エイリアス・traitシステムではありません。

---

## 15. 契約・必要条件・Capability

### 15.1 contract

```arc
contract SafeCast {
  requires mana_available >= 50mp;
  ensures true;
}
```

`requires`に失敗するとhandlerへ入りません。`ensures`に失敗すると正常結果として確定しません。

### 15.2 必要欠片

```arc
spell AdvancedSpell {
  requires fragments {
    FIRE: 3,
    ARCSYS_CORE: 2
  }

  on cast {
    return true;
  }
}
```

発動時に、インベントリまたは所持ノード保管領域へ指定ID・最低Lvの欠片があるかPaperが確認します。

### 15.3 必要権限

```arc
spell StaffSpell {
  requires permission "monaspellforge.special.staff";

  on cast {
    return true;
  }
}
```

### 15.4 Capability

Capabilityはソースが実行したい副作用の分類です。コメントやクライアント申告ではなく、CompilerがAPI呼び出しから導出します。

主な分類:

- `combat.damage`
- `combat.effect`
- `mana.consume`
- `visual.particle`
- `visual.display`
- `storage.write`
- `state.write`
- `event.emit`
- `operator.skript.bridge`

`operator.*` Capabilityは`monaspellforge.arcsys.operator`権限がないプレイヤーのDeployを拒否します。

---

## 16. 実行コンテキスト

### 16.1 共通値

| 名前 | 型 | 内容 |
|---|---|---|
| `caster` | Entity | 発動者 |
| `player` | Entity | 発動プレイヤー |
| `target` | Entity | 現在対象 |
| `target_location` | Location | 対象位置 |
| `location` | Location | 実行位置 |
| `world` | World | ワールド |
| `mana_available` | Mana | 実行開始時の利用可能MP |
| `trigger_type` | String | 起動種別 |
| `execution_id` | String | 実行ID |

### 16.2 ARCSYS_CORE専用値

| 名前 | 型 | 内容 |
|---|---|---|
| `circuit_id` | String | 回路UUID |
| `node_id` | String | CoreノードUUID |
| `number_in` | Float | NUMBER入力 |
| `boolean_in` | Bool | BOOLEAN入力 |
| `entity_in` | Entity | ENTITY入力。未接続時は視線対象または発動者 |
| `location_in` | Location | LOCATION入力 |

参照を持っていても、距離、ワールド、PvP、保護領域などの検査を回避できません。

---

## 17. 標準魔術API完全一覧

### 17.1 状態表示

| 記号 | 意味 |
|---|---|
| ✅ | alpha.8のPaper実戦ホストへ接続済み |
| ⚠ | 接続済みだが一部引数・形状・意味が簡略実装 |
| ⛔ | APIカタログには存在するが、実戦ホストは`CAPABILITY_NOT_AVAILABLE`を返す |

Fuelと基礎MPはAPIカタログ値です。実際の発動では回路、Core Lv、追加MP消費、サーバー設定の制限も適用されます。

### 17.2 センサー・対象

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `mobs.hostile(radius?: Distance, limit?: Int)` | `List<Entity>` | `sense.hostile` | 8 / 4 | ✅ 発動者周囲のMonster、半径上限64、件数上限32 |
| `entities.hostile(radius?, limit?, origin?)` | `List<Entity>` | `sense.hostile` | 8 / 4 | ⚠ 現状は`origin`を使わず発動者中心 |
| `target.nearest(radius?: Distance)` | `Option<Entity>` | `sense.target` | 6 / 2 | ✅ 距離順の最寄り敵対Mob |
| `entity.distance(from: Entity, to: Entity)` | `Distance` | `sense.distance` | 2 / 0 | ✅ 別ワールドは無限距離扱い |
| `raycast.entities(distance: Distance, limit?, origin?, width?)` | `List<Entity>` | `sense.raycast` | 12 / 2 | ⚠ 実戦ホストは最初の1体をOption相当で返す簡易実装 |
| `raycast.blocks(distance: Distance, fluid?, origin?, passable?)` | `Option<Block>` | `sense.raycast` | 12 / 2 | ⚠ 現状は発動者視線から最初のブロック |
| `blocks.query(radius: Distance, material?, limit?)` | `List<Block>` | `sense.block` | 8 / 1 | ⚠ 現状は周辺検索ではなく視線ブロック簡易実装 |
| `items.inspect(item: Item)` | `Item` | `sense.item` | 4 / 0 | ⛔ 実戦ホスト未接続 |

### 17.3 戦闘・術式

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `combat.can_attack(target: Entity)` | `Bool` | `combat.inspect` | 3 / 0 | ✅ 生存、同一ワールド、距離を検査。効果確定時に保護を再検査 |
| `combat.damage(target: Entity, amount: Number, kind?: String)` | `Result<Any,String>` | `combat.damage` | 16 / 12 | ✅ 1効果最大200、距離上限64、Paper damage event経由。Creative/Spectatorは無効 |
| `combat.apply(target: Entity, effect: String, duration: Duration, amplifier?: Int)` | `Result<Any,String>` | `combat.effect` | 12 / 8 | ⚠ 状態異常適用は接続済みだがalphaでは引数順の互換制限あり。amplifier最大4、既定時間上限600tick |
| `spell.cast(spell: String, target?: Any)` | `Result<Any,String>` | `spell.cast` | 16 / 12 | ⛔ 別術式呼出ホスト未接続 |
| `spell.effect(effect: String, target: Entity, power?, duration?)` | `Result<Any,String>` | `spell.effect` | 12 / 8 | ⛔ 汎用効果ホスト未接続 |

### 17.4 MP・数値・乱数

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `mana.current()` | `Mana` | `mana.read` | 1 / 0 | ✅ 予約済み消費を差し引いた現在MP |
| `mana.consume(amount: Mana)` | `Result<Any,String>` | `mana.consume` | 4 / 0 | ✅ VM中は予約し、確定時に消費。MP変動時は安全に失敗 |
| `math.clamp(value, min, max)` | `Number` | `math` | 2 / 0 | ✅ 数値を範囲内へ制限 |
| `random.chance(chance: Percent)` | `Bool` | `random` | 2 / 0 | ✅ 実行単位の決定的乱数 |

### 17.5 パーティクル・Display

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `world.particle(particle, location, count?, spread?)` | `Result` | `visual.particle` | 8 / 2 | ⚠ allowlist済み粒子だけ。位置・spread・countの一部は簡略実装 |
| `particles.spiral(location, radius?, count?, particle?)` | `Result` | `visual.particle` | 10 / 2 | ⚠ 形状は現在簡略表示 |
| `particles.ring(location, radius?, count?, particle?)` | `Result` | `visual.particle` | 8 / 2 | ⚠ 形状は現在簡略表示 |
| `particles.sphere(location, radius?, count?, particle?)` | `Result` | `visual.particle` | 12 / 2 | ⚠ 形状は現在簡略表示 |
| `display.magic_circle(location, radius?, duration?, color?, rotation?)` | `Result` | `visual.display` | 20 / 4 | ⚠ 一時BlockDisplayとして簡略実装。WorldGuard再検査あり |
| `display.item(item, location, duration?, scale?)` | `Result` | `visual.display` | 12 / 3 | ⚠ 一時ItemDisplay。素材・位置・scaleは制限付き |
| `display.text(text, location, duration?, scale?)` | `Result` | `visual.display` | 12 / 3 | ⚠ 最大128文字の一時TextDisplay |
| `virtual_structure.show(id, location, duration?, target?)` | `Result` | `visual.structure` | 24 / 8 | ⛔ 仮想構造ホスト未接続 |

許可済み粒子:

- `ENCHANT`
- `FLAME`
- `SOUL_FIRE_FLAME`
- `END_ROD`
- `ELECTRIC_SPARK`
- `CRIT`
- `WITCH`
- `PORTAL`

未許可名は`ENCHANT`へフォールバックします。

### 17.6 状態・PDC

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `pdc.get(key, default?)` | `Any` | `storage.read` | 3 / 0 | ✅ `user_`で始まる専用キーのみ |
| `pdc.set(key, value, ttl?)` | `Result` | `storage.write` | 5 / 2 | ✅ 最大512文字。現行PDC実装ではTTLは永続状態ほど厳密ではない |
| `state.get(default, name, scope?, ttl?)` | `Any` | `state.read` | 2 / 0 | ✅ 通常は`state ... persist`構文を推奨 |
| `state.set(name, value, scope?, ttl?)` | `Result` | `state.write` | 3 / 1 | ⚠ Compilerの永続state loweringは接続済み。直接API呼出はalpha互換制限あり |

### 17.7 時間・イベント・Skript

| API | 戻り値 | Capability | Fuel / MP | 状態・備考 |
|---|---|---|---:|---|
| `time.wait(duration)` | `Result` | `time.schedule` | 4 / 0 | ✅ CompilerがArcVMのContinuation命令へ変換 |
| `time.after(duration, signal)` | `Result` | `time.schedule` | 4 / 1 | ⛔ API直接呼出ホスト未接続。`after {}`構文を使用 |
| `event.emit(signal)` | `Result` | `event.emit` | 4 / 0 | ✅ allowlist済みローカルsignalを送信 |
| `event.cancel()` | `Result` | `event.cancel` | 2 / 0 | ⛔ イベント取消ホスト未接続 |
| `skript.emit(event, payload?)` | `Result` | `operator.skript.bridge` | 8 / 2 | ✅ allowlist必須、運営Capability、値は最大256文字 |

### 17.8 粒子や効果が出ないとき

1. APIが上表で⛔になっていないか確認します。
2. ARCSYS_COREでは`allow-production-effects`が有効か確認します。
3. WorldGuard、PvP、距離、ワールド、対象生存を確認します。
4. Effect契約上限、Core Lvの効果数上限、1tick効果数上限を確認します。
5. `ArcSys / ArcVM`出力とサーバー診断コードを確認します。

---

## 18. ARCSYS_CORE

`ARCSYS_CORE`は73番目の正規欠片、正式名は「術式演算核《アルカナ・プロセッサ》」です。

| 項目 | 値 |
|---|---|
| Category | `LOGIC` |
| Rarity | `LEGENDARY` |
| 最大Lv | 5 |
| 実行エンジン | ArcVM |

### 18.1 ポート

入力:

- `exec_in: EXEC`
- `entity_in: ENTITY`
- `location_in: LOCATION`
- `number_in: NUMBER`
- `boolean_in: BOOLEAN`

出力:

- `exec_out: EXEC`
- `exec_fail: EXEC`
- `entity_out: ENTITY`
- `number_out: NUMBER`
- `boolean_out: BOOLEAN`

Coreは回路内に置くだけでは実行されません。Triggerから`EXEC`エッジで到達したCoreだけが起動します。

### 18.2 Level性能

| Lv | VM Fuel | 最大効果数 | 基礎MP | Cooldown | 静的回路負荷 |
|---:|---:|---:|---:|---:|---:|
| 1 | 64 | 2 | 40 | 80tick | 12 |
| 2 | 96 | 3 | 55 | 70tick | 16 |
| 3 | 128 | 4 | 75 | 60tick | 24 |
| 4 | 192 | 6 | 100 | 50tick | 32 |
| 5 | 256 | 8 | 130 | 40tick | 40 |

値は設定で変更できます。

### 18.3 Binding Mode

| Mode | 動作 |
|---|---|
| `PINNED` | 指定Revisionを固定して実行 |
| `LATEST` | 実行開始時に最新の検証済みRevisionを確定 |

`LATEST`でも実行途中に内容は変わりません。

### 18.4 Fabricで紐付ける

1. 回路へARCSYS_COREを配置します。
2. Coreを右クリックします。
3. 次のいずれかを選びます。

| 操作 | 用途 |
|---|---|
| `Mod内でコード編集` | Minecraft内でArcSysを書き、検証・保存・Core反映まで行う |
| `VS Codeで編集` | VS Codeの補完・LSP・デバッグを使って編集する |
| `ArcSys制御パネル` | 保存済み術式、Revision mode、検証、ログ、シミュレーションを管理する |

各編集画面を開く前に回路が自動保存されます。保存に失敗した場合は編集画面へ進みません。

`VS Codeで編集`では、その文書の保存時にDeployと`LATEST`紐付けが自動実行されます。

`Mod内でコード編集`では、`Ctrl+S`を押すとPaperで検証・Deployしたあと、同じCoreへ`LATEST`で紐付けます。検証または権限確認に失敗した場合、現在のCore紐付けは変更されません。

発動条件や魔術形状がまだ不足している回路も、編集用の下書きとして保存できます。下書きは自動的に無効化され、検証に合格するまで有効化・実行できません。

### 18.5 Core制御パネルのモード

制御パネル右上から表示モードを選択できます。

#### かんたんモード

初期表示です。操作を次の3段階に絞っています。

1. `VS Codeで術式を作る・編集`
2. 必要に応じて保存済み術式を選択
3. 選択した術式をCoreで使用

保存済み術式をCoreで使用する場合は自動的に`LATEST`を選びます。

#### アドバンスドモード

次の管理機能を表示します。

- `PINNED` / `LATEST`切替
- 設定保存
- 紐付け解除
- プログラム検証
- 実行ログ
- シミュレーション
- VS Codeかんたん編集
- 状態再読込

### 18.6 Mod内ArcSysコードエディター

Mod内エディターは、VS Codeを起動せずに単一ファイルのArcSys術式を作成する画面です。

| 操作 | 動作 |
|---|---|
| `新規` | Core固有名を使用した最小テンプレートを作成 |
| `保存済みを開く` | 自分が所有する保存済み術式を順に読み込む |
| `下書き保存` | 現在のコードを端末内へ一時保存 |
| `検証` / `Ctrl+Enter` | Paper側で構文・型・Capability・必要条件を検証 |
| `保存・反映` / `Ctrl+S` | PaperへDeployし、成功後にCoreへ`LATEST`で紐付け |
| `閉じる` | 未Deployの編集内容を一時保存して回路画面へ戻る |

編集ショートカット:

| キー | 動作 |
|---|---|
| `Tab` | 2スペース字下げ。複数行選択時は一括字下げ |
| `Shift+Tab` | 現在行または選択行の字下げ解除 |
| `Enter` | 現在行の字下げを引き継ぐ。`{}`内では1段深くする |
| `{` / `(` / `[` | 対応する閉じ括弧を自動補完 |
| `Ctrl+Z` / `Ctrl+Y` | 最大100段階の元に戻す／やり直し |
| `Ctrl+F` | コード内検索。Enterで次の一致へ移動 |
| `Ctrl+/` | 現在行の`//`コメントを切替 |
| `Ctrl+Shift+S` | 手動で一時保存 |

画面下部にはCore状態、カーソルの行・列、総行数、文字数、下書き状態、Paper診断が表示されます。

#### 自動一時保存と復元

- 最後の入力から1.5秒後に端末内へ自動保存します。
- 画面を閉じる際も、未Deployの内容を保存します。
- Minecraftが異常終了しても、同じ回路・同じCoreを開くと復元します。
- 下書きはプレイヤーのクライアント端末だけに存在し、Paper上の正式Revisionではありません。
- PaperへのDeployとCore紐付けが両方成功した時だけ下書きを削除します。
- 下書きが復元された場合、サーバー版を開き直すまで復元内容を優先して表示します。

Mod内エディターは単一ファイル向けです。複数module、LSP補完、Hover、プロファイル、サーバーデバッガーを中心に使う場合はVS Codeを利用してください。

### 18.7 コマンドで紐付ける

```text
/msf arc core list
/msf arc core inspect <circuitId> <nodeId>
/msf arc core bind <circuitId> <nodeId> <spellId> [PINNED|LATEST] [revision]
/msf arc core unbind <circuitId> <nodeId>
```

### 18.8 戻り値から出力ポートへ

```arc
return true;       // boolean_out
return 12;         // number_out
return entity_in;  // entity_out
```

Map形式で`entity_out`、`number_out`、`boolean_out`を返すこともできます。`Some`と`Ok`は出力変換時に中身が取り出されます。

### 18.9 Coreの停止理由

| Code | 内容 |
|---|---|
| `DISABLED` | Core機能が設定で無効 |
| `BINDING_INVALID` | 所有者、ノード、欠片個体が不一致 |
| `REVISION_CONFLICT` | 対象Revisionがない |
| `COOLDOWN` | 再使用待ち |
| `CONCURRENCY_LIMIT` | 同時実行上限 |
| `TPS_GUARD` | TPSが最低値未満 |
| `COMPILE_ERROR` | 保存済み術式の再コンパイル失敗 |
| `REQUIREMENT` | 必要欠片または権限不足 |
| `BUDGET_EXCEEDED`相当 | Fuel、効果数、tick予算超過 |

---

## 19. VS Code連携

### 19.1 コマンド一覧

| コマンド | 内容 |
|---|---|
| `ArcSys: かんたんスタート` | クリップボードの6桁コードを読み、接続から初回Deployまで案内 |
| `ArcSys: テンプレートから魔術を作る` | 安全なスターターテンプレートを作成 |
| `ArcSys: Connect to Minecraft` | 6桁コードで接続 |
| `ArcSys: Open Spell from Minecraft` | 保存済み術式を開く |
| `ArcSys: Compile Current Spell` | Paperでコンパイル |
| `ArcSys: Validate Spell` | Paperで検証 |
| `ArcSys: Deploy Spell to Minecraft` | 新規Revisionを保存 |
| `ArcSys: Run Simulation` | 仮想実行してEffect Requestを確認 |
| `ArcSys: Profile Current Spell` | 命令、Host API、Fuel、stackを集計 |
| `ArcSys: Open Node Graph` | ソースから解析グラフ情報を表示 |
| `ArcSys: Start Debugging` | サーバー側デバッグ開始 |
| `ArcSys: Continue Debugging` | 継続 |
| `ArcSys: Step Instruction` | 1命令ステップ |
| `ArcSys: Stop Debugging` | 停止 |
| `ArcSys: View ArcVM Logs` | 監査・実行ログ表示 |
| `ArcSys: Bind Program to Core` | 術式をCoreへ紐付け |
| `ArcSys: Unbind Program from Core` | 解除 |
| `ArcSys: List ArcSys Cores` | Core一覧 |
| `ArcSys: Inspect Core` | Core状態表示 |
| `ArcSys: Validate Core Binding` | 紐付け検証 |
| `ArcSys: Toggle Auto Deploy on Save` | 通常`.arc`の保存時Deploy切替 |
| `ArcSys: Disconnect` | Bridge切断 |

### 19.2 Auto Deploy

通常の`.arc`では初期OFFです。ステータスバーの`ArcSys Auto Deploy: OFF`をクリックするとONになります。

- 保存後650msのdebounceでDeployします。
- Paperが再コンパイルします。
- 失敗時は既存Revisionを置き換えません。
- 通常Auto DeployはCoreを自動で紐付けません。
- Core専用編集セッションだけは、設定に関係なく保存時Deploy＋`LATEST`紐付けを行います。

### 19.3 かんたんスタート

alpha.11以降は次の流れで初回Deployできます。

1. Minecraftで`VS Code接続`またはCoreの`VS Codeで編集`を押します。
2. 6桁コードがクリップボードへ自動コピーされます。
3. VS Codeで`ArcSys: かんたんスタート`を実行します。
4. テンプレート、保存済み術式、現在の`.arc`から開始方法を選びます。
5. テンプレートは即座にPaperへ検証・Deployされます。
6. Auto Deployが有効になり、以後は`Ctrl+S`で更新できます。

Core専用編集の場合は、保存成功後に対象Coreへ自動的に`LATEST`で紐付けます。通常接続のAuto DeployはCoreを自動選択しません。

### 19.4 LSP機能

- ArcSysシンタックスハイライト
- キーワード・標準API補完
- API signature、MP、CapabilityのHover
- 同一ファイル内の関数・変数定義ジャンプ
- 括弧、文字列、単位、危険語の軽量診断
- 2スペースインデントのFormatter

重要: LSP診断は軽量な編集支援です。最終判定はPaperのCompilerです。

### 19.5 ArcSys Studio

サイドバーに次を表示します。

- 接続サーバー・プレイヤー
- 保存済み術式とRevision
- 所有欠片
- ARCSYS_CORE状態
- 使用可能APIとMP/Capability
- Deploy履歴

### 19.6 Revision競合

同じ術式を複数画面で編集すると、`baseRevision`が古い側は`REVISION_CONFLICT`で拒否されます。Minecraftから最新版を開き直し、変更を統合して再Deployしてください。

---

## 20. Minecraft Mod・Web・統合版

### 20.1 Fabric Mod

- ノード回路GUI
- ARCSYS_COREのかんたん／アドバンスド制御パネル
- Mod内ArcSysコードエディター
- コードの自動字下げ、括弧補完、検索、Undo/Redo、行コメント
- 未Deployコードの自動一時保存と復元
- Paper検証・Deploy・Core自動紐付け
- `.arc出力`と`.arc読込`
- Core制御パネル
- VS Code Bridge開始
- ノード移動、配線、ズーム、Undo/Redoなど

### 20.2 ModなしJava・統合版

ArcVMはPaperで動くため、実行自体にModは不要です。

利用可能な代替経路:

- `/msf web`でWebエディター連携
- `/msf arc list`、`cast`、`stop`
- `/msf arc core ...`でCore操作
- サーバーGUIで回路管理

VS CodeライブBridgeだけはFabric Modが必須です。

### 20.3 Webエディター

Webエディターは回路作成・保存の代替UIです。VS Codeのlocalhost Bridgeやローカル`.arc`ワークスペース機能とは別物です。

---

## 21. コマンドと権限

### 21.1 プレイヤーコマンド

| コマンド | 内容 |
|---|---|
| `/msf open` | ModまたはサーバーGUIを開く |
| `/msf web` | Web連携コードを発行 |
| `/msf arc list` | 所有ArcSys術式一覧 |
| `/msf arc cast <uuid>` | `on cast`を実行 |
| `/msf arc stop` | 自分のArcVMタスクを停止 |
| `/msf arc core list` | Core一覧 |
| `/msf arc core inspect ...` | Core詳細 |
| `/msf arc core bind ...` | Core紐付け |
| `/msf arc core unbind ...` | Core解除 |

### 21.2 運営コマンド

| コマンド | 内容 |
|---|---|
| `/msf arc signal <player> <signal> [payload]` | allowlist済みsignalを送信 |
| `/msf fragment give <player> ARCSYS_CORE <level> <amount>` | Core欠片発行 |
| `/msf admin stopcasts <player|all>` | 既存回路キャスト緊急停止 |
| `/msf admin audit <uuid|player>` | 監査概要 |

### 21.3 権限

| Permission | 既定 | 内容 |
|---|---|---|
| `monaspellforge.use` | 全員 | `/msf` |
| `monaspellforge.arcsys.use` | 全員 | ArcSys利用 |
| `monaspellforge.arcsys.cast` | 全員 | 保存済みArcSys発動 |
| `monaspellforge.arcsys.deploy` | 全員 | Deploy |
| `monaspellforge.arcsys.vscode` | 全員 | VS Code Bridge |
| `monaspellforge.arcsys.debug` | OP | デバッグ・Simulation |
| `monaspellforge.arcsys.operator` | OP | operator Capability |
| `monaspellforge.arcsys.signal` | OP | signal管理コマンド |
| `monaspellforge.arcsys.core.use` | 全員 | Core閲覧・利用 |
| `monaspellforge.arcsys.core.bind` | 全員 | 自分のCore紐付け |
| `monaspellforge.arcsys.core.admin` | OP | Core管理 |

所有権検査は権限と別に行われます。一般プレイヤーが他人の回路・術式UUIDを指定しても操作できません。

---

## 22. デバッグ・シミュレーション・プロファイル

### 22.1 ValidateとCompile

- `Validate`: 構文、型、API、Capability、必要条件を検査します。
- `Compile`: 上記に加えて命令数、Fuel、hash、source mapなどを返します。

### 22.2 Simulation

Minecraft世界を変更せず、Effect Requestを生成して確認します。待機は仮想tickとして進められます。

### 22.3 Debugger

デバッガーはPaper上のArcVMを使用します。

1. `ArcSys: Start Debugging`
2. watchする変数名をカンマ区切りで入力
3. `ArcSys: Step Instruction`または`Continue Debugging`
4. `ArcSys / ArcVM`出力でPC、Opcode、stack、Fuel、変数、traceを確認
5. `ArcSys: Stop Debugging`で終了

現在のVS Code UIは行クリック式ブレークポイントを自動送信せず、開始要求では空のbreakpoint配列を送ります。命令stepとwatchは利用できます。

### 22.4 Profiler

Profilerは次を集計します。

- 命令別実行数
- Host API呼び出し
- source module、行、列
- 最大stack深度
- 最小残Fuel
- Effect Request

### 22.5 Source Map

Compilerの各命令はmodule・行・列へ対応付けられます。Runtime TrapやProfile結果から元ソースを追跡できます。

---

## 23. 実行制限と負荷対策

### 23.1 言語・Compiler

| 項目 | 上限 |
|---|---:|
| 単一ソース | 65,536 UTF-8 bytes |
| Token文字列 | 2,048文字 |
| 識別子 | 256文字 |
| Parserネスト | 96 |
| 関数引数 | 16 |
| 呼出引数 | 32 |
| struct field | 32 |
| enum variant | 64 |
| module | 32 |
| project合計 | 524,288文字 |
| bytecode命令 | 4,096 |

### 23.2 ArcVM

| 項目 | 上限 |
|---|---:|
| stack | 256 |
| List要素 | 256 |
| String | 2,048文字 |
| call depth | 32 |
| suspension | 256 |
| 1回のwait | 1,728,000tick |
| 通常production Fuel | 512 |
| 通常production Effect | 64 |

ARCSYS_COREでは上表のproduction値より低いLv別上限が適用されます。

### 23.3 Paper

既定値:

| 項目 | 値 |
|---|---:|
| ArcVM同時task | 1,024 |
| 1tick再開数 | 128 |
| 1効果最大damage | 200 |
| 最大対象距離 | 64 block |
| Potion最大時間 | 600tick |
| Display最大時間 | 200tick |
| 1segment最大Effect | 64 |
| 1segment最大Display | 8 |
| 1segment最大PDC書込 | 8 |
| 1segment最大Skript signal | 4 |
| Core最低TPS | 17.0 |

ログアウト、プラグイン停止、管理停止では継続タスクがキャンセルされます。

---

## 24. セキュリティ

ArcSysから次の処理はできません。

- 任意Java / Kotlin / Python実行
- Reflection
- OSコマンド・プロセス生成
- ファイル読み書き
- 任意ネットワーク通信
- SQL直接実行
- JVM ClassLoader
- native library
- 任意コンソールコマンド
- 無制限thread生成
- 動的`eval`

### 24.1 Minecraft効果の再検査

- プレイヤーと術式の所有権
- Revision
- 必要欠片・権限
- Entityの生存・ワールド・距離
- Creative / Spectator保護
- PvPとPaper damage event
- WorldGuard保護
- MP残量
- Effect Contract
- TPSと同時実行予算

### 24.2 Skript Bridge

- `arc-runtime.skript-allowlist`へ登録したsignalだけを通します。
- 任意Skriptソースは実行できません。
- payloadはBool、Numberまたは最大256文字のStringへ制限されます。
- 1プレイヤー1tickあたり最大4 inbound signalです。
- 1signalで起動できる有効handlerは最大8です。

### 24.3 WorldGuardとMythicMobs

- Display・PDC等の変更は保護Adapterで再検査します。
- WorldGuardが存在するのにAdapter判定できない場合はfail-closedで拒否します。
- MythicMobs対象は取得可能な場合`mythic_type`をEntity参照へ追加します。
- MythicMobsであってもダメージは通常のPaperイベントを通ります。

---

## 25. 実用サンプル

### 25.1 低HP処刑

```arc
spell ExecutionJudge {
  on cast {
    let selected = target.nearest(radius: 24b);
    guard selected.is_some else {
      return false;
    }

    let enemy = selected.value;
    guard combat.can_attack(enemy) else {
      return false;
    }

    if (enemy.hp_ratio < 0.25) {
      combat.damage(enemy, 40)?;
    } else {
      combat.damage(enemy, 8)?;
    }

    return true;
  }
}
```

### 25.2 範囲攻撃

```arc
spell AreaBurst {
  on cast {
    let enemies = mobs.hostile(radius: 12b, limit: 8);
    let mut hits: Int = 0;

    for enemy in enemies {
      if (combat.can_attack(enemy)) {
        combat.damage(enemy, 5)?;
        hits += 1;
      }
    }

    return hits;
  }
}
```

### 25.3 5回ごとの強撃

```arc
spell ComboMemory {
  state hits: Int = 0 persist(player, ttl: 7d);

  on cast {
    let selected = target.nearest(radius: 16b);
    guard selected.is_some else {
      return hits;
    }

    hits += 1;
    if (hits >= 5) {
      combat.damage(selected.value, 30)?;
      hits = 0;
    } else {
      combat.damage(selected.value, 6)?;
    }

    return hits;
  }
}
```

### 25.4 MPを自前消費する術式

```arc
spell ManaBurst {
  on cast {
    if (mana.current() < 100mp) {
      return Err("NO_MANA");
    }

    mana.consume(100mp)?;
    let targets = mobs.hostile(radius: 10b, limit: 4);
    for enemy in targets {
      combat.damage(enemy, 12)?;
    }
    return Ok(targets.count);
  }
}
```

### 25.5 Core入力による分岐

```arc
spell CoreRouter {
  on cast {
    if (boolean_in) {
      combat.damage(entity_in, 6 + number_in)?;
      return number_in;
    }
    return 0;
  }
}
```

### 25.6 signal handler

```arc
spell SignalReceiver {
  on signal {
    return true;
  }
}
```

この術式をsignal起動するには、術式が有効化され、signalがサーバーallowlistへ登録されている必要があります。

### 25.7 module分割

`shared.arc`:

```arc
module example.shared;

pub fn scaled(value: Number) -> Number {
  return math.clamp(value * 2, 0, 50);
}
```

`main.arc`:

```arc
module example.main;
use example.shared;

spell ModuleAttack {
  on cast {
    let target = target.nearest(16b);
    if (target.is_some) {
      combat.damage(target.value, scaled(8))?;
    }
    return true;
  }
}
```

---

## 26. 診断コードとトラブル対処

### 26.1 診断prefix

| Prefix | 分類 |
|---|---|
| `ARC-E` | Lexer、Parser、Compiler error |
| `ARC-T` | 型error |
| `ARC-TW` | 型移行warning |
| `ARC-M` | module / project link error |
| `ARC-V` | bytecode verifier error |
| `ARC-R` | ArcVM runtime trap |
| `ARC-LSP` | VS Code軽量診断 |

### 26.2 `.arc`を読み込めない

1. PaperとFabricがalpha.12以上で同じ版か確認します。
2. 拡張子が`.arc`か確認します。
3. 通常ArcSysなら23KB以内か確認します。
4. 複数moduleなら単体Mod読込ではなくVS Code Bridgeを使います。
5. ノード回路キャリアなら、必要な欠片を所持しているか確認します。
6. 回路キャリアの`@msf-circuit-v2`行を編集していないか確認します。
7. 画面下部に表示された具体的な構文エラーと行番号を確認します。

### 26.3 VS Codeから接続できない

- Minecraft側で先にBridgeを開始します。
- 6桁コードの有効期限は10分です。
- MinecraftとVS Codeが同じPC上にある必要があります。
- `127.0.0.1:27128`を別アプリが使用していないか確認します。
- Minecraft再接続後は新しいコードを発行します。

### 26.4 Deployできない

| Error | 対処 |
|---|---|
| `NO_PERMISSION` | LuckPerms権限を確認 |
| `REVISION_CONFLICT` | Minecraftから最新版を開き直す |
| `SOURCE_REQUIRED` | `.arc`本文が空でないか確認 |
| `SOURCE_SIZE` | ファイルをmodule分割する |
| `OPERATOR_CAPABILITY` | operator APIを削除するか運営権限で実行 |
| `REQUIREMENT` | 必要欠片・権限を所持する |

### 26.5 発動しても何も起きない

- `on cast`があるか確認します。
- `/msf arc cast`に正しいspell UUIDを指定します。
- API表で実戦ホスト未接続になっていないか確認します。
- SimulationはEffect Requestを表示するだけで世界を変更しません。
- Coreでは`allow-production-effects`を確認します。
- MP、距離、Creative/Spectator、WorldGuard、PvPを確認します。
- `/msf arc stop`で古い継続タスクを停止して再試行します。

### 26.6 Fuel不足

- 長いloopや多数のAPI呼出を減らします。
- `limit`で対象数を抑えます。
- 同じ計算を変数へ保存します。
- Coreなら高Lvへ上げます。
- Profileで消費命令を確認します。

### 26.7 Coreへ反映されない

- 回路を先に保存します。
- `ARCSYS_CORE`の所有権とnode IDを確認します。
- `ArcSys: Deploy Spell to Minecraft`を先に実行します。
- `PINNED`なら新Revisionへ自動移行しません。
- 自動追従させたい場合は`LATEST`を使います。
- Core専用VS Code編集では対象文書だけを保存します。

### 26.8 Mod内エディターの下書きが復元されない

- PaperとFabricが同じalpha.12以上か確認します。
- 同じ回路・同じARCSYS_COREを開いているか確認します。下書きはCore単位です。
- コードが空、または保存上限を超えていないか確認します。
- 画面下部の下書き状態が`下書き保存失敗`になっていないか確認します。
- DeployとCore紐付けが成功した下書きは、正式Revisionへ移行したため自動削除されます。

### 26.9 Mod内エディターから保存できない

1. `Ctrl+Enter`でPaper診断を表示します。
2. 画面下部の行・列と診断コードを確認します。
3. `REVISION_CONFLICT`の場合は保存済み術式を開き直し、変更を統合します。
4. `REQUIREMENT`の場合は必要な欠片または権限を確認します。
5. Coreの回路が保存済みで、同じプレイヤーが所有しているか確認します。
6. 失敗時も端末内の下書きは残るため、コードを消さずに修正できます。

---

## 27. 運営向け設定

### 27.1 Arc runtime

```yaml
arc-runtime:
  max-active-tasks: 1024
  max-resumes-per-tick: 128
  max-damage-per-effect: 200.0
  max-target-range: 64.0
  max-potion-duration-ticks: 600
  max-display-duration-ticks: 200
  skript-allowlist: []
  contract:
    max-effects-per-segment: 64
    max-displays-per-segment: 8
    max-pdc-writes-per-segment: 8
    max-skript-signals-per-segment: 4
```

### 27.2 ARCSYS_CORE

```yaml
arcsys:
  core:
    enabled: false
    allow-production-effects: false
    require-owner: true
    prevent-cross-player-binding: true
    max-level: 5
    max-active-executions-per-player: 2
    max-active-executions-global: 64
    max-effects-per-tick: 64
    default-binding-mode: PINNED
    log-executions: true
    minimum-tps: 17.0
```

既定設定ではCore機能とCoreの実戦効果は無効です。検証環境で段階的に有効化してください。

### 27.3 推奨有効化手順

1. バックアップを作成します。
2. `enabled: true`、`allow-production-effects: false`で起動します。
3. Coreのbinding、compile、Fuel、MP、Cooldownだけを確認します。
4. WorldGuard、MythicMobs、PvP、Creative、Geyserを検証します。
5. 効果予算を低めに設定します。
6. `allow-production-effects: true`へ変更します。
7. TPS、MSPT、ArcVM task数、監査ログを監視します。

`/msf reload`ではHMACやDB等の全設定が再初期化されるわけではありません。重要な設定変更は安全な再起動で反映してください。

---

## 28. 現在の制限事項

alpha.12で未完成または簡略化されている主な項目です。

- 構文として存在する全イベントのPaper自動起動
- 通常ArcSysコードと任意の既存ノード回路の完全相互変換
- VS Code上の完全なグラフィカルノード編集
- Mod内コードエディターのシンタックス色分け、LSP補完、複数module編集
- `trait`、`impl`、汎用type aliasの完全な意味解析
- `atomic` / `transaction`による、すでに適用済みBukkit効果の完全rollback
- `spell.cast`、`spell.effect`、`virtual_structure.show`、`items.inspect`、`event.cancel`の実戦ホスト
- 粒子shape、Display transform、block query等の完全なカタログ準拠実装
- VS Codeの行ブレークポイントGUIとの完全連携
- Java/Bedrock、Bungee、WorldGuard、MythicMobsを含む全組合せの自動実機試験

「Parserが受理する」「APIカタログに表示される」「SimulationがEffect Requestを生成する」ことは、「本番Minecraft効果が完全実装済み」という意味ではありません。標準API表の状態欄を確認してください。

---

## 29. クイックリファレンス

### 29.1 最小テンプレート

```arc
spell MySpell {
  on cast {
    return true;
  }
}
```

### 29.2 安全な攻撃テンプレート

```arc
spell SafeAttack {
  on cast {
    let selected = target.nearest(radius: 16b);
    if (selected.is_some && combat.can_attack(selected.value)) {
      combat.damage(selected.value, 8)?;
    }
    return true;
  }
}
```

### 29.3 よく使う単位

```text
20t    = 20 tick
5s     = 100 tick
16b    = 16 block
100mp  = 100 MP
25%    = 0.25相当の確率
7d     = 7日
```

### 29.4 よく使うコマンド

```text
/msf open
/msf arc list
/msf arc cast <spell-uuid>
/msf arc stop
/msf arc core list
```

### 29.5 VS Codeの基本手順

```text
MinecraftでBridge開始
→ ArcSys: Connect to Minecraft
→ Validate
→ Compile / Simulation
→ Deploy
→ 必要ならBind Program to Core
```

### 29.6 困ったときの優先確認

```text
Paper/Fabric版が一致しているか
→ PaperのValidate結果
→ 必要欠片と権限
→ MP/Fuel/Effect予算
→ API実装状態
→ 距離・PvP・WorldGuard
→ Revision競合
```

---

## 付録: 正本となる実装

仕様がWikiと食い違う場合、次の順で現在動作を判断します。

1. Paperのサーバー権威型Compiler・Runtime Host
2. `ArcMagicApiCatalog`
3. ArcVMとVerifier
4. ProtocolとDB migration
5. Fabric / VS Code / Webの表示
6. 本Wikiとその他ドキュメント

バージョン更新後は、Paperとクライアントを揃えたうえで、必ずValidateと実機試験を行ってください。
