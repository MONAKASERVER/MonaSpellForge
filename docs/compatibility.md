# Compatibility audit

- Runtime target: Java 25, Paper 26.1.2 stable API, Fabric Loader 0.19.4 and Fabric API 0.155.2+26.1.2.
- Current Paper `api-version` remains `1.21.11`, the API value accepted by the running 26.1.2 server; changing it without a supported Paper declaration is avoided.
- Existing protocol v1 values are append-only so 1.x clients retain ordinals. ArcSys negotiation carries independent language/ABI features.
- Existing database tables, HMAC-signed fragments, neutral stones, circuits and commands are retained.
- Java without the Mod and Geyser players keep inventory/web editing and may execute server-approved ArcSys spells, but live VS Code connectivity requires the Fabric Mod.
- Bungee custom-payload forwarding and real Fabric/Paper round trips remain required release-candidate tests; local unit tests do not claim those as passed.

Official references used during implementation are the VS Code Extension/LSP guides, Fabric 26.1.2 networking documentation and Paper plugin messaging documentation. Actual checked-in dependency resolution and compilation are the build truth.
