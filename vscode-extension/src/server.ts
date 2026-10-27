import {
  createConnection, TextDocuments, ProposedFeatures, InitializeParams, InitializeResult,
  TextDocumentSyncKind, Diagnostic, DiagnosticSeverity, CompletionItem, CompletionItemKind,
  Hover, Location, Position, Range, TextEdit
} from 'vscode-languageserver/node';
import { TextDocument } from 'vscode-languageserver-textdocument';

const connection = createConnection(ProposedFeatures.all);
const documents = new TextDocuments(TextDocument);

const apis: Record<string, { detail: string; mana: number; capability: string }> = {
  'mobs.hostile': { detail: '範囲内の敵対Mobを取得します', mana: 4, capability: 'sense.hostile' },
  'target.nearest': { detail: '最も近い対象を取得します', mana: 2, capability: 'sense.target' },
  'spell.cast': { detail: '登録済み術式効果を対象へ要求します', mana: 12, capability: 'spell.cast' },
  'spell.effect': { detail: '安全な効果要求を生成します', mana: 8, capability: 'spell.effect' },
  'world.particle': { detail: '構成に応じたパーティクルを要求します', mana: 2, capability: 'visual.particle' },
  'math.clamp': { detail: '数値を最小値と最大値の範囲へ制限します', mana: 0, capability: 'math' }
};
const keywords = ['spell', 'fn', 'on', 'cast', 'pulse', 'hit', 'kill', 'damage_taken', 'let', 'const', 'state', 'if', 'else', 'for', 'in', 'repeat', 'return', 'true', 'false', 'none'];

connection.onInitialize((_params: InitializeParams): InitializeResult => ({ capabilities: {
  textDocumentSync: TextDocumentSyncKind.Incremental,
  completionProvider: { resolveProvider: false, triggerCharacters: ['.'] },
  hoverProvider: true, definitionProvider: true, documentFormattingProvider: true
} }));

documents.onDidChangeContent(change => validate(change.document));
documents.onDidOpen(change => validate(change.document));

function validate(document: TextDocument): void {
  const text = document.getText();
  const diagnostics: Diagnostic[] = [];
  const stack: Array<{ char: string; offset: number }> = [];
  let inString = false, lineComment = false, blockComment = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i], n = text[i + 1];
    if (lineComment) { if (c === '\n') lineComment = false; continue; }
    if (blockComment) { if (c === '*' && n === '/') { blockComment = false; i++; } continue; }
    if (!inString && c === '/' && n === '/') { lineComment = true; i++; continue; }
    if (!inString && c === '/' && n === '*') { blockComment = true; i++; continue; }
    if (c === '"' && text[i - 1] !== '\\') { inString = !inString; continue; }
    if (inString) continue;
    if ('{(['.includes(c)) stack.push({ char: c, offset: i });
    else if ('})]'.includes(c)) {
      const expected = c === '}' ? '{' : c === ')' ? '(' : '[';
      const open = stack.pop();
      if (!open || open.char !== expected) diagnostics.push(diag(document, i, i + 1, `対応する '${expected}' がありません`, 'ARC-LSP-001'));
    }
  }
  for (const open of stack) diagnostics.push(diag(document, open.offset, open.offset + 1, `'${open.char}' が閉じられていません`, 'ARC-LSP-002'));
  if (inString) diagnostics.push(diag(document, Math.max(0, text.lastIndexOf('"')), text.length, '文字列が閉じられていません', 'ARC-LSP-003'));
  if (Buffer.byteLength(text, 'utf8') > 65_536) diagnostics.push(diag(document, 0, Math.min(text.length, 1), 'ソースが65536バイトを超えています', 'ARC-LSP-004'));
  for (const match of text.matchAll(/\b(?:java|javax|kotlin|ProcessBuilder|Runtime|ClassLoader|reflect|File|Socket)\b/g)) {
    diagnostics.push(diag(document, match.index!, match.index! + match[0].length, 'ArcSysからOS・JVM APIは使用できません', 'ARC-LSP-SECURITY'));
  }
  connection.sendDiagnostics({ uri: document.uri, diagnostics: diagnostics.slice(0, 100) });
}

connection.onCompletion((): CompletionItem[] => [
  ...keywords.map(label => ({ label, kind: CompletionItemKind.Keyword })),
  ...Object.entries(apis).map(([label, api]) => ({ label, kind: CompletionItemKind.Function, detail: `${api.detail} / MP ${api.mana}`, documentation: `Capability: ${api.capability}` }))
]);

connection.onHover(params => {
  const document = documents.get(params.textDocument.uri); if (!document) return null;
  const word = wordAt(document, params.position);
  const api = apis[word];
  if (api) return { contents: { kind: 'markdown', value: `**${word}**\n\n${api.detail}\n\n消費魔力: **${api.mana}**  \nCapability: \`${api.capability}\`` } } as Hover;
  return null;
});

connection.onDefinition(params => {
  const document = documents.get(params.textDocument.uri); if (!document) return null;
  const name = wordAt(document, params.position).split('.').pop()!;
  const regex = new RegExp(`\\b(?:fn|let|const|state)\\s+${escapeRegex(name)}\\b`);
  const match = regex.exec(document.getText());
  return match ? Location.create(document.uri, Range.create(document.positionAt(match.index), document.positionAt(match.index + match[0].length))) : null;
});

connection.onDocumentFormatting(params => {
  const document = documents.get(params.textDocument.uri); if (!document) return [];
  const lines = document.getText().split(/\r?\n/); let indent = 0;
  const formatted = lines.map(line => {
    const trimmed = line.trim(); if (trimmed.startsWith('}')) indent = Math.max(0, indent - 1);
    const result = '  '.repeat(indent) + trimmed;
    if (trimmed.endsWith('{')) indent++;
    return result.replace(/\s+$/g, '');
  }).join('\n');
  return [TextEdit.replace(Range.create(Position.create(0, 0), document.positionAt(document.getText().length)), formatted)];
});

function wordAt(document: TextDocument, position: Position): string {
  const offset = document.offsetAt(position), text = document.getText();
  let start = offset, end = offset;
  while (start > 0 && /[A-Za-z0-9_.]/.test(text[start - 1])) start--;
  while (end < text.length && /[A-Za-z0-9_.]/.test(text[end])) end++;
  return text.slice(start, end);
}
function diag(document: TextDocument, start: number, end: number, message: string, code: string): Diagnostic {
  return { severity: DiagnosticSeverity.Error, range: Range.create(document.positionAt(start), document.positionAt(end)), message, code, source: 'ArcSys' };
}
function escapeRegex(value: string): string { return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

documents.listen(connection);
connection.listen();
