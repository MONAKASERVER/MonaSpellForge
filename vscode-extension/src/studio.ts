import * as vscode from 'vscode';
import { StudioSnapshot } from './protocol';

export class StudioProvider implements vscode.TreeDataProvider<StudioItem> {
  private readonly changed = new vscode.EventEmitter<StudioItem | undefined>();
  readonly onDidChangeTreeData = this.changed.event;
  private snapshot?: StudioSnapshot;

  update(snapshot?: StudioSnapshot): void { this.snapshot = snapshot; this.changed.fire(undefined); }
  getTreeItem(element: StudioItem): vscode.TreeItem { return element; }

  getChildren(element?: StudioItem): StudioItem[] {
    if (!this.snapshot) return [new StudioItem('Minecraft未接続', 'ArcSys: Connect to Minecraft を実行してください', vscode.TreeItemCollapsibleState.None)];
    if (!element) return [
      new StudioItem(`接続: ${this.snapshot.connectedServer ?? 'unknown'}`, this.snapshot.player ?? '', vscode.TreeItemCollapsibleState.None),
      StudioItem.group('保存済み魔術', 'spells'), StudioItem.group('所有欠片', 'fragments'),
      StudioItem.group('術式演算核', 'cores'), StudioItem.group('使用可能API', 'apis'), StudioItem.group('デプロイ履歴', 'history')
    ];
    switch (element.contextValue) {
      case 'spells': return this.snapshot.spells.map(s => new StudioItem(`${s.active ? '●' : '○'} ${s.name}`, `rev ${s.revision}`, vscode.TreeItemCollapsibleState.None));
      case 'fragments': return this.snapshot.fragments.map(f => new StudioItem(f.id, `×${f.count}`, vscode.TreeItemCollapsibleState.None));
      case 'cores': return (this.snapshot.cores ?? []).map(c => new StudioItem(`ARCSYS_CORE Lv.${c.level} — ${c.program}`, `rev ${c.revision} / ${c.mode} / ${c.status}`, vscode.TreeItemCollapsibleState.None));
      case 'apis': return this.snapshot.apis.map(a => new StudioItem(a.name, `MP ${a.mana} / ${a.capability}`, vscode.TreeItemCollapsibleState.None));
      case 'history': return this.snapshot.deployHistory.map(h => new StudioItem(`${h.status} ${h.spell}`, `rev ${h.revision} — ${h.at}`, vscode.TreeItemCollapsibleState.None));
      default: return [];
    }
  }
}

export class StudioItem extends vscode.TreeItem {
  constructor(label: string, description: string, state: vscode.TreeItemCollapsibleState) {
    super(label, state); this.description = description;
  }
  static group(label: string, contextValue: string): StudioItem {
    const item = new StudioItem(label, '', vscode.TreeItemCollapsibleState.Collapsed); item.contextValue = contextValue; return item;
  }
}
