import * as path from 'path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from 'vscode-languageclient/node';
import { FabricBridge } from './bridge';
import { StudioProvider } from './studio';
import { CoreEditorTarget, StudioSnapshot } from './protocol';

let client: LanguageClient | undefined;
let bridge: FabricBridge | undefined;
const output = vscode.window.createOutputChannel('ArcSys / ArcVM');
const spellMetadata = new Map<string, { spellId: string; revision: number }>();
const spellDocumentMetadata = new WeakMap<vscode.TextDocument, { spellId: string; revision: number }>();
const generalDeployTimers = new Map<string, NodeJS.Timeout>();
let debugSessionId: string | undefined;
let autoCoreSession: { target: CoreEditorTarget; document: vscode.TextDocument; spellId?: string; revision?: number } | undefined;
let autoDeploying = false;
let autoDeployQueued = false;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const serverModule = context.asAbsolutePath(path.join('out', 'server.js'));
  const serverOptions: ServerOptions = { run: { module: serverModule, transport: TransportKind.ipc }, debug: { module: serverModule, transport: TransportKind.ipc } };
  const clientOptions: LanguageClientOptions = { documentSelector: [{ scheme: 'file', language: 'arcsys' }], synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/*.arc') } };
  client = new LanguageClient('arcsysLsp', 'ArcSys Language Server', serverOptions, clientOptions);
  await client.start();

  const studio = new StudioProvider();
  const autoDeployStatus = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 90);
  autoDeployStatus.command = 'arcsys.toggleAutoDeploy';
  const refreshAutoDeployStatus = () => {
    const enabled = vscode.workspace.getConfiguration('arcsys').get('autoDeployOnSave', false);
    autoDeployStatus.text = enabled ? '$(cloud-upload) ArcSys Auto Deploy: ON' : '$(cloud-upload) ArcSys Auto Deploy: OFF';
    autoDeployStatus.tooltip = enabled ? '通常の.arc保存をMinecraftへ自動デプロイします' : 'クリックして通常の.arc自動デプロイを有効化';
    autoDeployStatus.show();
  };
  context.subscriptions.push(vscode.window.registerTreeDataProvider('arcsys.studio', studio), output, autoDeployStatus);
  const register = (name: string, handler: () => unknown) => context.subscriptions.push(vscode.commands.registerCommand(name, handler));
  register('arcsys.toggleAutoDeploy', async () => {
    const config = vscode.workspace.getConfiguration('arcsys');
    const next = !config.get('autoDeployOnSave', false);
    await config.update('autoDeployOnSave', next, vscode.ConfigurationTarget.Global);
    refreshAutoDeployStatus();
    vscode.window.showInformationMessage(`ArcSys Auto Deployを${next ? '有効' : '無効'}にしました`);
  });
  context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(event => {
    if (event.affectsConfiguration('arcsys.autoDeployOnSave')) refreshAutoDeployStatus();
  }));
  refreshAutoDeployStatus();

  register('arcsys.connect', async () => {
    const token = await vscode.window.showInputBox({ title: 'ArcSys ペアリング', prompt: 'MinecraftのArcSys画面に表示された6桁コード', password: true, validateInput: v => /^\d{6}$/.test(v) ? undefined : '6桁の数字を入力してください' });
    if (!token) return;
    const config = vscode.workspace.getConfiguration('arcsys.bridge');
    bridge = new FabricBridge(config.get('port', 27128), config.get('timeoutMs', 8000));
    await run('接続', async () => {
      const hello = await bridge!.connect(token);
      await refreshStudio(studio);
      if (hello.coreTarget?.autoDeployOnSave) await openCoreEditorSession(hello.coreTarget);
      return hello.coreTarget ? 'Minecraftへ接続しました。このファイルを保存すると選択中のCoreへ自動反映されます' : 'Minecraftへ接続しました';
    });
  });
  register('arcsys.disconnect', () => {
    bridge?.disconnect(); bridge = undefined; autoCoreSession = undefined;
    for (const timer of generalDeployTimers.values()) clearTimeout(timer);
    generalDeployTimers.clear();
    studio.update(); vscode.window.showInformationMessage('ArcSysを切断しました');
  });
  register('arcsys.validate', () => sourceRequest('validate'));
  register('arcsys.compile', () => sourceRequest('compile'));
  register('arcsys.deploy', () => sourceRequest('deploy'));
  register('arcsys.simulate', () => sourceRequest('simulate'));
  register('arcsys.profile', () => sourceRequest('profile'));
  register('arcsys.debug', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'arcsys') throw new Error('ArcSys (.arc) ファイルを開いてください');
    const watchesRaw = await vscode.window.showInputBox({ title: 'ArcSys Watch', prompt: '監視する変数名（カンマ区切り、省略可）' });
    const result = await requireBridge().request('debugStart', {
      ...(await projectPayload(editor.document)), watches: (watchesRaw ?? '').split(',').map(v => v.trim()).filter(Boolean), breakpoints: []
    }) as { debugSessionId?: string };
    debugSessionId = result.debugSessionId; showDebugResult('start', result);
  });
  register('arcsys.debugContinue', () => continueDebug('continue'));
  register('arcsys.debugStep', () => continueDebug('step'));
  register('arcsys.debugStop', async () => {
    if (!debugSessionId) throw new Error('実行中のArcSysデバッグセッションがありません');
    const result = await requireBridge().request('debugStop', { debugSessionId });
    showDebugResult('stop', result); debugSessionId = undefined;
  });
  register('arcsys.openGraph', () => sourceRequest('nodeGraph'));
  register('arcsys.logs', async () => { const result = await requireBridge().request('logs', {}); output.appendLine(JSON.stringify(result, null, 2)); output.show(true); });
  register('arcsys.listCores', async () => showCoreResult('list', await requireBridge().request('listCores', {})));
  register('arcsys.inspectCore', async () => {
    const core = await pickCore(); if (!core) return;
    showCoreResult('inspect', await requireBridge().request('inspectCore', { circuitId: core.circuitId, nodeId: core.nodeId }));
  });
  register('arcsys.validateCoreBinding', async () => {
    const core = await pickCore(); if (!core) return;
    showCoreResult('validate', await requireBridge().request('validateCoreBinding', { circuitId: core.circuitId, nodeId: core.nodeId }));
  });
  register('arcsys.unbindCore', async () => {
    const core = await pickCore(); if (!core) return;
    await requireBridge().request('unbindCore', { circuitId: core.circuitId, nodeId: core.nodeId });
    vscode.window.showInformationMessage('ARCSYS_COREの紐付けを解除しました'); await refreshStudio(studio);
  });
  register('arcsys.bindCore', async () => {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'arcsys') throw new Error('紐付けるArcSys (.arc) ファイルを開いてください');
    const metadata = getSpellMetadata(editor.document);
    if (!metadata) throw new Error('先にArcSys: Deploy Spell to Minecraftを実行してください');
    const result = await requireBridge().request('listCores', {}) as { cores?: CoreInfo[] };
    const core = await vscode.window.showQuickPick((result.cores ?? []).map(value => ({
      label: `${value.circuit} / ARCSYS_CORE Lv.${value.level}`, description: value.nodeId, value
    })), { title: '紐付ける術式演算核' });
    if (!core) return;
    const mode = await vscode.window.showQuickPick(['PINNED', 'LATEST'] as const, { title: 'Revision方式' });
    if (!mode) return;
    const bound = await requireBridge().request('bindCore', { circuitId: core.value.circuitId, nodeId: core.value.nodeId,
      spellId: metadata.spellId, revision: metadata.revision, mode });
    showCoreResult('bind', bound); await refreshStudio(studio);
  });
  register('arcsys.openSpell', async () => {
    const result = await requireBridge().request('listSpells', {}) as { spells?: Array<{ id: string; name: string }> };
    const selected = await vscode.window.showQuickPick((result.spells ?? []).map(s => ({ label: s.name, id: s.id })), { title: 'Minecraftから開く魔術' });
    if (!selected) return;
    const opened = await requireBridge().request('openSpell', { spellId: selected.id }) as { source: string; revision: number };
    const document = await vscode.workspace.openTextDocument({ language: 'arcsys', content: opened.source });
    setSpellMetadata(document, { spellId: selected.id, revision: opened.revision });
    await vscode.window.showTextDocument(document);
  });

  context.subscriptions.push(vscode.workspace.onDidSaveTextDocument(document => {
    if (document.languageId !== 'arcsys') return;
    if (autoCoreSession?.document === document) {
      void queueAutoDeploy(document, studio);
      return;
    }
    if (bridge?.connected && vscode.workspace.getConfiguration('arcsys').get('autoDeployOnSave', false))
      scheduleGeneralAutoDeploy(document, document.getText(), studio);
  }));

  async function openCoreEditorSession(target: CoreEditorTarget): Promise<void> {
    const core = await requireBridge().request('inspectCore', { circuitId: target.circuitId, nodeId: target.nodeId }) as CoreInfo & { spellId?: string; revision?: number; mode?: string };
    const existingSpellId = core.spellId;
    const hasSpell = existingSpellId !== undefined && existingSpellId !== '00000000-0000-0000-0000-000000000000' && core.status !== 'UNBOUND';
    let document: vscode.TextDocument;
    let spellId: string | undefined;
    let revision: number | undefined;
    if (hasSpell) {
      const opened = await requireBridge().request('openSpell', { spellId: existingSpellId }) as { source: string; revision: number };
      document = await vscode.workspace.openTextDocument({ language: 'arcsys', content: opened.source });
      spellId = existingSpellId;
      revision = opened.revision;
      setSpellMetadata(document, { spellId: existingSpellId, revision });
    } else {
      const suffix = target.nodeId.replaceAll('-', '').slice(0, 8);
      document = await vscode.workspace.openTextDocument({ language: 'arcsys', content:
        `spell Core_${suffix} {\n  on cast {\n    return true;\n  }\n}\n` });
    }
    autoCoreSession = { target, document, spellId, revision };
    await vscode.window.showTextDocument(document);
    vscode.window.showInformationMessage('ARCSYS_CORE編集セッションを開始しました。Ctrl+Sで検証・デプロイ・紐付けを自動実行します');
  }

  async function queueAutoDeploy(document: vscode.TextDocument, provider: StudioProvider): Promise<void> {
    if (autoDeploying) { autoDeployQueued = true; return; }
    autoDeploying = true;
    try {
      do {
        autoDeployQueued = false;
        await autoDeployAndBind(document, provider);
      } while (autoDeployQueued && autoCoreSession?.document === document);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      output.appendLine(`[${new Date().toISOString()}] core:auto-save failed\n${message}`);
      output.show(true);
      vscode.window.showErrorMessage(`ARCSYS_COREへの自動反映に失敗しました: ${message}`);
    } finally {
      autoDeploying = false;
    }
  }

  async function autoDeployAndBind(document: vscode.TextDocument, provider: StudioProvider): Promise<void> {
    const session = autoCoreSession;
    if (!session || session.document !== document) return;
    const result = await requireBridge().request('deploy', {
      ...(await projectPayload(document)), uri: document.uri.toString(), explicit: true,
      spellId: session.spellId, baseRevision: session.revision ?? 0
    }) as { spellId?: string; revision?: number };
    if (!result.spellId || result.revision === undefined) throw new Error('デプロイ結果に術式IDまたはRevisionがありません');
    session.spellId = result.spellId;
    session.revision = result.revision;
    setSpellMetadata(document, { spellId: result.spellId, revision: result.revision });
    const bound = await requireBridge().request('bindCore', {
      circuitId: session.target.circuitId, nodeId: session.target.nodeId,
      spellId: result.spellId, revision: result.revision, mode: 'LATEST'
    });
    output.appendLine(`[${new Date().toISOString()}] core:auto-save\n${JSON.stringify({ deploy: result, bind: bound }, null, 2)}`);
    output.show(true);
    await refreshStudio(provider);
    vscode.window.showInformationMessage(`ARCSYS_COREへ自動反映しました（Revision ${result.revision}）`);
  }

  function scheduleGeneralAutoDeploy(document: vscode.TextDocument, source: string, provider: StudioProvider): void {
    const key = document.uri.toString();
    const existing = generalDeployTimers.get(key);
    if (existing) clearTimeout(existing);
    generalDeployTimers.set(key, setTimeout(() => {
      generalDeployTimers.delete(key);
      void generalAutoDeploy(document, source, provider);
    }, 650));
  }

  async function generalAutoDeploy(document: vscode.TextDocument, source: string, provider: StudioProvider): Promise<void> {
    try {
      if (!bridge?.connected || !vscode.workspace.getConfiguration('arcsys').get('autoDeployOnSave', false)) return;
      const metadata = getSpellMetadata(document);
      const result = await requireBridge().request('deploy', {
        ...(await projectPayload(document, source)), uri: document.uri.toString(), explicit: true,
        spellId: metadata?.spellId, baseRevision: metadata?.revision ?? 0
      }) as { spellId?: string; revision?: number };
      if (!result.spellId || result.revision === undefined) throw new Error('デプロイ結果に術式IDまたはRevisionがありません');
      setSpellMetadata(document, { spellId: result.spellId, revision: result.revision });
      output.appendLine(`[${new Date().toISOString()}] auto-deploy ${document.fileName}\n${JSON.stringify(result, null, 2)}`);
      output.show(true);
      await refreshStudio(provider);
      vscode.window.setStatusBarMessage(`$(cloud-upload) ArcSys Revision ${result.revision} を自動デプロイしました`, 4000);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      output.appendLine(`[${new Date().toISOString()}] auto-deploy failed ${document.fileName}\n${message}`);
      output.show(true);
      vscode.window.showErrorMessage(`ArcSys Auto Deploy失敗: ${message}`);
    }
  }

  async function sourceRequest(type: 'validate' | 'compile' | 'deploy' | 'simulate' | 'profile' | 'debugStart' | 'nodeGraph'): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'arcsys') throw new Error('ArcSys (.arc) ファイルを開いてください');
    const metadata = getSpellMetadata(editor.document);
    const result = await requireBridge().request(type, {
      ...(await projectPayload(editor.document)), uri: editor.document.uri.toString(), explicit: true,
      spellId: metadata?.spellId, baseRevision: metadata?.revision ?? 0
    }) as { spellId?: string; revision?: number };
    if (type === 'deploy' && result.spellId && result.revision !== undefined)
      setSpellMetadata(editor.document, { spellId: result.spellId, revision: result.revision });
    output.appendLine(`[${new Date().toISOString()}] ${type}\n${JSON.stringify(result, null, 2)}`); output.show(true);
    vscode.window.showInformationMessage(`ArcSys ${type} 完了`);
    if (type === 'deploy') await refreshStudio(studio);
  }

  async function projectPayload(document: vscode.TextDocument, savedSource?: string): Promise<Record<string, unknown>> {
    const source = savedSource ?? document.getText();
    const rootModule = moduleName(source) ?? path.basename(document.fileName, '.arc');
    if (document.uri.scheme !== 'file' || !/\buse\s+[A-Za-z_][\w.]*/.test(source)) return { source };
    const modules: Record<string, string> = {};
    const files = await vscode.workspace.findFiles('**/*.arc', '**/{node_modules,.git,build,out}/**', 32);
    for (const uri of files) {
      const candidate = await vscode.workspace.openTextDocument(uri); const text = candidate.getText();
      const name = moduleName(text);
      if (name) modules[name] = text;
    }
    modules[rootModule] = source;
    const payload = { source, rootModule, modules };
    if (Buffer.byteLength(JSON.stringify(payload), 'utf8') > 55_000)
      throw new Error('ArcSys projectがFabric Bridgeの転送上限を超えています');
    return payload;
  }

  function moduleName(source: string): string | undefined {
    return /^\s*module\s+([A-Za-z_][\w]*(?:\.[A-Za-z_][\w]*)*)\s*;/m.exec(source)?.[1];
  }

  async function continueDebug(mode: 'continue' | 'step'): Promise<void> {
    if (!debugSessionId) throw new Error('ArcSys: Start Debuggingを先に実行してください');
    const result = await requireBridge().request('debugContinue', { debugSessionId, mode }) as { status?: string };
    showDebugResult(mode, result);
    if (result.status === 'COMPLETED' || result.status === 'TRAPPED' || result.status === 'STOPPED') debugSessionId = undefined;
  }

  async function pickCore(): Promise<CoreInfo | undefined> {
    const result = await requireBridge().request('listCores', {}) as { cores?: CoreInfo[] };
    const selected = await vscode.window.showQuickPick((result.cores ?? []).map(value => ({
      label: `${value.circuit} / ${value.program}`, description: `${value.status} · ${value.nodeId}`, value
    })), { title: '術式演算核を選択' });
    return selected?.value;
  }
}

