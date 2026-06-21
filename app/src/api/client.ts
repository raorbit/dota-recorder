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
// The OBS connection is app-managed and no longer part of this surface; only the
// recording knobs are exposed. Writes go through PUT /settings as a partial patch
// (see SettingsPatch).
export interface Settings {
  readonly resolution: string;
  readonly encoder: string;
  readonly retentionCapGb: number;
  readonly videoDir: string;
}

// A partial update to Settings. Every field is optional so the renderer can PATCH
// just what changed; the core carries forward any field the patch omits.
export type SettingsPatch = Partial<Settings>;

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

async function patchJson<TBody, TResult>(path: string, body: TBody): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'PATCH',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    throw new Error(`PATCH ${path} failed: ${res.status} ${res.statusText}`);
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
// Settings the core now holds (the OBS connection is app-managed and off-surface).
export function updateSettings(patch: SettingsPatch): Promise<Settings> {
  return putJson<SettingsPatch, Settings>('/settings', patch);
}

export function fetchMatches(): Promise<MatchSummary[]> {
  return getJson<MatchSummary[]>('/matches');
}

// --- match detail / markers / pauses / buckets (data-layer endpoints) -------

// Full row as the core's dev.dotarec.data.MatchSummary record serializes it (every
// matches column). Distinct from the list-card `MatchSummary` above, which is the
// trimmed shape an earlier step sketched for the browse grid. Nullable columns are
// `| null` to mirror SQLite NULLs. `videoPath` is null once retention prunes the row.
export interface MatchDetail {
  readonly id: number;
  readonly dotaMatchId: number | null;
  readonly recordKind: string;
  readonly enrichmentState: string;
  readonly hero: string | null;
  readonly kills: number | null;
  readonly deaths: number | null;
  readonly assists: number | null;
  readonly gpm: number | null;
  readonly xpm: number | null;
  readonly netWorth: number | null;
  readonly lastHits: number | null;
  readonly result: string | null;
  readonly lobbyType: number | null;
  readonly gameMode: number | null;
  readonly rankTier: number | null;
  readonly mmrDelta: number | null;
  readonly durationS: number | null;
  readonly playedAt: number | null;
  readonly videoPath: string | null;
  readonly thumbPath: string | null;
  readonly fileSizeBytes: number | null;
  readonly starred: boolean;
  readonly createdAt: number;
}

// A timeline annotation pinned to a position in the recorded video. `videoOffsetS`
// is seconds from the start of the .mp4 (what the player seeks to); `gameClock` is
// the in-game clock (nullable); `source` is 'gsi' (live) or 'replay' (enriched).
export interface Marker {
  readonly id: number;
  readonly matchId: number;
  readonly type: string;
  readonly videoOffsetS: number;
  readonly gameClock: number | null;
  readonly label: string | null;
  readonly source: string;
}

// A span where the game was paused. Both ends are wall-clock epoch millis;
// `endWall` is null while a pause is still open.
export interface PauseSpan {
  readonly id: number;
  readonly matchId: number;
  readonly startWall: number;
  readonly endWall: number | null;
}

// GET /matches/{id}/video — absolute path + a file:// URL. 404s (throws) when the
// row is unknown or its video was pruned by retention.
export interface VideoLocation {
  readonly matchId: number;
  readonly path: string;
  readonly url: string;
}

// GET /buckets/counts — one count per library bucket. Always all seven keys.
export interface BucketCounts {
  readonly ranked: number;
  readonly unranked: number;
  readonly turbo: number;
  readonly abilityDraft: number;
  readonly manual: number;
  readonly clips: number;
  readonly unsorted: number;
}

export function fetchMatch(id: number): Promise<MatchDetail> {
  return getJson<MatchDetail>(`/matches/${id}`);
}

export function fetchMarkers(id: number): Promise<Marker[]> {
  return getJson<Marker[]>(`/matches/${id}/markers`);
}

// GET /matches/{id}/video — resolves the recorded file as { path, url } where
// `url` is a file:// URI the renderer can drop straight into <video src>. Throws
// (404) when the row has no video_path or retention pruned the file; callers wrap
// it in Promise.allSettled so markers/duration still render without a file.
export function fetchVideo(id: number): Promise<VideoLocation> {
  return getJson<VideoLocation>(`/matches/${id}/video`);
}

export function fetchPauses(id: number): Promise<PauseSpan[]> {
  return getJson<PauseSpan[]>(`/matches/${id}/pauses`);
}

export function fetchBucketCounts(): Promise<BucketCounts> {
  return getJson<BucketCounts>('/buckets/counts');
}

// Toggles the star on a match (PATCH /matches/{id}) and returns the updated row.
export function setStarred(id: number, starred: boolean): Promise<MatchDetail> {
  return patchJson<{ starred: boolean }, MatchDetail>(`/matches/${id}`, { starred });
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
