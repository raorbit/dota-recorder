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

// The bridge requires a per-launch shared secret (see the core's BridgeAuthFilter).
// The preload injects it as `bridgeToken`; REST calls send it as a header and the
// WebSocket handshake carries it as a query param (handshakes can't set headers).
// Absent outside Electron (plain browser dev), where the core runs auth-disabled.
const TOKEN_HEADER = 'X-Dotarec-Token';

function authHeaders(): Record<string, string> {
  const token = window.dotarec?.bridgeToken;
  return token ? { [TOKEN_HEADER]: token } : {};
}

function wsUrl(): string {
  const base = window.dotarec?.wsUrl ?? DEFAULT_WS_URL;
  const token = window.dotarec?.bridgeToken;
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
}

// URL the renderer drops straight into <video src> for playback + seeking.
// Points at the authed loopback streaming endpoint (GET /matches/{id}/video/stream),
// which serves the VOD bytes with HTTP Range support. A <video> element can't send
// the X-Dotarec-Token header, so — exactly like wsUrl() — the token rides the query
// string (BridgeAuthFilter accepts ?token= on any gated path). Absent outside
// Electron (browser dev), where the core runs auth-disabled.
export function videoStreamUrl(id: number): string {
  const base = `${bridgeBase()}/matches/${id}/video/stream`;
  const token = window.dotarec?.bridgeToken;
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
}

// Playback URL for a generated clip's .mp4 (GET /clips/{id}/video/stream). Mirrors
// videoStreamUrl(): a <video> element can't carry the X-Dotarec-Token header, so the
// token rides the query string (BridgeAuthFilter accepts ?token= on any gated path).
export function clipStreamUrl(clipId: number): string {
  const base = `${bridgeBase()}/clips/${clipId}/video/stream`;
  const token = window.dotarec?.bridgeToken;
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
}

