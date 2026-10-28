# ArcSys Advanced Specification coverage

This document separates implemented execution semantics from accepted design. A parsed keyword is never reported as a completed runtime feature.

## Implemented in the current development branch

- UTF-8 `.arc` lexer/parser with bounded source, token and nesting limits.
- `let`, `let mut`, `const`, `state`, block scope、代入可否検査、未定義変数検査、型付き `fn` と有界ローカル呼び出し。
- `if/else`, expression `match` with literal/range/enum/wildcard patterns, `for`, `repeat`, `break`, `continue`, `guard`.
- `Int`, `Float`, `Number`, `Bool`, `String`, `Entity`, `Location`, `World`, `Block`, `Item` と、次元を区別する `Duration`, `Distance`, `Mana`, `Percent`。`List<T>`, `Option<T>`, `Result<T,E>` の注釈・引数・戻り値伝播。
- コンパイラ、型検査、VS Code補完を同じ `ArcMagicApiCatalog` から生成。名前付き引数、必須引数、引数型、API戻り値、単位なし旧構文への警告を一貫して診断。
- Events: cast, pulse, hit, kill, damage, damage_taken, death, enter, mana_below, spell_end, signal and projectile_impact.
- `requires fragments` and `requires permission` metadata, Paper-side ownership/permission validation and revision persistence.
- `chance`, `wait`, `every`, Skript-style `cast ... at ...`, `apply ... to ... for ...` lowering into the same compiler/VM.
- Lexically scoped `defer` cleanup in LIFO order on normal block exit, `return`, `break` and `continue`; control-flow and nested defer inside cleanup blocks are rejected.
- Runtime-tagged `Option`/`Result` values with `Some`, `None`, `Ok`, `Err`, payload-binding match patterns, `?` early return and Option/Result exhaustiveness checks.
- Resumable `wait` / `after` execution with opaque continuations, preserved fuel/variables/call stack/effects, bounded suspension count and a single-threaded tick scheduler with task and per-tick limits.
- Parsed state persistence policies for player/spell/session scope and TTL, embedded closed-host metadata, plus bounded typed SQLite storage with schema versions, expiry cleanup and owner erasure.
- `await parallel all`, `await parallel any` and `race` with separately compiled ArcVM branches, deterministic cooperative joins, aggregate fuel/effect accounting and cancellation-by-discard after resolution.
- Paper main-thread ArcVM activation with per-cast ownership/permission/fragment revalidation, logout/command cancellation and bounded live target, mana, damage, potion, particle and persistent-state effects.
- Top-level `contract` preconditions/postconditions, pure `fn` versus `effect fn ... uses [...]` validation, and a server-side per-segment Effect Contract which rejects undeclared capabilities and over-budget Display/PDC/Skript effects before application.
- Bounded Text/Item/Block Display effects, entity/block raycasts, player-scoped namespaced PDC, deterministic per-cast random generation, MythicMobs identity observation and fail-closed WorldGuard build checks for visual/PDC mutations.
- Allowlisted Skript bridge in both directions: outbound Bukkit events contain only bounded primitive payloads; inbound signals use the registered service or the operator-only command and can start at most eight enabled handlers.
- ArcVM bytecode debugger sessions with pause, instruction step, continue, stop, bounded trace and explicit variable watches, exposed through the authenticated Fabric bridge and VS Code commands.
- Automated scheduler pressure coverage for 1,000 simultaneous continuations while preserving the configured 128-resume per-tick ceiling.
- Capability discovery and closed APIs for math, targets, combat, mana, particles, displays, raycast, block/item observation, ArcSys storage and time.
- Operator-only capability boundary for the Skript bridge; arbitrary Skript source, unrestricted commands, JVM, OS, files and sockets remain unavailable.
- Deterministic bytecode, verifier, fuel/stack/list/string/effect/call-depth limits and simulation effect requests.

## Implemented as syntax only; runtime completion required

- `module`, `use`, `struct`, `enum`, `type`, `trait`, `impl` declarations are recognized so files can be incrementally migrated. Cross-file linking and full nominal typing are not enabled.
- `atomic`, `transaction`, `visual`, `combat`, `after` are parsed/lowered, but do not yet have the full transactional/cooperative scheduler semantics from the advanced specification.
- `atomic` and `transaction` currently provide bounded block grouping, not rollback of already-applied external Bukkit effects.

## Not complete — blocks production deployment

- Exhaustive checking and runtime values for user-defined enums/structs (built-in `Option<T>` / `Result<T,E>` propagation, constructors, patterns and `?` semantics are implemented).
- Full statement/block match arms and enum/struct runtime values.
- General-purpose awaitable handles and conditional/event waits outside parallel groups.
- An explicit operator/player persistent-data erasure command (production ArcVM state read/write is connected; the owner-erasure storage operation exists).
- Named-contract composition and static user-authored budget annotations beyond global `contract` pre/postconditions and server Effect Contract limits.
- Target selection DSL, node `bind`, channel/charge/release and cross-spell signal loop prevention.
- Virtual structures and richer Display transformations; live-server verification of the fail-closed WorldGuard reflection adapter and MythicMobs API/metadata adapter.
- Source-line maps and aggregate profiler counters (bytecode breakpoints, step/continue/stop, watches and bounded traces are implemented).
- Full VS Code graph editor and lossless code ↔ legacy node conversion.
- Fuzz, proxy, rollback, sustained Paper load and Java/Bedrock real-client drills. The 1,000-continuation scheduler pressure test is automated, but it is not a substitute for a live server/client run.

The production server must remain on its explicitly deployed version. Alpha.3 must not be enabled in production until the remaining blockers are implemented and live-server/client verification is complete.
