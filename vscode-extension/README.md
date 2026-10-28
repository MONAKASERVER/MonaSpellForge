# ArcSys 2.0 — Magic Engine

Language support and the authenticated MonaSpellForge Fabric bridge for `.arc` files.

This alpha provides syntax highlighting, diagnostics, completion, hover help, definition lookup, formatting, ArcSys Studio, server-authoritative validation/compile/deploy, simulation, graph data and logs. Live Minecraft features require the matching MonaSpellForge Fabric mod and an in-game pairing approval. The extension never connects directly to Paper.

The status bar item `ArcSys Auto Deploy: OFF` toggles opt-in deployment for ordinary `.arc` files. When enabled, saving a connected `.arc` waits briefly for repeated saves, then deploys the latest saved source through Fabric and Paper. A Core-scoped session opened from Minecraft always auto-deploys and binds only its designated document, independently of the global toggle.
