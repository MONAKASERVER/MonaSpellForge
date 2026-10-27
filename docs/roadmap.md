# ArcSys implementation roadmap

## Phase 0 — audited

- Buildable Java 25/Paper/Fabric 26.1.2 source exists.
- Production baseline is 1.7.0 with 72 definitions; historical 1.5 audit has 69.
- Existing SQLite, item signatures, commands and protocol are preserved.

## Phase 1 — language MVP (implemented in alpha.1)

- Lexer/parser/AST, diagnostics, bounded constructs, compiler API and CLI.
- Golden, malformed input, nesting and deterministic output tests.

## Phase 2 — VM MVP (implemented in alpha.1)

- Verified bytecode, fuel/call-depth/list/string limits and pure effect requests.
- No arbitrary JVM, OS or network access.

## Phase 3 — Paper integration (vertical slice implemented in alpha.1)

- Additive Arc project/source/revision tables, authoritative compile/deploy, permissions and audit are implemented.
- Production effect execution and the administrative kill switch remain gated.
- Eight ArcSys nodes remain feature-gated until execution and ownership tests pass.

## Phase 4 — Fabric and VS Code Studio (bridge/LSP vertical slice implemented in alpha.1)

- Authenticated loopback bridge, explicit Minecraft approval, LSP, completion, diagnostics, formatting, Studio sidebar, open/compile/validate/deploy/simulate/log commands are implemented.
- Breakpoint suspension, variable watch and graphical node editing remain Phase 4 follow-up work; `Start Debugging` currently runs the bounded server simulation and returns its trace/effects.

## Phase 5+

- Pulse/state/CALL libraries, simulator/debug traces, proxy tests, load/fuzz tests, advanced nodes and release candidate migration drills.

No alpha-only definition is placed into production loot or gacha pools.
