export const MAX_MESSAGE_BYTES = 65_536;

export type BridgeRequestType =
  | 'hello' | 'listSpells' | 'openSpell' | 'compile' | 'validate'
  | 'deploy' | 'simulate' | 'profile' | 'nodeGraph' | 'debugStart' | 'debugContinue' | 'debugState' | 'debugStop' | 'logs' | 'studioSnapshot'
  | 'listCores' | 'inspectCore' | 'bindCore' | 'unbindCore' | 'validateCoreBinding' | 'simulateCore';

export interface BridgeRequest {
  version: 1;
  id: string;
  type: BridgeRequestType;
  token?: string;
  payload: Record<string, unknown>;
}

export interface BridgeResponse {
  version: 1;
  id: string;
  ok: boolean;
  result?: unknown;
  error?: { code: string; message: string };
}

export interface StudioSnapshot {
  connectedServer?: string;
  player?: string;
  fragments: Array<{ id: string; count: number }>;
  spells: Array<{ id: string; name: string; revision: number; active: boolean }>;
  apis: Array<{ name: string; mana: number; capability: string }>;
  deployHistory: Array<{ spell: string; revision: number; status: string; at: string }>;
  cores: Array<{ bindingId: string; circuitId: string; nodeId: string; spellId: string; program: string;
    circuit: string; revision: number; mode: 'PINNED' | 'LATEST'; level: number; status: string }>;
}
