# MonaSpellForge 2.0 architecture

MonaSpellForge 2.0 extends the proven 1.7 codebase instead of replacing it. Existing `shared`, `paper`, and `fabric` modules remain the compatibility boundary. ArcSys is split into pure-Java modules so no player program receives Bukkit, Fabric, filesystem, process, reflection, SQL, or network access.

The trusted path is:

`ArcSys source -> lexer/parser -> semantic and capability checker -> bytecode verifier -> bounded ArcVM -> EffectPlan -> Paper host revalidation -> game effect`.

Paper is authoritative. Fabric and VS Code may produce diagnostics for responsiveness, but deploy sends source and revision metadata through the Fabric client. Paper recompiles source, derives capabilities from the authenticated player and owned circuit, verifies budgets, then atomically stores a new immutable revision. Running activations keep their existing compiled revision.

The VS Code extension connects only to a loopback WebSocket opened by the Fabric Mod. The Mod rotates its pairing secret when the Minecraft connection changes. A pairing attempt requires the secret in VS Code and explicit approval in Minecraft. The extension never connects to Paper directly.

Modules:

- `arcsys-language`: tokens, source spans, AST and parser.
- `arcsys-compiler`: semantic checks, capabilities, deterministic bytecode and diagnostics.
- `arcsys-vm`: bytecode verifier and fuel-bounded interpreter producing pure effect requests.
- `arcsys-cli`: offline check, compile, disassemble and format commands.
- `shared`: existing circuit model and Paper/Fabric protocol.
- `paper`: ownership, permissions, source persistence, authoritative compilation and host gateway.
- `fabric`: game editor and loopback developer bridge.
- `vscode-extension`: ArcSys language client/server and ArcSys Studio views.

## Version boundaries

Protocol v1 ordinals remain append-only for older clients. ArcSys packets add explicit grammar, VM ABI, registry hash, request id and optimistic revision fields. Unknown features force read-only behavior instead of silent writes.
