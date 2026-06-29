// Library view state for the Browse screen, backed by zustand.
//
// Owns: the selected bucket, the win/loss filter, the search query, the date
// filter, the selected match id, plus the data the screen renders (matches list,
// bucket counts, live Status). Actions mutate filters / selection and load data
// from the core REST API. A StatusSocket subscription refreshes the list when the
// core announces a newly recorded or enriched match.
//
// Defensive by design: every fetch tolerates an unreachable / empty core (no
// matches yet), and the socket subscription ignores frame types it does not know
// about, so wiring works against EMPTY data before any recording exists.
import { create } from 'zustand';
import {
  fetchMatches,
  fetchBucketCounts,
  fetchAllClips,
  fetchStatus,
  setStarred,
  deleteMatch as apiDeleteMatch,
  StatusSocket,
  type MatchSummary,
  type Clip,
  type BucketCounts,
  type Status,
  type StatusSnapshot,
} from '../api/client';
import type { Bucket } from './buckets';
export type { Bucket } from './buckets';

export type ResultFilter = 'all' | 'wins' | 'losses';

type LoadState = 'idle' | 'loading' | 'ready' | 'error';

const EMPTY_COUNTS: BucketCounts = {
  ranked: 0,
  unranked: 0,
  turbo: 0,
  abilityDraft: 0,
  manual: 0,
  clips: 0,
  unsorted: 0,
};

export interface LibraryState {
  // --- data ---
  readonly matches: readonly MatchSummary[];
  // Every saved clip across all matches (GET /clips), newest first. Backs the "Clips"
  // bucket, which lists clips from their own table rather than the matches list.
  readonly clips: readonly Clip[];
  readonly counts: BucketCounts;
  readonly status: Status | null;
  readonly loadState: LoadState;

  // --- filters / selection ---
  readonly bucket: Bucket;
  readonly resultFilter: ResultFilter;
  readonly search: string;
  readonly dateFilter: string | null;
  readonly selectedMatchId: number | null;
  // When a clip is selected from the Clips bucket, this holds its id so the player
  // auto-plays that clip (selectedMatchId points at the clip's parent match, so the
  // player loads the parent VOD + clip strip and starts on this clip). Null for a
  // plain match selection (full VOD).
  readonly selectedClipId: number | null;
  // Monotonic token bumped on every selectClip (even re-selecting the same clip id) so the player
  // re-plays a clip the user clicks again after switching back to the full VOD.
  readonly clipPlayToken: number;

  // --- actions ---
  readonly setBucket: (bucket: Bucket) => void;
  readonly setResultFilter: (filter: ResultFilter) => void;
  readonly setSearch: (search: string) => void;
  readonly setDateFilter: (date: string | null) => void;
  readonly selectMatch: (id: number | null) => void;
  // Select a clip from the Clips bucket: opens its parent match in the player and
  // marks the clip for auto-play.
  readonly selectClip: (clip: Clip) => void;
  readonly setStatus: (status: Status | null) => void;
  readonly toggleStar: (id: number, starred: boolean) => Promise<void>;
  readonly deleteMatch: (id: number) => Promise<void>;
  readonly load: () => Promise<void>;
}

// Monotonic token guarding load() against out-of-order resolution: each load() bumps
// it, so when an older in-flight load() resolves it sees a stale token and drops its
// result rather than clobbering the fresher load that superseded it. Module-scoped (not
// store state) so it never triggers a re-render.
let loadToken = 0;

