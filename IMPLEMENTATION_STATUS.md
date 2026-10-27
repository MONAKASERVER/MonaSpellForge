# 実装状況

更新: 2026-10-27

## 実装済み・自動検証済み

- v1.6.1: Mod UI上の配線を右クリックして個別切断し、Undo/Redoできる操作
- v1.6.0: 回路処理を2/4/6/8倍にする時界加速ノード
- v1.6.0: ModなしGUIの所持ノード一覧と、保存時にノードを保管庫へ戻す「しまう」処理
- v1.6.0: 正規署名のある旧欠片・旧無属性魔石の発行台帳復旧
- v1.6.0: Java版・統合版共通Web回路エディター連携と `/msf web`

- Paper/Fabric/sharedのGradleマルチプロジェクト、Java 25
- 72種を重複なく登録する欠片カタログ
- 型付きポート、循環、個数、上限、実行コストを検出するコンパイラ
- PDC + HMAC-SHA256 + SQLite発行台帳による欠片認証
- SQLite WAL、回路JSON、active回路、発動/強化監査、楽観ロック
- Modなし/統合版向け5×5インベントリGUI
- `/msf open`の保存済み魔術一覧、新規作成、再編集、ページ選択、二段階確認付き削除（Mod/Modなし双方）
- 保存済み魔術の有効・無効切替、排他的な1件有効化、無効化時の予約発動停止（Mod/Modなし双方）
- 回路1件あたりの実行負荷上限を128から512へ拡張
- Fabric通信、ノードカード・色分け配線・ドットキャンバスを備えたMinecraft風回路エディター
- 所持欠片パレット、名前/属性/機能検索、ドラッグ配置、ノード移動、型付きポート自由配線、右クリック追加/複製/しまう
- 所持欠片パレットの種類・レベル単位集約、所持数/配置数表示、7カテゴリの即時絞り込み
- 12属性の固有色と8形状の軌道を基礎に、分裂・追尾・貫通・連鎖・範囲・持続・増幅を反映する構成依存パーティクル
- 1実行あたりの粒子上限と低TPS時の自動縮小（効果判定・ダメージは維持）
- マウスホイールズーム、空白左ドラッグ/中クリックパン、Ctrl+Z/Ctrl+Y、ホバー詳細、クライアント即時検証、サーバー権威保存
- 72種類すべての固有二つ名、色付きアイテム名、個別の効果説明
- Fabric UIのリアルタイム戦闘指標（攻撃力・DPS・連射力・消費魔力・防御貫通・確定ダメージ）
- `ARMOR_PENETRATION` と `TRUE_DAMAGE` のサーバー権威型ダメージ処理
- 検証レスポンスのJSONを画面へ露出せず、日本語の短いステータスとして表示
- 表示倍率をノード本体・文字・ポート・背景間隔へ統一適用し、キャンバス外の描画をクリップ
- Mod版の配置座標を画面内5×5から約132×132グリッド相当の広域仮想キャンバスへ拡張
- 6トリガー、12属性の基本効果、対象/形状に基づくサーバー側発動
- 魔力、TPS/実行コスト制限、切断時キャンセル、緊急全停止
- ActionBarの実HP/MP HUD（実体力属性は変更せず、バニラのハート表示だけ最大20へ縮尺）
- 装備中の防具・両手の青色Lore `MP: 数値` による最大MP加算、`MPS: 数値` による毎秒MP回復加算
- 保存済み魔術のスナップショットを封入し、成功発動時だけ残回数を消費する5回使用の署名付き「無属性の魔石」
- 無属性の魔石の個体・所有者・封入魔術・残回数をSQLite台帳でも照合する複製対策
- 欠片を右クリックして種類ごとに255個まで保存する所有者別DB保管庫
- 回路保存時の欠片消費・封入と、封入後に現物を要求しない発動処理
- 2〜4倍増幅、Mobセンサー、連鎖、反復、遅延、周期、変数、計数、記憶、帰還などの制御ノード
- 剣1本につき保存済み魔術を1つ埋め込む`/msf sword`
- 素材・重複欠片・Vault通貨を使うLv1〜5強化、失敗時レベル維持
- shared単体テスト、署名改ざんテスト、SQLite revision・所有者付き削除・active回路引継ぎテスト
- MP/MPS Lore解析、魔石の所有者検証・一度だけの封入・原子的な残回数消費テスト
- `clean build`成功

