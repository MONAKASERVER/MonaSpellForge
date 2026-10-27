# ArcSys threat model

## Trust boundaries

VS Code and Fabric are untrusted presentation clients. Paper owns identity, fragments, permissions, compilation, bytecode, fuel, mana and effects. The ArcVM is an interpreter over a closed opcode set and has no class loader or general host object access.

## Controls

- Source, token, packet, list, string, function, nesting and bytecode size limits.
- Loop bounds, call depth, per-activation fuel and per-player rate limits.
- Loopback-only WebSocket, rotating random secret, origin allowlist, explicit game approval and session revocation on disconnect/logout.
- Request ids, optimistic revisions and payload limits prevent replay and stale overwrite.
- Capability derivation from server state; source comments and client claims are ignored.
- Effect plans are immutable data and revalidated on the main thread against ownership, target lifetime, world, PvP/region policy, mana and TPS.
- Audit records omit secrets and redact full source from routine logs.

## Explicitly forbidden

Reflection, Java/Python execution, process creation, filesystem, arbitrary network, SQL, native libraries, `eval`, unbounded loops/recursion and raw UUID-to-entity authority are not represented by the grammar or bytecode.