export const useLibraryStore = create<LibraryState>((set, get) => {
  // Invalidate any load() already in flight so its (possibly pre-mutation) result can't clobber a
  // local mutation that just changed the list — e.g. resurrect a just-deleted row, or revert a
  // just-applied star. The in-flight load() sees the bumped token and bails at its `token !==
  // loadToken` guard. Crucially, that early bail happens BEFORE load() restores loadState, so we
  // must settle a dangling 'loading' here too (the caller already holds authoritative data);
  // otherwise the table stays wedged on the "Loading recordings…" spinner with every row hidden.
  const invalidatePendingLoad = (): void => {
    loadToken++;
    if (get().loadState === 'loading') set({ loadState: 'ready' });
  };

  return {
  matches: [],
  clips: [],
  counts: EMPTY_COUNTS,
  status: null,
  loadState: 'idle',

  bucket: 'ranked',
  resultFilter: 'all',
  search: '',
  dateFilter: null,
  selectedMatchId: null,
  selectedClipId: null,
  clipPlayToken: 0,

  setBucket: (bucket) => set({ bucket }),
  setResultFilter: (resultFilter) => set({ resultFilter }),
  setSearch: (search) => set({ search }),
  setDateFilter: (dateFilter) => set({ dateFilter }),
  // A plain match selection always plays the full VOD, so clear any clip auto-play.
  selectMatch: (selectedMatchId) => set({ selectedMatchId, selectedClipId: null }),
  // Open the clip's parent match in the player and flag the clip for auto-play. Bump clipPlayToken
  // every call (even for the same clip id) so re-selecting a clip after "Full VOD" replays it.
  selectClip: (clip) =>
    set((s) => ({
      selectedMatchId: clip.parentMatchId,
      selectedClipId: clip.id,
      clipPlayToken: s.clipPlayToken + 1,
    })),
  setStatus: (status) => set({ status }),

  // Star/unstar a match: flip it locally for instant feedback, then persist via
  // PATCH /matches/{id}. Starred recordings are exempt from the retention sweep, so
  // this is the lever that copy promises ("oldest unstarred removed first"). Both the
  // optimistic flip and the on-failure revert are functional per-row updates (not a
  // whole-array snapshot) so a list reload landing during the in-flight PATCH — a
  // coalesced match.* event fires load() every ~200ms — isn't clobbered on revert.
  // Once the PATCH commits, invalidate any in-flight load(): one whose GET ran before the commit
  // would otherwise resolve afterward with the pre-flip star state and clobber the optimistic flip
  // via its whole-array replace. (A load that resolves in the brief flip->commit window can still
  // flicker the flip, but the 200ms coalesced reload reconciles it — inherent to optimistic UI over
  // polling; deleteMatch's guard is tighter because the server delete completes first.)
  toggleStar: async (id, starred) => {
    set((s) => ({ matches: s.matches.map((m) => (m.id === id ? { ...m, starred } : m)) }));
    try {
      await setStarred(id, starred);
      invalidatePendingLoad();
    } catch {
      set((s) => ({ matches: s.matches.map((m) => (m.id === id ? { ...m, starred: !starred } : m)) }));
    }
  },

  // Permanently delete a match (row + markers/pauses + .mp4 + thumbnail). Pessimistic:
  // delete server-side FIRST, then drop it from the list and clear the selection if it
  // was open, and refresh the bucket badges. Rethrows so the caller can surface a failure.
  deleteMatch: async (id) => {
    await apiDeleteMatch(id);
    // A coalesced match.* frame fires load() every ~200ms, so a load() that fetched the list BEFORE
    // this delete committed server-side may still be in flight; invalidate it so it can't resurrect
    // the just-deleted row (and so it doesn't leave the table wedged on the spinner).
    invalidatePendingLoad();
    set((s) => ({
      matches: s.matches.filter((m) => m.id !== id),
      // Deleting a match cascades its clips, so drop a clip-auto-play that pointed here.
      selectedMatchId: s.selectedMatchId === id ? null : s.selectedMatchId,
      selectedClipId: s.selectedMatchId === id ? null : s.selectedClipId,
    }));
    try {
      set({ counts: await fetchBucketCounts() });
    } catch {
      /* leave the stale badge; the next load() reconciles */
    }
  },

  load: async () => {
    const token = ++loadToken;
    set({ loadState: 'loading' });
    // Matches, counts, and clips are independent; settle all three so one failing
    // endpoint (e.g. counts not yet implemented) does not blank the whole screen.
    const [matchesRes, countsRes, clipsRes] = await Promise.allSettled([
      fetchMatches(),
      fetchBucketCounts(),
      fetchAllClips(),
    ]);

    // A newer load() superseded this one while it was in flight (a burst of match.*
    // frames each fire load()); drop the stale result so it can't clobber fresher data.
    if (token !== loadToken) return;

    const matches = matchesRes.status === 'fulfilled' ? matchesRes.value : [];
    const counts = countsRes.status === 'fulfilled' ? countsRes.value : EMPTY_COUNTS;
    const clips = clipsRes.status === 'fulfilled' ? clipsRes.value : [];

    // If ALL calls failed the core is unreachable; surface an error state.
    // If only some failed we still render with what we have.
    const errored =
      matchesRes.status === 'rejected' &&
      countsRes.status === 'rejected' &&
      clipsRes.status === 'rejected';

    // Drop a stale selection if neither the selected match nor (for a clip selection)
    // the selected clip survives the refresh.
    const { selectedMatchId, selectedClipId } = get();
    const matchPresent =
      selectedMatchId !== null && matches.some((m) => m.id === selectedMatchId);
    const clipPresent =
      selectedClipId !== null && clips.some((c) => c.id === selectedClipId);
    // A clip selection points selectedMatchId at the clip's parent (which is in the
    // matches list), so a surviving match OR clip keeps the selection alive.
    const stillPresent = matchPresent || clipPresent;

    set({
      matches,
      clips,
      counts,
      loadState: errored ? 'error' : 'ready',
      selectedMatchId: stillPresent ? selectedMatchId : null,
      selectedClipId: clipPresent ? selectedClipId : null,
    });
  },
  };
});

