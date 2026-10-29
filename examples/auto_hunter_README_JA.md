# AutoHunter

`auto_hunter.arc` は、半径12ブロック以内の敵対Mobを最大6体まで検出し、攻撃するArcSysサンプルです。

- 通常時: 10ダメージ
- HPが25%以下: 30ダメージ
- `combat.can_attack` が拒否した対象は攻撃しない
- プレイヤーや友好Mobは `mobs.hostile` の検索対象に含めない

## 現行版での試験

1. ArcSys CLIで `auto_hunter.arc` を検証します。
2. VS Code/Fabric Bridgeからサーバーへデプロイします。
3. `/msf arc list` で術式UUIDを確認します。
4. `/msf arc cast <術式UUID>` で周辺検索と攻撃を1回実行します。

## 自動実行について

ソースには `on pulse(20t)`（1秒間隔）も定義済みです。ただしPaper 2.0.0-alpha.9は、保存済みArcSys術式の`on pulse`を常駐起動するスケジューラーへまだ接続していません。そのためalpha.9では`on cast`の手動試験まで利用でき、常時自動討伐にはPaper側のpulse実行機能が必要です。

