import * as path from 'path';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, TransportKind } from 'vscode-languageclient/node';
import { FabricBridge } from './bridge';
import { StudioProvider } from './studio';
import { StudioSnapshot } from './protocol';

let client: LanguageClient | undefined;
let bridge: FabricBridge | undefined;
const output = vscode.window.createOutputChannel('ArcSys / ArcVM');
const spellMetadata = new Map<string, { spellId: string; revision: number }>();

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const serverModule = context.asAbsolutePath(path.join('out', 'server.js'));
  const serverOptions: ServerOptions = { run: { module: serverModule, transport: TransportKind.ipc }, debug: { module: serverModule, transport: TransportKind.ipc } };
  const clientOptions: LanguageClientOptions = { documentSelector: [{ scheme: 'file', language: 'arcsys' }], synchronize: { fileEvents: vscode.workspace.createFileSystemWatcher('**/*.arc') } };
  client = new LanguageClient('arcsysLsp', 'ArcSys Language Server', serverOptions, clientOptions);
  await client.start();

  const studio = new StudioProvider();
  context.subscriptions.push(vscode.window.registerTreeDataProvider('arcsys.studio', studio), output);
  const register = (name: string, handler: () => unknown) => context.subscriptions.push(vscode.commands.registerCommand(name, handler));

  register('arcsys.connect', async () => {
    const token = await vscode.window.showInputBox({ title: 'ArcSys ペアリング', prompt: 'MinecraftのArcSys画面に表示された6桁コード', password: true, validateInput: v => /^\d{6}$/.test(v) ? undefined : '6桁の数字を入力してください' });
    if (!token) return;
    const config = vscode.workspace.getConfiguration('arcsys.bridge');
    bridge = new FabricBridge(config.get('port', 27128), config.get('timeoutMs', 8000));
    await run('接続', async () => { await bridge!.connect(token); await refreshStudio(studio); return 'Minecraftへ接続しました'; });
  });
  register('arcsys.disconnect', () => { bridge?.disconnect(); bridge = undefined; studio.update(); vscode.window.showInformationMessage('ArcSysを切断しました'); });
  register('arcsys.validate', () => sourceRequest('validate'));
  register('arcsys.compile', () => sourceRequest('compile'));
  register('arcsys.deploy', () => sourceRequest('deploy'));
  register('arcsys.simulate', () => sourceRequest('simulate'));
  register('arcsys.debug', () => sourceRequest('debugStart'));
  register('arcsys.openGraph', () => sourceRequest('nodeGraph'));
  register('arcsys.logs', async () => { const result = await requireBridge().request('logs', {}); output.appendLine(JSON.stringify(result, null, 2)); output.show(true); });
  register('arcsys.openSpell', async () => {
    const result = await requireBridge().request('listSpells', {}) as { spells?: Array<{ id: string; name: string }> };
    const selected = await vscode.window.showQuickPick((result.spells ?? []).map(s => ({ label: s.name, id: s.id })), { title: 'Minecraftから開く魔術' });
    if (!selected) return;
    const opened = await requireBridge().request('openSpell', { spellId: selected.id }) as { source: string; revision: number };
    const document = await vscode.workspace.openTextDocument({ language: 'arcsys', content: opened.source });
    spellMetadata.set(document.uri.toString(), { spellId: selected.id, revision: opened.revision });
    await vscode.window.showTextDocument(document);
  });

  async function sourceRequest(type: 'validate' | 'compile' | 'deploy' | 'simulate' | 'debugStart' | 'nodeGraph'): Promise<void> {
    const editor = vscode.window.activeTextEditor;
    if (!editor || editor.document.languageId !== 'arcsys') throw new Error('ArcSys (.arc) ファイルを開いてください');
    const metadata = spellMetadata.get(editor.document.uri.toString());
    const result = await requireBridge().request(type, {
      source: editor.document.getText(), uri: editor.document.uri.toString(), explicit: true,
      spellId: metadata?.spellId, baseRevision: metadata?.revision ?? 0
    }) as { spellId?: string; revision?: number };
    if (type === 'deploy' && result.spellId && result.revision !== undefined)
      spellMetadata.set(editor.document.uri.toString(), { spellId: result.spellId, revision: result.revision });
    output.appendLine(`[${new Date().toISOString()}] ${type}\n${JSON.stringify(result, null, 2)}`); output.show(true);
    vscode.window.showInformationMessage(`ArcSys ${type} 完了`);
    if (type === 'deploy') await refreshStudio(studio);
  }
}

function requireBridge(): FabricBridge { if (!bridge?.connected) throw new Error('先に ArcSys: Connect to Minecraft を実行してください'); return bridge; }
async function refreshStudio(studio: StudioProvider): Promise<void> { studio.update(await requireBridge().request('studioSnapshot', {}) as StudioSnapshot); }
async function run(label: string, action: () => Promise<string>): Promise<void> {
  try { vscode.window.showInformationMessage(await action()); }
  catch (error) { const message = error instanceof Error ? error.message : String(error); output.appendLine(`${label}: ${message}`); vscode.window.showErrorMessage(`ArcSys ${label}失敗: ${message}`); }
}

export async function deactivate(): Promise<void> { bridge?.disconnect(); await client?.stop(); }