// Maps a raw status snapshot to the flattened Status the store keeps. (toStatus is
// private to the client module; re-derive the same shape here.)
function snapshotToStatus(snapshot: StatusSnapshot): Status {
  return {
    fsmState: snapshot.fsm.state,
    matchId: snapshot.fsm.activeMatchId,
    recording: snapshot.obs.recording,
    gsiConnected: snapshot.gsi.connected,
    snapshot,
  };
}

/**
 * Wires the library store to live data: kicks off the initial load, primes the
 * status from a one-shot GET /status, and subscribes to the StatusSocket. The
 * socket drives the live status card and triggers a list refresh when the core
 * pushes a `match.recorded` / `match.enriched` frame.
 *
 * These match.* frames may not be emitted yet by the current core — the
 * subscription is defensive and simply no-ops until they arrive.
 *
 * Returns a teardown function that closes the socket and detaches listeners.
 */
export function startLibrary(): () => void {
  const store = useLibraryStore.getState();

  void store.load();

  // Prime status immediately so the card is not blank before the first WS frame.
  void (async (): Promise<void> => {
    try {
      const snapshot = await fetchStatus();
      store.setStatus(snapshotToStatus(snapshot));
    } catch {
      // Core not up yet; the socket will fill this in once it connects.
    }
  })();

  const socket = new StatusSocket();

  const offStatus = socket.onStatus((status) => {
    useLibraryStore.getState().setStatus(status);
  });

  const offConn = socket.onConnectionChange((connected) => {
    // On drop, clear status so the card reads "unknown" rather than going stale.
    if (!connected) useLibraryStore.getState().setStatus(null);
  });

  // Beyond `status` frames, the socket forwards library-mutating match.* events
  // (match.recorded / match.enriched / match.enrichFailed) via onEvent. Any of
  // them re-loads the list + counts so an enriched row jumps Unsorted -> its
  // real bucket and the sidebar badges refresh together.
  //
  // Coalesce bursts: a backlog enriching can fire several match.* frames in quick
  // succession. The store's loadToken already prevents stale results from clobbering,
  // but without coalescing each frame still issues its own fetch pair. Collapse a burst
  // into a single reload fired shortly after the first frame.
  let reloadTimer: ReturnType<typeof setTimeout> | null = null;
  const scheduleReload = (): void => {
    if (reloadTimer !== null) return;
    reloadTimer = setTimeout(() => {
      reloadTimer = null;
      void useLibraryStore.getState().load();
    }, 200);
  };
  const off = subscribeToMatchEvents(socket, scheduleReload);

  // Clip lifecycle frames mutate the library too: a new/finished clip changes the
  // Clips bucket list + count. Subscribe to ALL matches (key 0) and reuse the same
  // coalesced reload — load() now refetches clips + counts alongside the match list.
  // clip.progress fires often during generation; only created/ready change membership,
  // so skip progress to avoid a reload per percent tick.
  const offClips = socket.onClipEvent(0, (evt) => {
    if (evt.type === 'clip.progress') return;
    scheduleReload();
  });

  socket.connect();

  return () => {
    offStatus();
    offConn();
    off();
    offClips();
    if (reloadTimer !== null) clearTimeout(reloadTimer);
    socket.close();
  };
}

// match.recorded / match.enriched / match.enrichFailed arrive as raw /ws
// envelopes. StatusSocket now surfaces them through its typed onEvent() channel,
// so we forward every such frame to onMatchEvent — which re-fetches the list +
// counts. A newly-enriched row thus leaves Unsorted for its real bucket and the
// badge counts update in one shot. load() replaces (not increments) state, so
// duplicate frames for the same id are naturally idempotent — no double-counting.
function subscribeToMatchEvents(
  socket: StatusSocket,
  onMatchEvent: () => void,
): () => void {
  return socket.onEvent(() => onMatchEvent());
}
