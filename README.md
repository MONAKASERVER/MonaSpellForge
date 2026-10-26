# MonaSpellForge

Paper 26.1.2 + Fabric 26.1.2 向けの、サーバー権威型魔術回路システムです。69種類の欠片、回路検証、SQLite永続化、HMAC付き欠片、発動、強化、バニラGUI、Fabric専用表示を1プロジェクトで管理します。

## ビルド

Java 25が必要です。

```powershell
$env:JAVA_HOME='C:\path\to\jdk-25'
.\gradlew.bat clean build
```

成果物:

- `paper/build/libs/MonaSpellForge-Paper-1.3.1.jar`
- `fabric/build/libs/MonaSpellForge-Fabric-26.1.2-1.3.1.jar`

Paper JARをサーバーの`plugins`へ、Fabric JARを各Javaクライアントの`mods`へ配置します。ModがないJava版・統合版は54スロットのサーバーGUIを利用できます。

## Fabric Modのダウンロード

[GitHub Releases](https://github.com/MONAKASERVER/MonaSpellForge/releases)から、利用中のMinecraftバージョンに対応する`MonaSpellForge-Fabric-*.jar`をダウンロードし、クライアントの`mods`フォルダへ配置してください。

v1.3.1の動作対象はMinecraft 26.1.2、Fabric Loader 0.19.4以降、Java 25です。サーバー側には互換性のあるMonaSpellForge Paperプラグインが必要です。

## Fabric回路エディター操作

- 右側の「所持ノード」からキャンバスへ左ドラッグ: 欠片を配置
- 配置済みノードを左ドラッグ: 5×5の好きなマスへ移動
- 色付きポートを左ドラッグ: 型が一致するポートへ自由配線
- ノードまたはキャンバスを右クリック: 追加・複製・削除メニュー
- マウスホイール: ズームイン・アウト
- 空白部分を左ドラッグ、または中クリックドラッグ: キャンバス全体を移動
- `Ctrl+Z` / `Ctrl+Y`: 元に戻す / やり直す
- 検索欄: 欠片名・原典ID・属性・機能・レアリティ・レベルで絞り込み
- カテゴリタブ: 全部・発動・属性・形状・対象・補助・論理をワンクリックで切り替え
- 同種・同レベルの欠片は1行へ集約し、所持数と配置数を表示
- ノード、欠片、ポートへホバー: 効果・消費魔力・実行負荷・接続エラーを表示

画面内の検証は操作のたびに即時実行されます。保存時にはPaper側でも所有権、欠片署名、配線、実行負荷を再検証します。

## 最短の試し方

1. OPが `/msf fragment give <player> FIRE 1 1` の形式で欠片を発行します。
2. 必要なTRIGGER / ELEMENT / TARGET / SHAPE欠片を所持します。
3. プレイヤーが `/msf open` を実行し、Mod画面の「所持ノード」または中央5×5へ配置します。
4. 「検証」後、「保存して有効化」を押します。
5. 設定したトリガー、または `/msf spell cast` で発動します。

ガチャ機能は含みません。外部プラグインは Bukkit Services API の `FragmentService` を使って欠片を正規発行してください。
