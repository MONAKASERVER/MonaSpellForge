# MonaSpellForge

Paper 26.1.2 + Fabric 26.1.2 向けの、サーバー権威型魔術回路システムです。2.0.0-alpha.7ではVS Codeに任意で有効化できる保存時Auto Deployを追加し、`TRUE_DAMAGE`がクリエイティブ／スペクテイターへ通る問題を修正しました。alpha.6のCore専用自動反映、alpha.5のファイル入出力、alpha.4のArcSys開発機能も含みます。既存72種類と73番目の `ARCSYS_CORE` は維持されます。

v1.7.0ではFabricエディター下部に攻撃力・DPS・連射力・消費魔力のリアルタイム戦闘指標を追加しました。新しい術式強化として、低負荷で防御を15～55%貫通する `ARMOR_PENETRATION` と、莫大な魔力・実行負荷を使って確定ダメージを与える `TRUE_DAMAGE` を追加しています。

## ビルド

Java 25が必要です。

```powershell
$env:JAVA_HOME='C:\path\to\jdk-25'
.\gradlew.bat clean build
```

成果物:

- `paper/build/libs/MonaSpellForge-Paper-1.6.1.jar`
- `fabric/build/libs/MonaSpellForge-Fabric-26.1.2-1.6.1.jar`

Paper JARをサーバーの`plugins`へ、Fabric JARを各Javaクライアントの`mods`へ配置します。ModがないJava版・統合版は54スロットのサーバーGUIを利用できます。

## Fabric Modのダウンロード

[GitHub Releases](https://github.com/MONAKASERVER/MonaSpellForge/releases)から、利用中のMinecraftバージョンに対応する`MonaSpellForge-Fabric-*.jar`をダウンロードし、クライアントの`mods`フォルダへ配置してください。

v1.6.1の動作対象はMinecraft 26.1.2、Fabric Loader 0.19.4以降、Java 25です。サーバー側には互換性のあるMonaSpellForge Paperプラグインが必要です。

Java版・統合版共通のWebエディターは、ゲーム内で `/msf web` を実行して発行される6桁コードで連携します。コードは10分間・1回限り有効で、保存処理はPaper側でも所有権、所持ノード、Revision、回路上限を再検証します。

## Fabric回路エディター操作

- 右側の「所持ノード」からキャンバスへ左ドラッグ: 欠片を配置
- 配置済みノードを左ドラッグ: 5×5の好きなマスへ移動
- 色付きポートを左ドラッグ: 型が一致するポートへ自由配線
- 配線を右クリック: その配線だけを切断
- ノードまたはキャンバスを右クリック: 追加・複製・しまうメニュー
- `.arc出力`: 回路をダウンロードフォルダーへ`.arc`ファイルとして保存（同名時は連番）
- `.arc読込`: エクスプローラーのファイル選択画面から`.arc`ファイルを読み込み
- `ARCSYS_CORE`を右クリックして「VS Codeで編集」: そのCore専用のペアリングを開始。VS Codeで開かれた`.arc`を保存すると、検証・デプロイ・`LATEST`紐付けを自動実行
- VS Codeの`ArcSys Auto Deploy`をON: 通常の`.arc`も保存時にMinecraftへ自動デプロイ（初期OFF・Coreへの自動紐付けは専用編集時のみ）
- マウスホイール: ズームイン・アウト
- 空白部分を左ドラッグ、または中クリックドラッグ: キャンバス全体を移動
- `Ctrl+Z` / `Ctrl+Y`: 元に戻す / やり直す
- 検索欄: 欠片名・原典ID・属性・機能・レアリティ・レベルで絞り込み
- カテゴリタブ: 全部・発動・属性・形状・対象・補助・論理をワンクリックで切り替え
- 同種・同レベルの欠片は1行へ集約し、所持数と配置数を表示
- ノード、欠片、ポートへホバー: 効果・消費魔力・実行負荷・接続エラーを表示
- 発動時のパーティクルは属性色・魔術形状・分裂・追尾・貫通・連鎖・範囲・持続・増幅ノードに応じて合成

画面内の検証は操作のたびに即時実行されます。保存時にはPaper側でも所有権、欠片署名、配線、実行負荷を再検証します。
回路1件あたりの実行負荷上限は512です。

## 最短の試し方

1. OPが `/msf fragment give <player> FIRE 1 1` の形式で欠片を発行します。術式演算核は `/msf fragment give <player> ARCSYS_CORE 1 1`（Lv.5ならレベルを5）です。
2. 必要なTRIGGER / ELEMENT / TARGET / SHAPE欠片を所持します。
3. プレイヤーが `/msf open` を実行し、Mod画面の「所持ノード」または中央5×5へ配置します。
4. 「検証」後、「保存して有効化」を押します。
5. 設定したトリガー、または `/msf spell cast` で発動します。

保存済み魔術は `/msf open` の一覧から有効・無効を切り替えられます。Fabric版は専用ボタン、Modなし版はShift＋左クリックで操作します。有効にできる魔術は同時に1件です。

ガチャ機能は含みません。外部プラグインは Bukkit Services API の `FragmentService` を使って欠片を正規発行してください。
