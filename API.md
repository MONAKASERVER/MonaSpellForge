# API

Paperの`ServicesManager`に次を登録します。

- `FragmentService`: HMAC/PDC/発行台帳付きの欠片生成・検証
- `CircuitService`: 回路取得・検証・楽観ロック付き保存
- `SpellService`: 回路ID指定発動・実行中キャスト停止

```java
FragmentService fragments = Bukkit.getServicesManager().load(FragmentService.class);
List<ItemStack> issued = fragments.createItems(player, "FIRE", 1, 1, "my-addon:quest");
```

生成物を独自に複製せず、必ず`createItems`を使用してください。各個体UUIDはSQLiteの発行台帳にも記録され、PDC署名だけをコピーしたアイテムは検証で拒否されます。

通信チャネルは`monaspellforge:bridge`、プロトコルはv1です。保存は`baseRevision`による楽観ロックを使用し、競合時は`CONFLICT`を返します。
