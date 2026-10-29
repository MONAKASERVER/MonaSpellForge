# ArcSys 2.0 — Magic Engine

Language support and the authenticated MonaSpellForge Fabric bridge for `.arc` files.

This alpha provides syntax highlighting, diagnostics, completion, hover help, definition lookup, formatting, ArcSys Studio, server-authoritative validation/compile/deploy, simulation, graph data and logs. Live Minecraft features require the matching MonaSpellForge Fabric mod and an in-game pairing approval. The extension never connects directly to Paper.

The status bar item `ArcSys Auto Deploy: OFF` toggles opt-in deployment for ordinary `.arc` files. When enabled, saving a connected `.arc` waits briefly for repeated saves, then deploys the latest saved source through Fabric and Paper. A Core-scoped session opened from Minecraft always auto-deploys and binds only its designated document, independently of the global toggle.

## かんたんスタート

Minecraftの魔術一覧またはARCSYS_COREで`VS Code接続`を押すと、6桁コードがクリップボードへ自動コピーされます。VS Codeのステータスバーにある`ArcSys: かんたん接続`、またはArcSys Studioの`かんたんスタート`を押してください。コードを手入力せずに接続し、テンプレート作成・保存済み術式・現在の`.arc`から開始できます。

テンプレートは選択直後にPaperへ検証・デプロイされ、保存時Auto Deployも有効になります。以後は`Ctrl+S`で更新できます。既存の一度限りコード、10分の有効期限、localhost限定Bridge、Paper側の再コンパイルと権限検証は維持されます。