## 本番導入済み

- Paper版 v1.5.1をメインサーバーへ導入し、69欠片登録・DB・魔術有効/無効切替・実行負荷上限512・Paper起動完了を確認
- Fabric/Paper v1.5.1をGitHub Releaseへ公開し、GitHub Actions成功を確認
- 既存SQLiteの整合性確認済み、旧JAR・設定・DBのロールバックバックアップ取得済み

## 実機確認が必要

- Modなし/統合版クライアントでの魔術一覧GUI操作
- Fabric 26.1.2 v1.5.1での魔術一覧画面とplugin message往復
- 実プレイヤーによる全属性・全形状・複合Modifierの見た目確認
- MythicMobsボス、保護領域、PvP規則との組み合わせ
- 長時間・多人数負荷試験

## 今後の拡張点

- Fabric画面での範囲選択、コピー＆ペースト
- 全LOGICノードの時間継続型VM（現在はコンパイル検証と基本実行）
- PROJECTILE/BEAM/BARRIER等それぞれの独立した物理挙動
- WorldGuard独自フラグとMythicMobs専用ターゲットAPI
- 設定YAMLからのホットリロード可能な全パラメータ差し替え

ビルド成功のみで実サーバー動作確認済みとは扱っていません。

# MonaSpellForge 2.0 implementation status

## 2.0.0-alpha.1

Implemented and covered by automated tests:

- Java 25 ArcSys lexer, parser and immutable AST.
- Closed deterministic ArcVM bytecode compiler and capability discovery.
- Fuel, stack, list, string and effect bounded VM with no reflection, file, process, SQL or network API.
- Offline `arcsys check`, `compile` and `disasm` CLI.
- Additive SQLite Arc spell/revision/deploy-audit storage with optimistic revision conflicts.
- Paper-authoritative compile, validation, deployment and bounded simulation.
- Append-only v1 custom-payload protocol additions.
- Fabric loopback-only WebSocket bridge with explicit in-game pairing code, TTL, one client, size/rate limits and disconnect revocation.
- VS Code `.arc` language registration, TextMate grammar, LSP diagnostics/completion/hover/definition/formatting.
- VS Code command set and ArcSys Studio tree.

Feature-gated / not represented as complete:

- Bidirectional lossless conversion for every legacy 5×5 node.
- Graphical VS Code node editor.
- Cross-proxy verification and production load/fuzz certification.
- ArcSys-only fragments in loot/gacha.

Production remains on the stable 1.7 line until the release-candidate migration and operational tests pass.

## Advanced language specification follow-up

The advanced ArcSys and Minecraft API specifications are accepted, but are not yet production-complete. The development branch now includes dimension-aware types, local/effect functions, global contract pre/postconditions, match expressions, state/requirement metadata, expanded events, Skript-style control sugar, lexically scoped LIFO `defer` cleanup, runtime-tagged Option/Result values with payload pattern matching and `?` propagation, resumable wait/after continuations with bounded cooperative tick scheduling, deterministic parallel all/any and race groups with aggregate budgets, typed TTL/schema-version persistence connected to a Paper main-thread ArcVM host, bounded live combat/Display/raycast/PDC effects, an allowlisted bidirectional Skript boundary, optional WorldGuard/MythicMobs adapters and a pause/step/watch bytecode debugger. Remaining blockers include named contract composition, source maps/profiler counters, virtual structures, full generic propagation, fuzz/proxy/rollback drills, sustained live load and actual Java/Bedrock client verification. See `docs/advanced-spec-coverage.md`.