// Thumbnail image URL for a clip (GET /clips/{id}/thumb). Mirrors clipStreamUrl():
// an <img src> can't carry the X-Dotarec-Token header, so the token rides the
// ?token= query param (BridgeAuthFilter accepts it on any gated path). The endpoint
// 404s when the thumbnail isn't rendered yet / the file is gone, so callers should
// render it through an <img onError> fallback. Absent token outside Electron.
export function clipThumbUrl(clipId: number): string {
  const base = `${bridgeBase()}/clips/${clipId}/thumb`;
  const token = window.dotarec?.bridgeToken;
  return token ? `${base}?token=${encodeURIComponent(token)}` : base;
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

// One audio source captured into the recording. FROZEN wire shape, serialized
// identically by the core (Java record, Jackson) and here — field names/order are
// verbatim. `id` is a stable UUID used as both the React key and the OBS input-name
// suffix (never null/empty). `kind` selects the WASAPI capture kind. `target` is the
// OBS itemValue selecting the device/process (a WASAPI device_id or the literal
// "default" for output/input; an encoded window string like "::dota2.exe" for
// application); null means "use default". `label` is display-only. `volume` is a
// 0..100 UI percent (the core maps to an OBS linear multiplier). `muted` mutes it.
export type AudioSourceKind = 'application' | 'output' | 'input';

export interface AudioSource {
  readonly id: string;
  readonly kind: AudioSourceKind;
  readonly target: string | null;
  readonly label: string;
  readonly volume: number;
  readonly muted: boolean;
}

// User-editable configuration mirrored from the core's config/SettingsStore.
// The OBS connection is app-managed and no longer part of this surface; only the
// recording knobs are exposed. Writes go through PUT /settings as a partial patch
// (see SettingsPatch).
// One archive storage location (a slower/larger drive). FROZEN wire shape, serialized
// identically by the core (Java record) and here. `id` is a stable UUID used as the
// React key; `path` is the absolute archive directory; `capGb` is that drive's disk
// budget in GiB. On PUT, storageLocations is a FULL-LIST REPLACE (like audioSources):
// [] = single-drive (no archives), [..] = replace the whole list.
export interface StorageLocation {
  readonly id: string;
  readonly path: string;
  readonly capGb: number;
}

export interface Settings {
  readonly resolution: string;
  readonly encoder: string;
  readonly retentionCapGb: number;
  readonly videoDir: string;
  readonly accountId: number | null;
  // Always a non-empty array from the core (a fresh install seeds one default-output
  // source). On PUT it is a FULL-LIST REPLACE: send the complete current array.
  readonly audioSources: AudioSource[];
  // Recording video controls written into the OBS profile (basic.ini) and applied on
  // the next OBS launch, mirroring `resolution`. fps is an OBS common-FPS integer
  // (30/60); quality is the OBS RecQuality token; format is the RecFormat2 container.
  readonly fps: number;
  readonly quality: string;
  readonly format: string;
  // Archive drives recordings are moved onto after recording (tiered storage). Empty =
  // single-drive: everything stays in videoDir under retentionCapGb.
  readonly storageLocations: StorageLocation[];
  // Auto-clip controls. When `autoClipOnRampage` is on, the core cuts a clip on a
  // rampage trigger; `clipPaddingSeconds` (1..60) is the lead/trail padding around a
  // clip's span. Mirrors SettingsStore.Settings.autoClipOnRampage / clipPaddingSeconds.
  readonly autoClipOnRampage: boolean;
  readonly clipPaddingSeconds: number;
}

// Per-drive disk usage from GET /storage/usage, used to show real free/total space and
// warn when a configured cap exceeds the drive's capacity. `role` is 'active' (the
// recording drive) or 'archive'. free/total are null when the drive can't be stat'd.
export interface DriveUsage {
  readonly role: 'active' | 'archive';
  readonly path: string;
  readonly capBytes: number;
  readonly usedBytes: number;
  readonly freeBytes: number | null;
  readonly totalBytes: number | null;
}

// GET /storage/usage: per-drive rows plus library-wide totals. `totalBytes` is every
// stored VOD; `starredBytes` is the starred subset (never auto-deleted by retention).
export interface StorageUsage {
  readonly drives: DriveUsage[];
  readonly totalBytes: number;
  readonly starredBytes: number;
}

// A partial update to Settings. Every field is optional so the renderer can PATCH
// just what changed; the core carries forward any field the patch omits. Because a
// null/omitted field means "leave unchanged", clearing accountId needs an explicit flag.
export type SettingsPatch = Partial<Settings> & {
  readonly clearAccountId?: boolean;
};

// Full row as the core's dev.dotarec.data.MatchSummary record serializes it.
// Nullable columns are `| null` to mirror SQLite NULLs. The list endpoint returns
// this contract; identity is the numeric `id`.
export interface MatchSummary {
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
  readonly recordStartedWallMs: number | null;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    headers: { Accept: 'application/json', ...authHeaders() },
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

// Pulls the most human-readable message out of a non-ok response body. The core
// runs with server.error.include-message=always, so a validation 400 carries a JSON
// body with a "message" field (e.g. "cap must be > 0") — surface that verbatim
// instead of the opaque "400 Bad Request" status text. Falls back to a plain-text
// body, then to null when there's nothing useful (so the caller can use status text).
// Reads the body only once: try JSON, and reuse the buffered text if that fails.
async function errorBodyMessage(res: Response): Promise<string | null> {
  let raw: string;
  try {
    raw = await res.text();
  } catch {
    return null; // body already consumed / unreadable — nothing to add
  }
  const text = raw.trim();
  if (text === '') return null;
  try {
    const parsed = JSON.parse(text) as { message?: unknown };
    if (typeof parsed.message === 'string' && parsed.message.trim() !== '') {
      return parsed.message;
    }
  } catch {
    /* not JSON — fall through to the raw text below */
  }
  return text;
}

async function putJson<TBody, TResult>(path: string, body: TBody): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'PUT',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    // Prefer the core's validation message (body.message) over the bare status text so
    // the user sees "cap must be > 0", not "PUT /settings failed: 400 Bad Request".
    const detail = await errorBodyMessage(res);
    throw new Error(detail ?? `PUT ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as TResult;
}

async function patchJson<TBody, TResult>(path: string, body: TBody): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'PATCH',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(5_000),
  });
  if (!res.ok) {
    throw new Error(`PATCH ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as TResult;
}

// Bodyless POST returning JSON. Setup actions (registry walk + cfg write) can run a
// touch longer than a plain read, so they get a wider timeout.
async function postJson<TResult>(path: string): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'POST',
    headers: { Accept: 'application/json', ...authHeaders() },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) {
    throw new Error(`POST ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as TResult;
}

// Body-carrying POST returning JSON (distinct from the bodyless postJson above —
// do not collapse the two). Surfaces the core's validation message on a non-ok
// body, exactly like putJson.
async function postJsonBody<TBody, TResult>(path: string, body: TBody): Promise<TResult> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...authHeaders(),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) {
    const detail = await errorBodyMessage(res);
    throw new Error(detail ?? `POST ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as TResult;
}

// DELETE returning no body (204/200). Wider timeout: the core also unlinks the .mp4 + thumbnail.
async function delVoid(path: string): Promise<void> {
  const res = await fetch(`${bridgeBase()}${path}`, {
    method: 'DELETE',
    headers: { ...authHeaders() },
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) {
    throw new Error(`DELETE ${path} failed: ${res.status} ${res.statusText}`);
  }
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

// Per-drive disk usage for the storage settings UI (real free/total space + computed
// used bytes per configured drive). Polled alongside the settings form.
export function fetchStorageUsage(): Promise<StorageUsage> {
  return getJson<StorageUsage>('/storage/usage');
}

// A single polled screenshot of the OBS "Dota" scene. `dataUri` is a ready-to-use
// `data:image/jpeg;base64,…` URI on success, or null on every degrade path (OBS down,
// connection failed, or screenshot failure). The endpoint always returns HTTP 200, so
// the renderer just shows the image or a placeholder.
export interface ScenePreview {
  readonly dataUri: string | null;
}

// GET /obs/preview — authed via getJson (an <img src> request can't carry the bridge
// token, hence the inline data-URI design). The renderer polls this while the
// Recording tab is mounted and renders `dataUri` straight into an <img>.
export function fetchScenePreview(): Promise<ScenePreview> {
  return getJson<ScenePreview>('/obs/preview');
}

// One pickable device/process for an audio source's target, as the core's
// /audio/inputs endpoint serializes it. `value` goes verbatim into
// AudioSource.target; `label` is the human label for the picker.
export interface AudioInputOption {
  readonly value: string;
  readonly label: string;
}

// GET /audio/inputs?kind=… — enumerates the selectable targets for an audio source
// kind. The core returns [] (HTTP 200) when OBS is down or enumeration fails, so the
// settings UI degrades gracefully rather than erroring.
export function fetchAudioInputs(kind: AudioSourceKind): Promise<AudioInputOption[]> {
  return getJson<AudioInputOption[]>(`/audio/inputs?kind=${kind}`);
}

// Applies a partial settings patch via PUT /settings and returns the updated
// Settings the core now holds (the OBS connection is app-managed and off-surface).
export function updateSettings(patch: SettingsPatch): Promise<Settings> {
  return putJson<SettingsPatch, Settings>('/settings', patch);
}

// POST /setup/gsi/install — whether the cfg was auto-written, and the path it went
// to. installed=false (null paths) means Dota could not be found.
export interface GsiInstallResult {
  readonly installed: boolean;
  readonly dotaDir: string | null;
  readonly cfgPath: string | null;
}

// POST /setup/gsi/install-manual — the cfg body to drop in by hand when auto-install
// can't find Dota. `targetDir` is the gamestate_integration folder when known, else null.
export interface GsiManualInstructions {
  readonly cfgFileName: string;
  readonly cfgBody: string;
  readonly targetDir: string | null;
}

// The Steam launch option that activates GSI cfg loading (a constant, shown without a round-trip).
export const GSI_LAUNCH_OPTION = '-gamestateintegration';

// Auto-installs the GSI cfg into the discovered Dota tree (mints the auth token on
// first run). installed=false means Dota wasn't found — fall back to manual.
export function installGsi(): Promise<GsiInstallResult> {
  return postJson<GsiInstallResult>('/setup/gsi/install');
}

// Fetches the cfg body + target path for a manual install.
export function fetchGsiManualInstructions(): Promise<GsiManualInstructions> {
  return postJson<GsiManualInstructions>('/setup/gsi/install-manual');
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
  // Wall-clock epoch millis when recording started — the anchor the player uses to
  // convert pause spans (also wall-clock epoch millis) into video offsets. Null on
  // seeded/legacy rows that predate the column being written.
  readonly recordStartedWallMs: number | null;
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

// A saved clip cut from a parent match's VOD. FROZEN wire shape, serialized
// identically by the core's dev.dotarec.data.ClipRow record and here — field
// names/order match the record. `kind` is 'auto' (rampage/etc.) or 'manual';
// `triggerReason` is the auto trigger (e.g. 'rampage'), null for manual. Offsets
// are seconds into the parent VOD. `videoPath`/`thumbPath`/`fileSizeBytes` are null
// until the clip is generated. `status` walks 'pending' → 'generating' → 'ready'
// (or 'failed', with `error` set).
export interface Clip {
  readonly id: number;
  readonly parentMatchId: number;
  readonly kind: 'auto' | 'manual';
  readonly triggerReason: string | null;
  readonly startOffsetS: number;
  readonly endOffsetS: number;
  readonly label: string | null;
  readonly videoPath: string | null;
  readonly thumbPath: string | null;
  readonly fileSizeBytes: number | null;
  readonly status: 'pending' | 'generating' | 'ready' | 'failed';
  readonly error: string | null;
  readonly createdAt: number;
  // When true, the clip is exempt from the retention sweep (kept until manually deleted),
  // independently of its parent match's star.
  readonly starred: boolean;
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

export function fetchPauses(id: number): Promise<PauseSpan[]> {
  return getJson<PauseSpan[]>(`/matches/${id}/pauses`);
}

export function fetchBucketCounts(): Promise<BucketCounts> {
  return getJson<BucketCounts>('/buckets/counts');
}

// Clips cut from a match's VOD, ordered by start offset (GET /matches/{id}/clips).
export function fetchClips(matchId: number): Promise<Clip[]> {
  return getJson<Clip[]>(`/matches/${matchId}/clips`);
}

// Every clip across all matches, newest first (GET /clips). Backs the library
// "Clips" bucket flat list (clips live in their own table, not the matches list).
export function fetchAllClips(): Promise<Clip[]> {
  return getJson<Clip[]>('/clips');
}

// Requests a new manual clip (POST /matches/{id}/clips). The core accepts the cut and
// returns the freshly-created ClipRow (status 'pending'); generation runs async and
// progress/readiness arrive over the socket as clip.progress / clip.ready events.
export function createClip(
  matchId: number,
  body: { startOffsetS: number; endOffsetS: number; label?: string },
): Promise<Clip> {
  return postJsonBody<typeof body, Clip>(`/matches/${matchId}/clips`, body);
}

// Permanently deletes a clip (DELETE /clips/{id}): unlinks the .mp4 then drops the row.
export function deleteClip(clipId: number): Promise<void> {
  return delVoid(`/clips/${clipId}`);
}

// Toggles the star on a match (PATCH /matches/{id}) and returns the updated row.
export function setStarred(id: number, starred: boolean): Promise<MatchDetail> {
  return patchJson<{ starred: boolean }, MatchDetail>(`/matches/${id}`, { starred });
}

// Toggles the star on a clip (PATCH /clips/{id}) and returns the updated clip. A starred clip is
// exempt from the retention sweep, independent of its parent match.
export function setClipStarred(clipId: number, starred: boolean): Promise<Clip> {
  return patchJson<{ starred: boolean }, Clip>(`/clips/${clipId}`, { starred });
}

// Permanently deletes a match (DELETE /matches/{id}): the row + its markers/pauses
// (FK cascade) and the .mp4 + thumbnail on disk. No undo.
export function deleteMatch(id: number): Promise<void> {
  return delVoid(`/matches/${id}`);
}

export type StatusListener = (status: Status) => void;
export type StateListener = (connected: boolean) => void;
// Receives raw /ws envelopes for library-mutating match.* events
// (match.recorded / match.enriched / match.enrichFailed). The renderer reacts by
// re-fetching the list + counts; the payload shape is intentionally opaque here.
export type MatchEventListener = (evt: { type: string; payload: unknown }) => void;

// Clip lifecycle frames fanned out to onClipEvent() subscribers. Three kinds:
//   'clip.created'  — payload is the new Clip (status 'pending')
//   'clip.progress' — payload is { clipId, parentMatchId, percent }
//   'clip.ready'    — payload is { clipId, parentMatchId, status, videoPath }
// Every payload carries `parentMatchId`, so a subscription can be scoped to the open
// match (see StatusSocket.onClipEvent). The renderer reacts by refreshing that match's
// clip list (clip.created / clip.ready) or updating a progress bar (clip.progress).
export type ClipEventType = 'clip.created' | 'clip.progress' | 'clip.ready';

export type ClipEvent =
  | { readonly type: 'clip.created'; readonly payload: Clip }
  | {
      readonly type: 'clip.progress';
      readonly payload: { readonly clipId: number; readonly parentMatchId: number; readonly percent: number };
    }
  | {
      readonly type: 'clip.ready';
      readonly payload: {
        readonly clipId: number;
        readonly parentMatchId: number;
        readonly status: string;
        readonly videoPath: string | null;
      };
    };

export type ClipEventListener = (evt: ClipEvent) => void;

// Every clip frame payload carries parentMatchId; pull it out for matchId-scoped routing.
function clipEventMatchId(evt: ClipEvent): number {
  return evt.payload.parentMatchId;
}

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
  private readonly matchEventListeners = new Set<MatchEventListener>();
  // Clip-event subscribers keyed by the match they care about; a 0 key means "all
  // matches". onClipEvent() adds to a per-match set and returns a detacher.
  private readonly clipEventListeners = new Map<number, Set<ClipEventListener>>();

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

  // Subscribe to library-mutating match.* frames (match.recorded /
  // match.enriched / match.enrichFailed). Mirrors onStatus(): returns a detacher.
  onEvent(listener: MatchEventListener): () => void {
    this.matchEventListeners.add(listener);
    return () => this.matchEventListeners.delete(listener);
  }

  // Subscribe to clip lifecycle frames (clip.created / clip.progress / clip.ready),
  // scoped to one match by its parentMatchId. Pass matchId 0 (or omit) to receive
  // every clip event. Mirrors onEvent(): returns a detacher that also prunes the
  // per-match bucket once empty.
  onClipEvent(matchId: number, listener: ClipEventListener): () => void {
    let bucket = this.clipEventListeners.get(matchId);
    if (!bucket) {
      bucket = new Set<ClipEventListener>();
      this.clipEventListeners.set(matchId, bucket);
    }
    bucket.add(listener);
    return () => {
      const set = this.clipEventListeners.get(matchId);
      if (!set) return;
      set.delete(listener);
      if (set.size === 0) this.clipEventListeners.delete(matchId);
    };
  }

  // Fans a clip frame out to its match-scoped subscribers plus any all-matches (key 0)
  // subscribers.
  private emitClipEvent(evt: ClipEvent): void {
    const matchId = clipEventMatchId(evt);
    for (const listener of this.clipEventListeners.get(matchId) ?? []) listener(evt);
    if (matchId !== 0) {
      for (const listener of this.clipEventListeners.get(0) ?? []) listener(evt);
    }
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
      } else if (
        envelope?.type === 'match.enriched' ||
        envelope?.type === 'match.enrichFailed' ||
        envelope?.type === 'match.recorded'
      ) {
        // Library-mutating events: fan out to onEvent() subscribers, which
        // re-fetch the list + counts. Unknown types still fall through ignored.
        for (const listener of this.matchEventListeners) listener(envelope);
      } else if (
        envelope?.type === 'clip.created' ||
        envelope?.type === 'clip.progress' ||
        envelope?.type === 'clip.ready'
      ) {
        // Clip lifecycle events: fan out to onClipEvent() subscribers scoped by the
        // payload's parentMatchId. The envelope's payload shape is known per type
        // (see ClipEvent), so the cast is safe.
        this.emitClipEvent(envelope as ClipEvent);
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
