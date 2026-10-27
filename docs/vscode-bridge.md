# ArcSys Developer Bridge

## Alpha installation

1. Keep the stable 1.7 installation in production. Test the 2.0 alpha on a separate Paper/Fabric environment.
2. Install the matching 2.0 Paper plugin and Fabric client mod.
3. Install the packaged `ArcSys 2.0 — Magic Engine` VSIX in VS Code.
4. Join the server with the Fabric mod and run `/msf open`.
5. Select **VS Code** in the spell library. This is the Minecraft-side approval action.
6. Run **ArcSys: Connect to Minecraft** and enter the displayed six-digit, single-use code.

The bridge listens only on `127.0.0.1:27128`. It is closed on Minecraft disconnect, the code expires after ten minutes, and only one VS Code client can pair. VS Code never opens a connection to Paper; every request traverses the logged-in Fabric custom-payload session.

## Commands

- `ArcSys: Connect to Minecraft`
- `ArcSys: Open Spell from Minecraft`
- `ArcSys: Compile Current Spell`
- `ArcSys: Validate Spell`
- `ArcSys: Deploy Spell to Minecraft`
- `ArcSys: Run Simulation`
- `ArcSys: Open Node Graph`
- `ArcSys: Start Debugging`
- `ArcSys: View ArcVM Logs`
- `ArcSys: Disconnect`

Deploy is always explicit. Paper recompiles source, rechecks permissions and capabilities, and uses optimistic revisions. Existing activations are not mutated; the new revision becomes available to later activations.

## Current alpha boundary

The debugger command runs a bounded server-side simulation and returns the trace/effect requests. Breakpoint suspension, variable watch, graphical graph editing and production combat-effect execution are not yet enabled. The advanced ArcSys fragments therefore remain outside loot and gacha pools.
