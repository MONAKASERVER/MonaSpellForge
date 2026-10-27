# ArcSys runtime integrations

## Effect contracts

All effects are requests until the Paper main thread validates the complete new segment. The server checks that the bytecode declared the matching capability and applies independent limits for total effects, Displays, PDC writes and Skript signals. Unknown effect types fail closed.

Source functions are pure by default. A mutating host call is accepted only in an `effect fn` with a matching `uses` declaration. A top-level `contract` applies its `requires` and `ensures` expressions to every handler in the file. A failed condition returns `Err("CONTRACT_VIOLATION")` without entering the handler or returning its successful value.

## Skript and SkBee

`arc-runtime.skript-allowlist` is empty by default. Only names in this list can cross the bridge.

- ArcSys to Skript: listen for `ArcSkriptBridgeEvent`. The event exposes player, allowlisted signal and a bounded primitive payload.
- Skript to ArcSys: obtain `ArcSkriptBridge` from Bukkit ServicesManager, or run the operator-only `/msf arc signal <player> <signal> [payload]` command. Only enabled `on signal` spells are considered.
- There is no API for executing Skript source, arbitrary functions, unrestricted commands, files, network or JVM reflection.
- Per player, at most four inbound signals are accepted in one server tick and at most eight enabled handlers start per signal.

## Optional plugins

- WorldGuard: Display placement is checked through the session manager. If WorldGuard is present but its adapter cannot be queried, mutation is denied.
- MythicMobs: target references include a bounded `mythic_type` when the active-mob API, metadata or the compatibility scoreboard tag identifies it. Damage still travels through Bukkit's normal cancellable damage event.

## Client compatibility

ArcVM execution is server-authoritative and does not require the Fabric Mod. Java without the Mod and Bedrock/Floodgate players can invoke saved spells through normal server commands and use the web editor. The VS Code live bridge intentionally requires the Fabric Mod.

Automated tests cover compiler/VM behavior, protocol limits and 1,000 simultaneous continuations. They do not prove visual rendering, WorldGuard/MythicMobs version compatibility, Geyser/Floodgate packet behavior or real-client usability; those require the release-candidate staging checklist.
