# ARCSYS_CORE — 術式演算核《アルカナ・プロセッサ》

`ARCSYS_CORE` is the 73rd built-in fragment in MonaSpellForge 2.0.0-alpha.2. The existing 72 fragment IDs are unchanged. It is a `LOGIC`, `LEGENDARY`, level 1–5 fragment and executes only after an `EXEC` edge reaches `exec_in`.

## Safety defaults

The feature is shipped disabled. Set `arcsys.core.enabled: true` in a test environment to enable binding. Production effects remain separately disabled until `allow-production-effects` is explicitly enabled. Paper always recompiles the selected revision, ArcVM verifies bytecode, and the level controls fuel/effect/MP/cooldown limits.

No VS Code client can contact Paper directly. Core operations use the existing route: VS Code → authenticated Fabric loopback bridge → plugin message → Paper. Paper rechecks player, circuit, node, fragment instance, spell owner and revision.

## Binding

- Fabric: save the circuit, right-click the core node, then choose `ArcSys制御パネル`.
- VS Code: deploy the current `.arc`, then run `ArcSys: Bind Program to Core`.
- No Mod / Bedrock: use `/msf arc core list` followed by the bind, inspect or unbind subcommand shown by command help.
- `PINNED` fixes one immutable revision. `LATEST` resolves the latest verified revision once at execution start.

The binding migration is additive. `arc_core_bindings` stores ownership and revision metadata; `arc_core_audit` records binding and execution decisions. Removing or replacing the bound node prunes its binding. Existing circuits, issued fragments, neutral stones and ArcSys revisions remain intact.

## Ports

Inputs: `exec_in`, `entity_in`, `location_in`, `number_in`, `boolean_in`.

Outputs: `exec_out`, `exec_fail`, `entity_out`, `number_out`, `boolean_out`.

The current runtime chains reachable core nodes in graph order. Only a completed core advances the success path. Compile/runtime/permission/mana/revision/budget failures do not emit success. Effects are held until ArcVM completes and are revalidated by Paper before commit.

## Samples

- `examples/core_conditional.arc`: enemy HP branch.
- `examples/core_state_memory.arc`: owner/spell state with TTL.
- `examples/core_sensor_router.arc`: sensor input and typed number/boolean routing.

Live Paper/Fabric/Geyser validation remains a separate manual gate; a successful build is not treated as a live-server pass.

## Editor fixes included in alpha.2

The Fabric editor's `ノードをしまう` action uses a dedicated atomic server operation. It can return a node even when removing that node temporarily makes the draft circuit incomplete. The server still validates ownership, revision, fragment identity and storage limits.

Node circuits can be exported as `.arc` text to the clipboard and imported back into the Mod editor. The embedded graph snapshot includes node types, levels, parameters, positions and typed edges. Fragment instance UUIDs are deliberately excluded; import allocates matching fragments from the player's current circuit/palette so an exported file cannot forge ownership.

Neutral magic stones are bearer-transferable. The first valid use after a handoff atomically moves ledger custody and re-signs the physical item. A copied item with the previous owner/signature becomes stale as soon as custody moves and is rejected.
