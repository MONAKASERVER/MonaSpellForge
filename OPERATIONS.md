# 運用

## バックアップ

停止中、またはSQLiteのオンラインバックアップ手順で`plugins/MonaSpellForge/monaspellforge.sqlite`と`config.yml`を同時に保全してください。WALモードのため、稼働中にDB本体だけをコピーしないでください。

## 障害時

- 全発動停止: `/msf admin stopcasts`
- TPS 17未満: 新規発動を拒否
- 無効署名/発行台帳不一致: 欠片を拒否し、再発行元を監査
- 回路競合: 編集画面を閉じ、`/msf open`で再取得

`cast_audit`、`upgrade_audit`、`fragment_issuance`を調査に使用できます。秘密鍵、完全な署名、個人情報をログへ出さない設計です。

本番導入ではPaper/Fabricの両JARを同じリリース番号で揃え、まず検証環境で再起動、接続、GUI、保存、発動、強化を確認してください。
