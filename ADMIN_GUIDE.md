# 管理者ガイド

主なコマンド:

- `/msf open`
- `/msf spell list`
- `/msf spell cast`
- `/msf upgrade`
- `/msf stone`
- `/msf sword`
- `/msf fragment give <player> <id> <level> <amount>`
- `/msf debug`
- `/msf admin stopcasts`

主要権限は`plugin.yml`に定義されています。`security.hmac-secret`は初回起動時に自動生成されます。稼働後に変更すると既存欠片を検証できなくなるため、バックアップと移行なしに変更しないでください。

欠片は右クリックで所有者別DB保管庫へ格納できます。新しく回路へ装着した欠片は保存成功時に消費・封入され、以後の発動時には現物を要求しません。保存した回路のうち、最後に保存した1件だけが有効になります。

強化失敗時はレベルを維持し、素材・同種欠片・MONAを消費します。設定は`upgrade.yml`です。Vault経済プロバイダーがない場合、金額が必要な強化は安全に拒否されます。
