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

// User-editable configuration mirrored from the core's config/SettingsStore.
// The raw OBS password is NEVER returned by the core; `obsPasswordSet` is the
// only signal the renderer gets about whether a secret is stored. Writes go
// through PUT /settings as a partial patch (see SettingsPatch).
export interface Settings {
  readonly resolution: string;
  readonly encoder: string;
  readonly retentionCapGb: number;
  readonly videoDir: string;
  readonly obsHost: string;
  readonly obsPort: number;
  readonly obsPasswordSet: boolean;
}

// A partial update to Settings. Every field is optional so the renderer can PATCH
// just what changed. `obsPassword` is write-only: it is accepted here but never
// echoed back in Settings (the core reports only `obsPasswordSet`). Omit it to
// leave the stored secret untouched; send an empty string to clear it.
export type SettingsPatch = Partial<Omit<Settings, 'obsPasswordSet'>> & {
  readonly obsPassword?: string;
};

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

async function putJson<TBody, TResult>(path: string, body: TBody): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    throw new Error(`PUT ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as TResult;
}

export function fetchHealth(): Promise<Health> {
  return getJson<Health>('/health');
}

// One-shot poll of the live status snapshot (GET /status). The StatusSocket is
// the primary live feed; this is for views that mount independently of the socket
// or want an immediate value before the first frame arrives.
export function fetchStatus(): Promise<StatusSnapshot> {
  return getJson<StatusSnapshot>('/status');
}

export function fetchSettings(): Promise<Settings> {
  return getJson<Settings>('/settings');
}

// Applies a partial settings patch via PUT /settings and returns the updated
// (password-redacted) Settings the core now holds.
export function updateSettings(patch: SettingsPatch): Promise<Settings> {
  return putJson<SettingsPatch, Settings>('/settings', patch);
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
