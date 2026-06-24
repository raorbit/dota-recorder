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
  fetchStatus,
  StatusSocket,
  type MatchSummary,
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
  readonly counts: BucketCounts;
  readonly status: Status | null;
  readonly loadState: LoadState;

  // --- filters / selection ---
  readonly bucket: Bucket;
  readonly resultFilter: ResultFilter;
  readonly search: string;
  readonly dateFilter: string | null;
  readonly selectedMatchId: number | null;

  // --- actions ---
  readonly setBucket: (bucket: Bucket) => void;
  readonly setResultFilter: (filter: ResultFilter) => void;
  readonly setSearch: (search: string) => void;
  readonly setDateFilter: (date: string | null) => void;
  readonly selectMatch: (id: number | null) => void;
  readonly setStatus: (status: Status | null) => void;
  readonly load: () => Promise<void>;
}

// Monotonic token guarding load() against out-of-order resolution: each load() bumps
// it, so when an older in-flight load() resolves it sees a stale token and drops its
// result rather than clobbering the fresher load that superseded it. Module-scoped (not
// store state) so it never triggers a re-render.
let loadToken = 0;

export const useLibraryStore = create<LibraryState>((set, get) => ({
  matches: [],
  counts: EMPTY_COUNTS,
  status: null,
  loadState: 'idle',

  bucket: 'ranked',
  resultFilter: 'all',
  search: '',
  dateFilter: null,
  selectedMatchId: null,

  setBucket: (bucket) => set({ bucket }),
  setResultFilter: (resultFilter) => set({ resultFilter }),
  setSearch: (search) => set({ search }),
  setDateFilter: (dateFilter) => set({ dateFilter }),
  selectMatch: (selectedMatchId) => set({ selectedMatchId }),
  setStatus: (status) => set({ status }),

  load: async () => {
    const token = ++loadToken;
    set({ loadState: 'loading' });
    // Counts and the list are independent; settle both so one failing endpoint
    // (e.g. counts not yet implemented) does not blank the whole screen.
    const [matchesRes, countsRes] = await Promise.allSettled([
      fetchMatches(),
      fetchBucketCounts(),
    ]);

    // A newer load() superseded this one while it was in flight (a burst of match.*
    // frames each fire load()); drop the stale result so it can't clobber fresher data.
    if (token !== loadToken) return;

    const matches = matchesRes.status === 'fulfilled' ? matchesRes.value : [];
    const counts = countsRes.status === 'fulfilled' ? countsRes.value : EMPTY_COUNTS;

    // If BOTH calls failed the core is unreachable; surface an error state.
    // If only one failed we still render with what we have.
    const errored = matchesRes.status === 'rejected' && countsRes.status === 'rejected';

    // Drop a stale selection if the selected match is no longer in the list.
    const { selectedMatchId } = get();
    const stillPresent =
      selectedMatchId !== null && matches.some((m) => m.id === selectedMatchId);

    set({
      matches,
      counts,
      loadState: errored ? 'error' : 'ready',
      selectedMatchId: stillPresent ? selectedMatchId : null,
    });
  },
}));

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

  socket.connect();

  return () => {
    offStatus();
    offConn();
    off();
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