interface CoreInfo { circuitId: string; nodeId: string; circuit: string; program: string; level: number; status: string }
function showCoreResult(action: string, result: unknown): void {
  output.appendLine(`[${new Date().toISOString()}] core:${action}\n${JSON.stringify(result, null, 2)}`); output.show(true);
}

function requireBridge(): FabricBridge { if (!bridge?.connected) throw new Error('先に ArcSys: Connect to Minecraft を実行してください'); return bridge; }
function setSpellMetadata(document: vscode.TextDocument, metadata: { spellId: string; revision: number }): void {
  spellDocumentMetadata.set(document, metadata);
  spellMetadata.set(document.uri.toString(), metadata);
}
function getSpellMetadata(document: vscode.TextDocument): { spellId: string; revision: number } | undefined {
  return spellDocumentMetadata.get(document) ?? spellMetadata.get(document.uri.toString());
}
async function refreshStudio(studio: StudioProvider): Promise<void> { studio.update(await requireBridge().request('studioSnapshot', {}) as StudioSnapshot); }
async function run(label: string, action: () => Promise<string>): Promise<void> {
  try { vscode.window.showInformationMessage(await action()); }
  catch (error) { const message = error instanceof Error ? error.message : String(error); output.appendLine(`${label}: ${message}`); vscode.window.showErrorMessage(`ArcSys ${label}失敗: ${message}`); }
}

function showDebugResult(action: string, result: unknown): void {
  output.appendLine(`[${new Date().toISOString()}] debug:${action}\n${JSON.stringify(result, null, 2)}`);
  output.show(true);
}

export async function deactivate(): Promise<void> {
  for (const timer of generalDeployTimers.values()) clearTimeout(timer);
  generalDeployTimers.clear();
  bridge?.disconnect();
  await client?.stop();
}
