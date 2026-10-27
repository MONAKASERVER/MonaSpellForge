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
- Capability discovery and closed APIs for math, targets, combat, mana, particles, displays, raycast, block/item observation, ArcSys storage and time.
- Operator-only capability boundary for the future Skript bridge; arbitrary Skript, commands, JVM, OS, files and sockets remain unavailable.
- Deterministic bytecode, verifier, fuel/stack/list/string/effect/call-depth limits and simulation effect requests.

## Implemented as syntax only; runtime completion required

- `module`, `use`, `struct`, `enum`, `type`, `trait`, `impl` declarations are recognized so files can be incrementally migrated. Cross-file linking and full nominal typing are not enabled.
- `atomic`, `transaction`, `visual`, `combat`, `after` are parsed/lowered, but do not yet have the full transactional/cooperative scheduler semantics from the advanced specification.
- State host operations exist in bytecode/simulation; typed TTL persistence is not yet connected to production ArcVM activations.

## Not complete — blocks production deployment

- `Option<T>`, `Result<T,E>`, `?`, constructor-pattern matching and exhaustive enum checking.
- Full statement/block match arms and enum/struct runtime values.
- `parallel all/any`, `race`, `await`, conditional wait and resumable cooperative tasks.
- Typed persistent state scopes, TTL cleanup, version migration and player data erasure.
- Contract `requires/ensures`, effect-function signatures and static budget annotations as enforceable constructs.
- Target selection DSL, node `bind`, channel/charge/release and cross-spell signal loop prevention.
- Production implementations for Display, virtual structures, raycast, PDC/storage and combat effects with WorldGuard/MythicMobs rules.
- Allowlisted bidirectional Skript/SkBee event bridge.
- Source maps, real breakpoints, variable watch and profiler counters.
- Full VS Code graph editor and lossless code ↔ legacy node conversion.
- Load, fuzz, proxy, Java/Bedrock and rollback drills.

The stable production server must remain on the 1.7 line until every blocking item is implemented and verified.
