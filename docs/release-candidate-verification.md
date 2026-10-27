# ArcSys 2.0 release-candidate verification

No item in this document may be marked PASS from a successful build alone.

## Automated evidence

| Check | Current result |
|---|---|
| Parser/compiler/VM/Paper unit tests | PASS on the development workstation |
| Effect capability and segment-limit rejection | PASS |
| Contract precondition failure path | PASS |
| Debug pause/resume/watch continuation | PASS |
| 1,000 simultaneous one-tick continuations | PASS; all drained in 8 ticks with no tick resuming more than 128 |
| VS Code bridge TypeScript tests | PASS |
| Paper shaded JAR and Fabric remapped JAR | PASS |

## Staging server checks still required

| Check | Required observation | Status |
|---|---|---|
| WorldGuard | Display denied in protected region and allowed in owned region | NOT RUN |
| MythicMobs | `mythic_type` visible and damage mechanics remain cancellable | NOT RUN |
| Skript/SkBee | allowlisted signal works both directions; non-allowlisted signal is rejected | NOT RUN |
| Display cleanup | Text/Item/Block Displays disappear at configured TTL after cast/logout/restart | NOT RUN |
| Raycast/PDC | range, namespace, value length and ownership limits hold | NOT RUN |
| Sustained load | record TPS/MSPT/heap with 1, 10, 50 and 100 active casters for 30 minutes each | NOT RUN |
| Restart/rollback | active continuations cancel safely and stable database remains readable after rollback | NOT RUN |

## Java and Bedrock real-client checks still required

Run each case once with a Java client without the Mod and once through Geyser/Floodgate with a Bedrock client:

1. Join, change world and respawn without chunk or movement failure.
2. Pair the web editor, list/open/save a spell and reject an expired pairing code.
3. Enable a saved spell, cast it through the normal server command, and observe HP/MP/action-bar state.
4. Trigger a Display/particle spell and verify the client remains connected even if a visual is unsupported.
5. Trigger raycast, potion and damage effects against a normal mob and a MythicMob.
6. Verify WorldGuard denial, logout cancellation and reconnect behavior.
7. Confirm that VS Code live deployment remains unavailable without the Fabric Mod.

Current status: **NOT RUN**. A real Bedrock client session is required; automated JVM tests do not satisfy this gate.
