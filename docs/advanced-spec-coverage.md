# ArcSys Advanced Specification coverage

This document separates implemented execution semantics from accepted design. A parsed keyword is never reported as a completed runtime feature.

## Implemented in the current development branch

- UTF-8 `.arc` lexer/parser with bounded source, token and nesting limits.
- `let`, `let mut`, `const`, `state`, block scope, `fn`, typed parameter/return syntax and bounded local calls.
- `if/else`, expression `match` with literal/range/enum/wildcard patterns, `for`, `repeat`, `break`, `continue`, `guard`.
- `Int`, `Float`, `Bool`, `String` inference and dimension-aware `Duration`, `Distance`, `Mana`, `Percent` literals.
- Events: cast, pulse, hit, kill, damage, damage_taken, death, enter, mana_below, spell_end, signal and projectile_impact.
- `requires fragments` and `requires permission` metadata, Paper-side ownership/permission validation and revision persistence.
- `chance`, `wait`, `every`, Skript-style `cast ... at ...`, `apply ... to ... for ...` lowering into the same compiler/VM.
- Lexically scoped `defer` cleanup in LIFO order on normal block exit, `return`, `break` and `continue`; control-flow and nested defer inside cleanup blocks are rejected.
- Runtime-tagged `Option`/`Result` values with `Some`, `None`, `Ok`, `Err`, payload-binding match patterns, `?` early return and Option/Result exhaustiveness checks.
- Resumable `wait` / `after` execution with opaque continuations, preserved fuel/variables/call stack/effects, bounded suspension count and a single-threaded tick scheduler with task and per-tick limits.
- Parsed state persistence policies for player/spell/session scope and TTL, embedded closed-host metadata, plus bounded typed SQLite storage with schema versions, expiry cleanup and owner erasure.
- `await parallel all`, `await parallel any` and `race` with separately compiled ArcVM branches, deterministic cooperative joins, aggregate fuel/effect accounting and cancellation-by-discard after resolution.
- Paper main-thread ArcVM activation with per-cast ownership/permission/fragment revalidation, logout/command cancellation and bounded live target, mana, damage, potion, particle and persistent-state effects.
- Capability discovery and closed APIs for math, targets, combat, mana, particles, displays, raycast, block/item observation, ArcSys storage and time.
- Operator-only capability boundary for the future Skript bridge; arbitrary Skript, commands, JVM, OS, files and sockets remain unavailable.
- Deterministic bytecode, verifier, fuel/stack/list/string/effect/call-depth limits and simulation effect requests.

## Implemented as syntax only; runtime completion required

- `module`, `use`, `struct`, `enum`, `type`, `trait`, `impl` declarations are recognized so files can be incrementally migrated. Cross-file linking and full nominal typing are not enabled.
- `atomic`, `transaction`, `visual`, `combat`, `after` are parsed/lowered, but do not yet have the full transactional/cooperative scheduler semantics from the advanced specification.
- State host operations exist in bytecode/simulation; typed TTL persistence is not yet connected to production ArcVM activations.

## Not complete — blocks production deployment

- Full generic propagation for annotated `Option<T>` / `Result<T,E>` signatures and exhaustive user-defined enum checking (the runtime constructors, patterns and `?` semantics are implemented).
- Full statement/block match arms and enum/struct runtime values.
- General-purpose awaitable handles and conditional/event waits outside parallel groups.
- An explicit operator/player persistent-data erasure command (production ArcVM state read/write is connected; the owner-erasure storage operation exists).
- Contract `requires/ensures`, effect-function signatures and static budget annotations as enforceable constructs.
- Target selection DSL, node `bind`, channel/charge/release and cross-spell signal loop prevention.
- Production implementations for Display, virtual structures, raycast and PDC; explicit WorldGuard/MythicMobs adapters beyond Bukkit's cancellable damage path.
- Allowlisted bidirectional Skript/SkBee event bridge.
- Source maps, real breakpoints, variable watch and profiler counters.
- Full VS Code graph editor and lossless code ↔ legacy node conversion.
- Load, fuzz, proxy, Java/Bedrock and rollback drills.

The stable production server must remain on the 1.7 line until every blocking item is implemented and verified.
