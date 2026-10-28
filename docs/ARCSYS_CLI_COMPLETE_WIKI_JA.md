# ArcSys CLI 完全Wiki

> 対象: MonaSpellForge / ArcSys CLI `2.0.0-alpha.9`
>
> 必要環境: Java 25
>
> 最終更新: 2026-10-29

ArcSys CLIは、ArcSysソースをMinecraftやPaperサーバーへ接続せずに検査・コンパイル・逆アセンブル表示するためのオフラインコマンドラインツールです。

CLIが行うのは、`.arc`ソースに対する字句解析、構文解析、型検査、Capability検査、ArcVMバイトコード生成です。Minecraft効果の実行、PaperへのDeploy、プレイヤーの欠片所有確認、WorldGuard判定などは行いません。

ArcSys言語そのものについては、[ArcSys 2.0 完全Wiki](ARCSYS_COMPLETE_WIKI_JA.md)も参照してください。

---

## 目次

1. [CLIでできること](#1-cliでできること)
2. [必要環境](#2-必要環境)
3. [入手とインストール](#3-入手とインストール)
4. [基本構文](#4-基本構文)
5. [`check`コマンド](#5-checkコマンド)
6. [`compile`コマンド](#6-compileコマンド)
7. [`disasm`コマンド](#7-disasmコマンド)
8. [出力の読み方](#8-出力の読み方)
9. [診断メッセージ](#9-診断メッセージ)
10. [終了コード](#10-終了コード)
11. [ArcVMバイトコード](#11-arcvmバイトコード)
12. [逆アセンブルの読み方](#12-逆アセンブルの読み方)
13. [CLIが検証する項目](#13-cliが検証する項目)
14. [CLIが検証しない項目](#14-cliが検証しない項目)
15. [単一ファイルとmoduleの制限](#15-単一ファイルとmoduleの制限)
16. [CI・自動化](#16-ci自動化)
17. [セキュリティ](#17-セキュリティ)
18. [トラブルシューティング](#18-トラブルシューティング)
19. [よくある質問](#19-よくある質問)
20. [現在の制限事項](#20-現在の制限事項)
21. [クイックリファレンス](#21-クイックリファレンス)

---

## 1. CLIでできること

### 1.1 対応機能

| 機能 | コマンド | 状態 |
|---|---|---:|
| ArcSysソースの検査 | `check` | 対応 |
| ArcVMバイトコード生成 | `compile` | 対応 |
| 生成予定の命令列表示 | `disasm` | 対応 |
| 構文・型診断 | 全コマンド | 対応 |
| 推定Fuel・命令数表示 | 全コマンド | 対応 |
| 決定的SHA-256表示 | 全コマンド | 対応 |

### 1.2 対応していない機能

| 機能 | CLIの現状 |
|---|---|
| ArcVM実行 | 非対応 |
| Simulation | 非対応 |
| Profiler | 非対応 |
| Debugger | 非対応 |
| PaperへのDeploy | 非対応 |
| ARCSYS_COREへのBind | 非対応 |
| `.arcb`の読み込み | 非対応 |
| 複数module projectの自動収集 | 非対応 |
| REPL | 非対応 |
| Watch mode | 非対応 |
| JSON / SARIF出力 | 非対応 |
| 標準入力からの読込 | 非対応 |

CLIは安全な静的ツールです。サーバーにログインしていなくても利用できますが、CLIだけでゲーム内術式を更新することはできません。

---

## 2. 必要環境

### 2.1 Java

ArcSys CLI 2.0.0-alpha.9はJava 25向けにビルドされています。

確認コマンド:

```text
java --version
```

Java 25以外で起動すると、クラスファイルのバージョン不一致などで起動できない場合があります。

### 2.2 Minecraftは不要

CLI単体の検査には以下は不要です。

- Minecraftクライアント
- Fabric Loader
- Paperサーバー
- MonaSpellForge Paperプラグイン
- VS Code拡張
- プレイヤーアカウント

### 2.3 バージョンを揃える

CLIは同じリリースに含まれる`arcsys-language`と`arcsys-compiler`を同梱しています。

サーバーへ後からDeployする予定のソースは、Paperと同じMonaSpellForgeバージョンのCLIで確認してください。異なるalpha版では、構文、APIカタログ、型検査、バイトコード形式が変わる可能性があります。

---

## 3. 入手とインストール

### 3.1 GitHub Releasesから入手

[MonaSpellForge Releases](https://github.com/MONAKASERVER/MonaSpellForge/releases)から、使用中のバージョンに合う次のファイルを取得します。

```text
arcsys-cli-2.0.0-alpha.9.zip
```

LinuxやmacOS向けには`.tar`版も利用できます。

### 3.2 ZIPの構造

展開後の主な構造:

```text
arcsys-cli-2.0.0-alpha.9/
├─ bin/
│  ├─ arcsys-cli
│  └─ arcsys-cli.bat
└─ lib/
   ├─ arcsys-cli-2.0.0-alpha.9.jar
   ├─ arcsys-compiler-2.0.0-alpha.9.jar
   └─ arcsys-language-2.0.0-alpha.9.jar
```

`lib`内のJARを個別に移動しないでください。起動スクリプトは相対位置にある複数のJARをクラスパスへ追加します。

### 3.3 Windows

PowerShellまたはコマンドプロンプトで、展開先へ移動して実行します。

```powershell
.\bin\arcsys-cli.bat check .\magic\flame.arc
```

パスに空白がある場合:

```powershell
& ".\bin\arcsys-cli.bat" check ".\My Spells\flame.arc"
```

### 3.4 Linux・macOS

```bash
./bin/arcsys-cli check ./magic/flame.arc
```

実行権限が失われている場合:

```bash
chmod +x ./bin/arcsys-cli
```

### 3.5 PATHへ追加する場合

PATHへ追加する対象は`bin`ディレクトリです。実際の配布ランチャー名は`arcsys-cli`です。

CLI内部のUsage表示は短縮名として`arcsys`と表示しますが、配布物のファイル名は次のとおりです。

```text
Windows: arcsys-cli.bat
Unix:   arcsys-cli
```

`arcsys`という別名を使いたい場合は、シェル側でaliasやラッパーを設定してください。

### 3.6 ソースからビルド

リポジトリを取得済みの場合:

```powershell
.\gradlew.bat :arcsys-cli:installDist
```

配布ZIPを作る場合:

```powershell
.\gradlew.bat :arcsys-cli:distZip
```

Linux・macOSでは`gradlew.bat`の代わりに`./gradlew`を使用します。

---

## 4. 基本構文

```text
arcsys-cli <check|compile|disasm> <file.arc>
```

引数は必ず2個です。

1. コマンド名
2. 対象ソースファイルのパス

例:

```powershell
.\bin\arcsys-cli.bat check .\spell.arc
.\bin\arcsys-cli.bat compile .\spell.arc
.\bin\arcsys-cli.bat disasm .\spell.arc
```

現在は次の形式に対応していません。

```text
arcsys-cli --help
arcsys-cli --version
arcsys-cli check --json spell.arc
arcsys-cli check folder/
arcsys-cli check a.arc b.arc
arcsys-cli compile -o output.arcb spell.arc
```

引数が不足している、余分である、またはコマンド名が不明な場合はUsageを標準エラーへ表示して終了します。

```text
Usage: arcsys <check|compile|disasm> <file.arc>
```

---

## 5. `check`コマンド

### 5.1 目的

`check`は`.arc`ファイルを最後までコンパイル可能か検査します。バイトコードはメモリ上で生成されますが、ファイルには保存されません。

```powershell
.\bin\arcsys-cli.bat check .\core_conditional.arc
```

### 5.2 成功時

```text
OK CoreConditional fuel=74 instructions=36 sha256=8fc2dbb96e7a7392de63317f02f4dff9b6ca099a3273c5daab0ea7724952de90
```

終了コードは`0`です。

### 5.3 失敗時

```text
ERROR ARC-T5023:3:18 未定義の変数です: missing_name
ERROR ARC-T5027:4:5 combat.damage の引数個数が不正です
```

コンパイルできない場合、`OK`行は出ません。終了コードは`1`です。

### 5.4 生成物

`check`はファイルを作成しません。CIや保存前検査に適しています。

---

## 6. `compile`コマンド

### 6.1 目的

`compile`はソースを検査し、成功した場合にArcVMバイトコードを保存します。

```powershell
.\bin\arcsys-cli.bat compile .\core_conditional.arc
```

出力例:

```text
OK CoreConditional fuel=74 instructions=36 sha256=8fc2dbb96e7a7392de63317f02f4dff9b6ca099a3273c5daab0ea7724952de90
Wrote core_conditional.arcb
```

### 6.2 出力先

出力先は入力ファイルと同じディレクトリです。出力名は入力ファイル名の末尾へ`b`を追加して決まります。

| 入力 | 出力 |
|---|---|
| `spell.arc` | `spell.arcb` |
| `boss_logic.arc` | `boss_logic.arcb` |
| `test.txt` | `test.txtb` |

CLIは入力拡張子が`.arc`かどうかを強制していません。意図した`.arcb`名にするため、`.arc`を使用してください。

### 6.3 上書き

同名の出力ファイルが存在する場合、確認なしで内容を置き換えます。必要な`.arcb`は事前にバックアップしてください。

### 6.4 コンパイル失敗時

診断を表示して終了コード`1`を返します。`.arcb`は新規作成されません。

ただし、以前のコンパイルで作成された同名`.arcb`は自動削除されません。CIでは失敗後に古い生成物を誤配布しないよう注意してください。

### 6.5 `.arcb`はDeployファイルではない

生成した`.arcb`をPaperへ直接アップロードして術式を登録する機能はありません。

Paperはクライアント側のコンパイル結果を信用せず、`.arc`ソースをサーバー側で再コンパイルします。ゲームへ反映する場合はFabric Bridge、VS Code拡張、または対応するサーバー側のソース取込経路を使用してください。

---

## 7. `disasm`コマンド

### 7.1 目的

`disasm`は`.arc`ソースをその場でコンパイルし、生成された命令をPC順に表示します。

```powershell
.\bin\arcsys-cli.bat disasm .\core_conditional.arc
```

出力例:

```text
OK CoreConditional fuel=74 instructions=36 sha256=8fc2dbb96e7a7392de63317f02f4dff9b6ca099a3273c5daab0ea7724952de90
0000 Instruction[op=PUSH_DOUBLE, text=BLOCKS, number=0, decimal=24.0]
0001 Instruction[op=CALL_HOST, text=target.nearest, number=1, decimal=0.0]
0002 Instruction[op=STORE, text=selected, number=0, decimal=0.0]
0003 Instruction[op=LOAD, text=selected, number=0, decimal=0.0]
0004 Instruction[op=GET_MEMBER, text=is_some, number=0, decimal=0.0]
0005 Instruction[op=JUMP_IF_FALSE, text=, number=29, decimal=0.0]
```

### 7.2 重要な注意

`disasm`は既存`.arcb`を開くコマンドではありません。

```text
入力:  .arcソース
処理:  再コンパイル
出力:  命令列を標準出力へ表示
保存:  なし
```

`.arcb`を引数へ渡すと、バイナリをArcSysソースとして読もうとして失敗します。

### 7.3 NUL区切りの表示

`CALL_HOST`などの内部`text`には、API名と名前付き引数情報を分けるNUL文字が含まれる場合があります。端末によっては空白、四角、見えない文字として表示されます。

これは命令破損ではなく、現在の`Instruction.toString()`をそのまま出力しているためです。

---

## 8. 出力の読み方

### 8.1 成功行

```text
OK <spellName> fuel=<estimatedFuel> instructions=<count> sha256=<hash>
```

| 項目 | 意味 |
|---|---|
| `OK` | コンパイル成功 |
| `spellName` | `spell`宣言の名前 |
| `fuel` | Compilerが算出した推定Fuel |
| `instructions` | 生成命令数 |
| `sha256` | エンコード済みバイトコード全体のSHA-256 |

### 8.2 Fuel

CLIが表示する`fuel`は静的な推定値です。

実際の実行では次の要素が別途関係します。

- ARCSYS_COREのLevel別VM燃料上限
- 実行した分岐
- 反復回数
- Host API呼び出し
- 継続・並列処理
- Paper側のtick予算

`fuel=74`だから必ず74 Fuelで実行完了する、という意味ではありません。

### 8.3 命令数

`instructions`は静的に生成された命令数です。Compilerの上限は4,096命令です。

ループが複数回実行されても、静的命令数自体は増えません。実行負荷はFuelやランタイム予算でも制限されます。

### 8.4 SHA-256

SHA-256は次の情報を含むエンコード済みプログラムから計算されます。

- 術式名
- Entry point
- Capability
- 必要欠片・必要権限
- 推定Fuel
- 命令列
- Source map

同じバージョン、同じソース、同じCompiler入力なら同じハッシュになります。

空白やコメントの変更でもSource mapの位置が変わり、ハッシュが変わる場合があります。意味が同じコードなら必ず同じハッシュになる、という仕様ではありません。

### 8.5 標準出力と標準エラー

| 出力 | ストリーム |
|---|---|
| `OK ...` | 標準出力 |
| `Wrote ...` | 標準出力 |
| 逆アセンブル命令 | 標準出力 |
| `ERROR ...` | 標準エラー |
| `WARNING ...` | 標準エラー |
| Usage | 標準エラー |

CIでログを別々に収集する場合は、この違いに注意してください。

---

## 9. 診断メッセージ

### 9.1 書式

```text
<severity> <code>:<line>:<column> <message>
```

例:

```text
ERROR ARC-T5023:3:18 未定義の変数です: missing_name
```

| 部分 | 意味 |
|---|---|
| `ERROR` | 診断の重大度 |
| `ARC-T5023` | 安定した診断コード |
| `3` | 1始まりの行番号 |
| `18` | 1始まりの列番号 |
| 末尾 | 日本語の説明 |

### 9.2 Severity

| Severity | 結果 |
|---|---|
| `ERROR` | コンパイル失敗、終了コード1 |
| `WARNING` | 診断を表示するが、ERRORがなければ成功可能 |

### 9.3 診断prefix

主な診断系列:

| Prefix | 分野 |
|---|---|
| `ARC-L...` | Lexer |
| `ARC-P...` | Parser |
| `ARC-E...` | Compiler |
| `ARC-T...` | 型検査 |
| `ARC-M...` | module / project linker |
| `ARC-V...` | ArcVM Verifier |
| `ARC-R...` | Runtime |
| `ARC-H...` | Host API |

現在のCLIは単一ファイルのLexer、Parser、型検査、Compiler診断を表示します。`ArcVerifier`やRuntimeを起動しないため、`ARC-V`、`ARC-R`、`ARC-H`は通常CLIからは出ません。

### 9.4 修正候補について

内部の診断データには`suggestion`もありますが、現在のCLI表示はsuggestionを出力しません。詳しい候補が必要な場合はVS Code拡張の診断や、ArcSys完全Wikiを参照してください。

### 9.5 複数エラー

可能な範囲で複数の診断を一度に表示します。ただしLexerやParser段階で構造を復元できない場合、後続の型エラーまでは表示されません。

---

## 10. 終了コード

### 10.1 正式な終了コード

| Code | 意味 |
|---:|---|
| `0` | 検査・コンパイル成功 |
| `1` | ArcSys診断にERRORがありコンパイル失敗 |
| `2` | 引数個数またはコマンド名が不正 |

### 10.2 ファイルI/O・JVMエラー

対象ファイルが存在しない、読取権限がない、出力先へ書けないなどの例外は、現在のCLIでは人向けエラーへ変換されずJavaのstack traceになる場合があります。

これらは非0で終了しますが、例外時の細かな終了コードをCI契約として固定しないでください。

### 10.3 PowerShellで判定

```powershell
& ".\bin\arcsys-cli.bat" check ".\spell.arc"
if ($LASTEXITCODE -ne 0) {
    Write-Error "ArcSys validation failed: $LASTEXITCODE"
    exit $LASTEXITCODE
}
```

### 10.4 Bashで判定

```bash
if ! ./bin/arcsys-cli check ./spell.arc; then
  echo "ArcSys validation failed" >&2
  exit 1
fi
```

---

## 11. ArcVMバイトコード

### 11.1 `.arcb`とは

`.arcb`はArcVM用の独自バイナリです。

- JVMの`.class`ではありません。
- Javaコードを含みません。
- OSコマンドではありません。
- Minecraft resource packではありません。
- ノード回路キャリア形式の`.arc`とは別物です。

### 11.2 現行フォーマット

alpha.9のバイトコード形式:

```text
Magic:          ARC5 / 0x41524335
Format version: 5
```

高レベルの格納順:

```text
magic
format version
spell name
entry points
capabilities
requirements
estimated fuel
instructions
source map
```

### 11.3 命令データ

各命令は概念上、次の4フィールドを持ちます。

```text
op       命令種別
text     文字列operand
number   64-bit整数operand
decimal  浮動小数operand
```

命令によって使用しないフィールドは空文字または0です。

### 11.4 主なOpcode

| 分類 | Opcode例 |
|---|---|
| 定数 | `PUSH_NONE`, `PUSH_BOOL`, `PUSH_LONG`, `PUSH_DOUBLE`, `PUSH_STRING` |
| 変数 | `LOAD`, `STORE` |
| Stack | `POP`, `DUP` |
| 算術 | `ADD`, `SUB`, `MUL`, `DIV`, `MOD`, `NEGATE` |
| 比較 | `EQ`, `NE`, `LT`, `LE`, `GT`, `GE` |
| 論理 | `NOT`, `AND`, `OR` |
| 値 | `GET_MEMBER`, `MAKE_LIST`, `MAKE_STRUCT`, `MAKE_VARIANT` |
| Option/Result | `IS_VARIANT`, `UNWRAP_VARIANT`, `COERCE_RESULT` |
| 呼出 | `CALL`, `CALL_HOST` |
| 継続 | `SUSPEND`, `PARALLEL` |
| 分岐 | `JUMP`, `JUMP_IF_FALSE` |
| Iterator | `ITER_INIT`, `ITER_HAS_NEXT`, `ITER_NEXT` |
| 終了 | `RETURN`, `HALT` |

### 11.5 互換性

バイトコードformat versionが変わる可能性があるため、`.arcb`を長期保存の正本にしないでください。

正本は`.arc`ソースです。MonaSpellForgeを更新したら、新しい同版CLIまたはPaper Compilerで再コンパイルしてください。

---

## 12. 逆アセンブルの読み方

### 12.1 PC

行頭の4桁はProgram Counterです。

```text
0005 Instruction[op=JUMP_IF_FALSE, text=, number=29, decimal=0.0]
```

この例ではPC 5の命令が、条件falseならPC 29へ移動します。

### 12.2 `PUSH_*`

```text
PUSH_LONG   numberへ整数を保持
PUSH_DOUBLE decimalへ小数を保持
PUSH_STRING textへ文字列を保持
```

単位付き値では`text`に単位が入ることがあります。

```text
Instruction[op=PUSH_DOUBLE, text=BLOCKS, number=0, decimal=24.0]
```

### 12.3 `CALL_HOST`

許可リストにある魔術APIをPaper Hostへ要求する命令です。

```text
Instruction[op=CALL_HOST, text=target.nearest, number=1, decimal=0.0]
```

`number`は引数個数です。実際のゲーム実行ではPaperが権限、対象、距離、保護、予算を再検査します。

### 12.4 `CALL`

ユーザー定義関数を呼び出します。任意のJavaメソッド呼び出しではありません。

### 12.5 `SUSPEND`

`wait`、`after`などで継続実行を予約するための命令です。CLIは表示するだけで、Schedulerを実行しません。

### 12.6 `PARALLEL`

`parallel all`、`parallel any`、`race`などの分岐群をArcVM Schedulerへ渡します。CLIは並列結果をSimulationしません。

### 12.7 Source map

コンパイル済みProgramにはPCからmodule・行・列へ戻すSource mapがあります。ただし現在の`disasm`表示にはSource mapが併記されません。

---

## 13. CLIが検証する項目

### 13.1 Lexer

- 不正文字
- 閉じていない文字列
- 数値・単位token
- keywordとidentifier

### 13.2 Parser

- `spell`構造
- `on` handler
- 関数、状態、契約
- 式と文
- `if`、`match`、`for`、`repeat`
- `wait`、`parallel`、`race`
- `struct`、`enum`、module宣言

### 13.3 型検査

- 未定義変数
- 再代入可否
- 引数個数
- 引数型
- 戻り値型
- Option / Result
- struct field
- enum variant
- 単位型
- Effect APIの使用位置

### 13.4 Compiler

- 1ファイル1術式
- 重複handler
- 重複関数
- 不正な`break` / `continue`
- `defer`内の禁止制御
- 閉じたHost API許可リスト
- Capability収集
- 必要欠片・権限metadata
- 最大4,096命令
- 決定的バイトコード生成

### 13.5 APIシグネチャ

標準魔術APIの名前、引数数、型、Capability、基礎Fuelは、Paper Compilerと共通の`ArcMagicApiCatalog`を使います。

CLIとPaperのバージョンが同じなら、API名のスペルや静的な型について同じ判定になります。

---

## 14. CLIが検証しない項目

### 14.1 プレイヤー固有情報

CLIにはプレイヤーセッションがないため、次は確認しません。

- 必要欠片を所有しているか
- 必要Permissionを持っているか
- ARCSYS_COREを所有しているか
- Core LevelのFuel上限へ収まるか
- 現在MPが足りるか
- Cooldown中か

### 14.2 Minecraft状態

- 対象Mobが存在するか
- 距離内か
- PvP可能か
- WorldGuard保護を通るか
- MythicMobsへの効果が許可されるか
- チャンクがロード済みか
- TPSが安全か

### 14.3 Paper Runtime実装

APIカタログへ登録され、静的にコンパイルできることと、Paper実戦Hostで完全動作することは別です。

CLIは`CAPABILITY_NOT_AVAILABLE`や、Paper側で部分実装となっているAPIを事前検出しません。実装状況はArcSys完全Wikiの「標準魔術API完全一覧」で確認してください。

### 14.4 ArcVerifier

現在のCLIは`ArcCompiler`へ直接接続されており、`ArcVerifier`を別工程として実行しません。

PaperへDeployする場合は、Paperがサーバー権威で再コンパイルし、必要な検証を行います。

### 14.5 実行結果

- ダメージ量
- 対象選択結果
- 永続状態の値
- 分岐結果
- 実行時間
- 実Fuel消費
- Effect数
- Stack最大深度

これらはCLIの`check`だけでは分かりません。

---

## 15. 単一ファイルとmoduleの制限

### 15.1 単一ファイルCompiler

CLIは現在、`ArcCompiler.compile(String)`を使用します。`ArcProjectCompiler`は使用しません。

そのため、CLIへ渡せるのは自己完結した単一ソースです。

```arc
spell Standalone {
  on cast {
    return true;
  }
}
```

### 15.2 1ファイル1術式

1つのファイルへ複数`spell`を定義すると`ARC-E2001`で失敗します。

```arc
spell A { on cast { return true; } }
spell B { on cast { return true; } }
```

各術式を別ファイルへ分割してください。

### 15.3 moduleと`use`

Parserはmodule構文を認識しますが、CLIは複数ファイルを探索・連結しません。

次のようなprojectは、CLI単体のファイル指定では完全解決できません。

```arc
module main;
use combat_utils;
```

複数module projectの検証・DeployにはVS Code拡張のproject収集とPaper側`ArcProjectCompiler`を使用してください。

### 15.4 Bundle

Paper DBへ保存されるproject bundleや、VS Code Bridgeのmodule mapをCLIへ直接渡すオプションはありません。

---

## 16. CI・自動化

### 16.1 PowerShellで複数ファイルを検査

CLI自体は複数ファイル指定に対応しないため、シェルで1件ずつ呼び出します。

```powershell
Get-ChildItem .\spells -Filter *.arc -File -Recurse | ForEach-Object {
    & ".\arcsys-cli\bin\arcsys-cli.bat" check $_.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "ArcSys check failed: $($_.Name)"
    }
}
```

### 16.2 Bashで複数ファイルを検査

```bash
set -euo pipefail
find ./spells -type f -name '*.arc' -print0 |
  while IFS= read -r -d '' file; do
    ./arcsys-cli/bin/arcsys-cli check "$file"
  done
```

### 16.3 GitHub Actions例

```yaml
name: ArcSys Check

on:
  push:
    paths:
      - "spells/**/*.arc"
  pull_request:
    paths:
      - "spells/**/*.arc"

jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "25"
      - name: Validate ArcSys sources
        shell: bash
        run: |
          set -euo pipefail
          find spells -type f -name '*.arc' -print0 |
            while IFS= read -r -d '' file; do
              ./tools/arcsys-cli/bin/arcsys-cli check "$file"
            done
```

この例では、あらかじめCLI配布物を`tools/arcsys-cli`へ展開している前提です。

### 16.4 リポジトリ内Gradleで実行

MonaSpellForgeソースツリー内ではApplication plugin経由でも実行できます。

```powershell
.\gradlew.bat :arcsys-cli:run --args="check examples/core_conditional.arc"
```

### 16.5 古い`.arcb`対策

`compile`失敗時に以前の`.arcb`が残るため、配布処理では次のいずれかを採用してください。

- cleanな一時ディレクトリでコンパイル
- 成功した実行だけ成果物へコピー
- コンパイル前に対象生成物を明示的に整理
- SHA-256とCLIバージョンをmanifestへ記録

---

## 17. セキュリティ

### 17.1 オフライン動作

CLIは次の操作を行いません。

- Paperへの接続
- WebSocket接続
- HTTP通信
- Minecraft Custom Payload送信
- SQLアクセス
- Bukkit API呼び出し
- OSコマンド実行
- Java Reflection
- 任意Javaコード実行

### 17.2 読み書きする範囲

`check`と`disasm`:

- 指定されたソースファイルを読み取る
- ファイル出力なし

`compile`:

- 指定されたソースファイルを読み取る
- 同じディレクトリへ末尾`b`の出力ファイルを書き込む
- 同名出力を上書きする

### 17.3 `.arc`の信頼

CLIは`.arc`をデータとして解析します。`.arc`内からJava、Python、PowerShell、シェルを起動する構文はありません。

ただし、極端に大きいファイルのサイズ上限をCLI自身は設定していません。信頼できない巨大ファイルを自動処理する場合は、CI側でファイルサイズや処理時間を制限してください。

### 17.4 `.arcb`の信頼

CLIが生成した`.arcb`であっても、Paperはクライアント成果物をそのまま信頼しません。サーバー権威型の境界を迂回する用途には使用できません。

---

## 18. トラブルシューティング

### 18.1 Usageだけ表示される

原因:

- 引数が2個ではない
- コマンド名のスペルが違う
- `--help`や`--version`を指定した

正しい形式:

```text
arcsys-cli check spell.arc
```

### 18.2 `java`が見つからない

Java 25を導入し、`JAVA_HOME`とPATHを確認してください。

```text
java --version
```

起動スクリプトが別のJavaを選んでいる場合は、CLIを実行する端末でJava 25の`JAVA_HOME`を設定します。

### 18.3 `UnsupportedClassVersionError`

古いJavaで起動しています。Java 25へ切り替えてください。

### 18.4 `NoSuchFileException`

指定したソースパスが存在しません。

- 現在ディレクトリを確認
- 相対パスの基準を確認
- 空白を含むパスを引用符で囲む
- ファイル名の全角・半角を確認

### 18.5 `AccessDeniedException`

入力ファイルの読取権限、または`compile`出力先の書込権限がありません。

ソースをユーザーが読み書きできる作業ディレクトリへ移してください。

### 18.6 日本語が文字化けする

CLIのソース読込はUTF-8です。ソースをUTF-8で保存し、UTF-8対応端末を使用してください。

WindowsではWindows Terminalまたは新しいPowerShellを推奨します。ログ収集ツールがOEM code pageとして誤解釈している場合は、収集側のencodingもUTF-8へ合わせてください。

### 18.7 `check`は通るがサーバーで拒否される

CLIでは確認できない次の条件を調べます。

- PaperとCLIのバージョン一致
- 必要欠片
- LuckPerms権限
- Capabilityの本番有効化
- ARCSYS_CORE Level
- MP、Cooldown、Fuel、Effect上限
- WorldGuard、PvP、対象距離
- Revision競合

### 18.8 `.arcb`をゲームへ読み込めない

正常です。`.arcb`はCLI内部向けのArcVMバイトコードで、Fabricの`.arc読込`対象ではありません。

ゲームへ取り込むのは通常の`.arc`ソース、またはMonaSpellForgeのノード回路キャリア形式です。

### 18.9 `disasm`へ`.arcb`を渡すと失敗する

現在の`disasm`は`.arc`を再コンパイルするコマンドです。バイナリDecoderではありません。

```text
正: disasm spell.arc
誤: disasm spell.arcb
```

### 18.10 moduleが見つからない

CLIは単一ファイルCompilerです。複数moduleはVS Code BridgeまたはPaperのproject経路で検証してください。

### 18.11 Hashが前回と違う

次を確認します。

- CLIバージョン
- ソースの空白・改行・コメント
- module構成
- 文字コード
- Source map位置

同じ意味のソースでも、ソース位置が変わるとハッシュが変わる場合があります。

### 18.12 FuelがCore上限を超える

CLIはLevelごとのCore上限を自動選択しません。WikiのARCSYS_CORE性能表と照合し、術式を小さくするか適切なLevelを使用してください。

---

## 19. よくある質問

### Q1. CLIだけでサーバーへ魔術を送れますか？

できません。DeployにはFabric Mod経由のVS Code Bridgeなど、認証済みのサーバー経路が必要です。

### Q2. CLIはModですか？

違います。Minecraftとは独立したJavaコマンドラインアプリケーションです。

### Q3. `.arcb`をPaperのpluginsへ置けば動きますか？

動きません。Paper plugin JARでも、術式Deploy形式でもありません。

### Q4. CLIで魔術を発動できますか？

できません。ArcVM RuntimeとMinecraft Hostを起動しません。

### Q5. CLIでAPI一覧を表示できますか？

現在は専用コマンドがありません。ArcSys完全WikiまたはVS Code補完を使用してください。

### Q6. 警告をエラー扱いにできますか？

CLIオプションとしては未対応です。CIで標準エラーを解析するか、将来の`--warnings-as-errors`対応が必要です。

### Q7. 出力ファイル名を変えられますか？

`-o`オプションはありません。生成後に別名へコピーしてください。

### Q8. フォルダ全体を一度に検査できますか？

CLI単体ではできません。PowerShell、Bash、CIのループで1件ずつ呼び出します。

### Q9. 標準入力からソースを渡せますか？

できません。実在するファイルパスが必要です。

### Q10. `.arcb`から元の`.arc`へ戻せますか？

できません。Source mapは位置情報であり、完全なソース復元情報ではありません。必ず`.arc`を保管してください。

### Q11. `check`と`compile`の検査精度は違いますか？

同じCompilerを使用します。違いは、成功後に`.arcb`を書き込むかどうかです。

### Q12. `disasm`のために先に`compile`が必要ですか？

不要です。`disasm`自身がソースをコンパイルして命令を表示します。

### Q13. Paperが同じ`.arc`を再コンパイルするのはなぜですか？

クライアントが偽造したバイトコードや、古いAPI・制限で作られた成果物を実行しないためです。Paperが唯一の権威です。

### Q14. CLIのSHA-256を認証署名として使えますか？

使えません。これは内容識別用のハッシュで、秘密鍵による署名ではありません。

---

## 20. 現在の制限事項

alpha.9 CLIの主な制限です。

1. コマンドは`check`、`compile`、`disasm`のみ。
2. 単一ファイルCompilerで、複数module projectを解決しない。
3. `.arcb`を読み戻すDecoderがない。
4. `disasm`にSource map、Entry point、Capability一覧を表示しない。
5. JSON、SARIF、JUnit XML出力がない。
6. `--help`、`--version`、`-o`、`--quiet`、`--color`がない。
7. ディレクトリ再帰、複数ファイル指定、標準入力、Watch modeがない。
8. 診断suggestionを表示しない。
9. ファイルI/O例外がstack traceになる場合がある。
10. Runtime、Simulation、Profiler、Debuggerを搭載しない。
11. ArcVerifierを独立工程として起動しない。
12. Paperの実戦Host API対応状況を検査しない。
13. プレイヤーの欠片、権限、MP、Core Levelを検査しない。
14. 既存出力を確認なしで上書きする。
15. コンパイル失敗時に古い`.arcb`を削除しない。

これらを必要とする場合はVS Code拡張、Fabric Mod、Paperサーバーの機能を併用してください。

---

## 21. クイックリファレンス

### 21.1 インストール確認

```powershell
java --version
.\bin\arcsys-cli.bat
```

2つ目はUsageと終了コード2になるのが現行仕様です。専用`--version`はありません。

### 21.2 検査

```powershell
.\bin\arcsys-cli.bat check .\spell.arc
```

### 21.3 コンパイル

```powershell
.\bin\arcsys-cli.bat compile .\spell.arc
```

生成物:

```text
spell.arcb
```

### 21.4 命令列表示

```powershell
.\bin\arcsys-cli.bat disasm .\spell.arc
```

### 21.5 終了コード

```text
0 = 成功
1 = ArcSysコンパイルエラー
2 = コマンド形式エラー
```

### 21.6 推奨開発フロー

```text
.arcをVS Codeで編集
→ ArcSys CLI check
→ Gitへcommit
→ Fabric ModでMinecraftへ接続
→ VS Code BridgeでValidate
→ PaperへDeploy
→ 必要ならARCSYS_COREへBind
→ ゲーム内で実機試験
```

### 21.7 正本

```text
編集・保管の正本: .arcソース
ローカル生成物:   .arcb
実行判断の正本:   Paperサーバー
```

---

## 付録A: 最小サンプル

```arc
spell CliHello {
  on cast {
    return true;
  }
}
```

検査:

```powershell
.\bin\arcsys-cli.bat check .\cli_hello.arc
```

## 付録B: 実戦APIを含むサンプル

```arc
spell CliDamageCheck {
  on cast {
    let selected = target.nearest(12b);
    if (selected.is_some) {
      let enemy = selected.value;
      combat.damage(enemy, 8);
      return true;
    }
    return false;
  }
}
```

CLIは構文・型・APIシグネチャを検査しますが、実際にその対象へダメージを与えられるかはPaperが判断します。

## 付録C: 実装上の正本

CLIの現在動作を確認する優先順位:

1. `ArcSysCli`
2. `ArcCompiler`
3. `ArcParser`と`ArcTypeChecker`
4. `ArcBytecode`
5. 本Wiki

バージョン更新時は、CLIとPaperのCompilerが同じリリースであることを確認してください。
