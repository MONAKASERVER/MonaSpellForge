# ArcSys Developer Bridge

## Alpha installation

1. Keep the stable 1.7 installation in production. Test the 2.0 alpha on a separate Paper/Fabric environment.
2. Install the matching 2.0 Paper plugin and Fabric client mod.
3. Install the packaged `ArcSys 2.0 — Magic Engine` VSIX in VS Code.
4. Join the server with the Fabric mod and run `/msf open`.
5. Select **VS Code** in the spell library. This is the Minecraft-side approval action.
6. Run **ArcSys: Connect to Minecraft** and enter the displayed six-digit, single-use code.

## Easy start (alpha.11+)

1. In Minecraft, click **VS Code接続** or **VS Codeかんたん編集**. The six-digit code is copied to the system clipboard automatically.
2. In VS Code, click **ArcSys: かんたん接続** in the status bar or **かんたんスタート** in ArcSys Studio.
3. The extension reads the exact six-digit clipboard value and connects without manual retyping.
4. Choose a starter template, a saved Minecraft spell, or the currently open `.arc` file.
5. Quick Start enables save-time deployment. A starter template is deployed immediately, and subsequent `Ctrl+S` saves update it through Fabric and Paper.

The one-use code, ten-minute expiry, loopback-only listener, Fabric session requirement and Paper-side validation are unchanged. Clipboard text is accepted only when it consists of exactly six digits.

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

Deploy is explicit during normal editing. The Core-scoped direct editing session described below also treats saving its one designated document as an explicit deploy action. Paper recompiles source, rechecks permissions and capabilities, and uses optimistic revisions. Existing activations are not mutated; the new revision becomes available to later activations.

## Direct ARCSYS_CORE editing

Right-click an `ARCSYS_CORE` in the Fabric circuit editor and select `VS Codeで編集`. The editor first saves the circuit, starts a pairing session scoped to that circuit/node pair, and displays the six-digit code. After `ArcSys: Connect to Minecraft`:

- an existing bound program is opened automatically, or a new `.arc` document is prepared for an unbound Core;
- saving that specific document runs server-side validation and deployment;
- a successful deployment is bound back to the selected Core in `LATEST` mode;
- compile, permission, revision or binding failures leave the previous binding unchanged and are shown in `ArcSys / ArcVM` output.

The ARCSYS_CORE control panel opens in **かんたんモード** by default. It presents the workflow as three numbered actions: open VS Code editing, select an existing spell when needed, and use the selected spell on the Core. The mode selectors in the upper-right switch to **アドバンスド**, which preserves revision pinning, unbind, validation, logs and simulation controls.

## Mod内コードエディター

Fabricの回路画面でARCSYS_COREを右クリックし、**Mod内でコード編集**を選ぶと、VS Codeを使わずにArcSysソースを編集できます。既存のCore紐付けがある場合は現在のソースを読み込み、未設定の場合は安全な最小テンプレートを開きます。

- `Ctrl+Enter`: Paper側でコードを検証
- `Ctrl+S`: Paper側で検証・Deployし、成功後に対象Coreへ`LATEST`で紐付け
- `新規`: 新しい最小テンプレートを開始
- `保存済みを開く`: 自分の保存済み術式を読み込む

検証・Deploy・所有権・Capability・欠片要件はVS Code経路と同じくPaper側で再確認されます。

Core-scoped automatic deployment applies only to the document opened by this explicit editing session. Ordinary `.arc` files and normal VS Code connections require the manual deploy command by default, unless the optional setting below is enabled.

## Optional Auto Deploy on Save

For ordinary `.arc` files, click the VS Code status-bar item `ArcSys Auto Deploy: OFF` or run `ArcSys: Toggle Auto Deploy on Save`. When enabled, saving a connected ArcSys document automatically deploys it after a short debounce. The Paper server still recompiles and validates the source. This option does not automatically bind the program to a Core; use the Core-scoped editing flow above when save-and-bind behavior is required.

Auto Deploy is disabled by default and stops immediately when the Fabric bridge disconnects. Failed validation or deployment is reported in `ArcSys / ArcVM` output and does not replace an existing server revision.

## Current alpha boundary

The debugger command runs a bounded server-side simulation and returns the trace/effect requests. Breakpoint suspension, variable watch, graphical graph editing and production combat-effect execution are not yet enabled. The advanced ArcSys fragments therefore remain outside loot and gacha pools.
