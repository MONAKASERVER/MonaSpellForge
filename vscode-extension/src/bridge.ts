import WebSocket from 'ws';
import { randomUUID } from 'crypto';
import { BridgeHello, BridgeRequest, BridgeRequestType, BridgeResponse, MAX_MESSAGE_BYTES } from './protocol';

export class FabricBridge {
  private socket?: WebSocket;
  private token?: string;
  private readonly pending = new Map<string, { resolve: (value: unknown) => void; reject: (reason: Error) => void; timer: NodeJS.Timeout }>();

  constructor(private readonly port: number, private readonly timeoutMs: number) {}

  get connected(): boolean { return this.socket?.readyState === WebSocket.OPEN; }

  async connect(token: string): Promise<BridgeHello> {
    if (!/^\d{6}$/.test(token)) throw new Error('ペアリングコードは6桁です');
    this.disconnect();
    this.token = token;
    const socket = new WebSocket(`ws://127.0.0.1:${this.port}/arcsys`, { maxPayload: MAX_MESSAGE_BYTES });
    this.socket = socket;
    socket.on('message', data => this.onMessage(data));
    socket.on('close', () => this.rejectAll(new Error('Fabric Modとの接続が切れました')));
    socket.on('error', error => this.rejectAll(error));
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Fabric Mod bridgeへの接続がタイムアウトしました')), this.timeoutMs);
      socket.once('open', () => { clearTimeout(timer); resolve(); });
      socket.once('error', error => { clearTimeout(timer); reject(error); });
    });
    return await this.request('hello', { client: 'vscode', clientVersion: '2.0.0-alpha.9' }) as BridgeHello;
  }

  request(type: BridgeRequestType, payload: Record<string, unknown>): Promise<unknown> {
    if (!this.connected || !this.socket) return Promise.reject(new Error('Minecraft Fabric Modに接続されていません'));
    const id = randomUUID();
    const request: BridgeRequest = { version: 1, id, type, token: this.token, payload };
    const encoded = JSON.stringify(request);
    if (Buffer.byteLength(encoded, 'utf8') > MAX_MESSAGE_BYTES) return Promise.reject(new Error('送信サイズが上限を超えています'));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${type} がタイムアウトしました`)); }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      this.socket!.send(encoded);
    });
  }

  disconnect(): void {
    this.socket?.close(1000, 'ArcSys user disconnect');
    this.socket = undefined;
    this.token = undefined;
    this.rejectAll(new Error('切断しました'));
  }

  private onMessage(data: WebSocket.RawData): void {
    const byteLength = Array.isArray(data) ? data.reduce((sum, part) => sum + part.byteLength, 0) : data.byteLength;
    if (byteLength > MAX_MESSAGE_BYTES) { this.disconnect(); return; }
    let response: BridgeResponse;
    try { response = JSON.parse(data.toString()) as BridgeResponse; }
    catch { this.disconnect(); return; }
    if (response.version !== 1 || typeof response.id !== 'string') return;
    const pending = this.pending.get(response.id);
    if (!pending) return;
    clearTimeout(pending.timer);
    this.pending.delete(response.id);
    if (response.ok) pending.resolve(response.result);
    else pending.reject(new Error(`${response.error?.code ?? 'ARC-BRIDGE'}: ${response.error?.message ?? '要求が拒否されました'}`));
  }

  private rejectAll(error: Error): void {
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
  }
}
