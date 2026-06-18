// Minimal typed client for the local core bridge: REST over fetch plus a
// WebSocket client with exponential-backoff reconnect.
//
// Endpoints resolve from the preload-exposed bridge when present (packaged /
// Electron), falling back to the known loopback defaults (dev / browser).
const DEFAULT_BRIDGE_BASE = 'http://127.0.0.1:3224';
const DEFAULT_WS_URL = 'ws://127.0.0.1:3224/ws';

function bridgeBase(): string {
  return window.dotarec?.bridgeBase ?? DEFAULT_BRIDGE_BASE;
}

function wsUrl(): string {
  return window.dotarec?.wsUrl ?? DEFAULT_WS_URL;
}

export interface Health {
  readonly status: 'ok' | string;
  readonly version: string;
  readonly dbReady: boolean;
  readonly schemaVersion: number;
}

// Wire shape of a `status` frame's payload. Mirrors dev.dotarec.bridge.StatusSnapshot
// on the core; keep the two in sync.
export interface StatusSnapshot {
  readonly gsi: {
    readonly connected: boolean;
    readonly lastFrameAgoMs: number | null;
  };
  readonly obs: {
    readonly connected: boolean;
    readonly sceneActive: boolean;
    readonly recording: boolean;
  };
  readonly fsm: {
    readonly state: string;
    readonly activeMatchId: number | null;
  };
}

// Flattened live status delivered to onStatus() listeners. Derived from a
// StatusSnapshot; the raw snapshot is kept under `snapshot` for fuller views.
export interface Status {
  readonly fsmState: string;
  readonly matchId: number | null;
  readonly recording: boolean;
  readonly gsiConnected: boolean;
  readonly snapshot: StatusSnapshot;
}

// Every /ws frame is a typed envelope: { type, payload }. The renderer routes by
// `type` and ignores unknown types so new server event kinds don't break old clients.
interface WsEnvelope {
  readonly type: string;
  readonly payload: unknown;
}

function toStatus(snapshot: StatusSnapshot): Status {
  return {
    fsmState: snapshot.fsm.state,
    matchId: snapshot.fsm.activeMatchId,
    recording: snapshot.obs.recording,
    gsiConnected: snapshot.gsi.connected,
    snapshot,
  };
}

// Mirrors the matches table; populated in a later step.
export interface MatchSummary {
  readonly matchId: number;
  readonly category: string;
  readonly hero: string;
  readonly result: 'win' | 'loss' | string;
  readonly kills: number;
  readonly deaths: number;
  readonly assists: number;
  readonly playedAt: string;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    headers: { Accept: 'application/json' },
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export function fetchHealth(): Promise<Health> {
  return getJson<Health>('/health');
}

export function fetchMatches(): Promise<MatchSummary[]> {
  return getJson<MatchSummary[]>('/matches');
}

export type StatusListener = (status: Status) => void;
export type StateListener = (connected: boolean) => void;

/**
 * WebSocket client to the core's /ws endpoint with exponential-backoff reconnect.
 * Step 0: connects and forwards parsed JSON frames; full status schema is wired
 * in a later step.
 */
export class StatusSocket {
  private socket: WebSocket | null = null;
  private closed = false;
  private backoffMs = 500;
  private readonly maxBackoffMs = 10_000;
  private readonly statusListeners = new Set<StatusListener>();
  private readonly stateListeners = new Set<StateListener>();

  connect(): void {
    this.closed = false;
    this.open();
  }

  close(): void {
    this.closed = true;
    this.socket?.close();
    this.socket = null;
  }

  onStatus(listener: StatusListener): () => void {
    this.statusListeners.add(listener);
    return () => this.statusListeners.delete(listener);
  }

  onConnectionChange(listener: StateListener): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  private open(): void {
    const ws = new WebSocket(wsUrl());
    this.socket = ws;

    ws.onopen = () => {
      this.backoffMs = 500;
      this.emitState(true);
    };

    ws.onmessage = (event: MessageEvent<string>) => {
      let envelope: WsEnvelope;
      try {
        envelope = JSON.parse(event.data) as WsEnvelope;
      } catch {
        return; // ignore malformed (non-JSON) frames
      }
      // Route by type; unknown types are intentionally ignored so the client
      // tolerates server event kinds added in later steps.
      if (envelope?.type === 'status') {
        const status = toStatus(envelope.payload as StatusSnapshot);
        for (const listener of this.statusListeners) listener(status);
      }
    };

    ws.onclose = () => {
      this.emitState(false);
      this.scheduleReconnect();
    };

    ws.onerror = () => {
      ws.close();
    };
  }

  private scheduleReconnect(): void {
    if (this.closed) return;
    const delay = this.backoffMs;
    this.backoffMs = Math.min(this.backoffMs * 2, this.maxBackoffMs);
    setTimeout(() => {
      if (!this.closed) this.open();
    }, delay);
  }

  private emitState(connected: boolean): void {
    for (const listener of this.stateListeners) listener(connected);
  }
}
