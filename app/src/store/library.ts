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
  setClipStarred,
  deleteMatch as apiDeleteMatch,
  deleteClip as apiDeleteClip,
  toStatus,
  StatusSocket,
  type MatchSummary,
  type Clip,
  type BucketCounts,
  type Status,
  type DiskWarning,
} from '../api/client';
import type { Bucket } from './buckets';
import type { UpdateState } from '../../electron/bridge-contract';
import { mergeLibraryLoad } from '../lib/library-load';
import {
  isOrphanedRecording,
  stepOrphanNotify,
  shouldNotifyDisk,
  INITIAL_ORPHAN_NOTIFY_STATE,
  ORPHAN_NOTIFY_DELAY_MS,
  type OrphanNotifyState,
} from '../lib/system-notify';
import {
  applyMatchesDeleted,
  applyMatchVideosDeleted,
  applyRecordingsDeleted,
  applyClipDeleted,
  applyClipsDeleted,
  type DeleteSlice,
} from '../lib/library-delete';
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
  // The latest low-disk warning the core pushed (core "error" frame, scope "disk"), or null when none
  // is outstanding. Backs a visible banner; the wiring also fires a debounced OS notification. Set by
  // the socket subscription in startLibrary; cleared via setDiskWarning(null) when a banner is dismissed.
  readonly diskWarning: DiskWarning | null;
  // Latest app-update snapshot pushed by the Electron main process (electron-updater), or null
  // outside Electron / before the first push. Drives the Settings update row + the Sidebar
  // "update ready" dot. Fed by startLibrary's onUpdateState subscription.
  readonly update: UpdateState | null;

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
  // Star/unstar a clip (exempts it from the retention sweep), mirroring toggleStar for matches.
  readonly toggleClipStar: (id: number, starred: boolean) => Promise<void>;
  // Delete a recording — never its clips (they have their own delete). A recording with clips
  // survives as a videoless stub row (the clips need their parent); a clipless one drops entirely.
  readonly deleteMatch: (id: number) => Promise<void>;
  // Bulk-delete several recordings at once (the table's multi-select). Deletes each server-side,
  // then applies the same rule to all survivors and refreshes counts in one shot.
  readonly deleteMatches: (ids: readonly number[]) => Promise<void>;
  // Permanently delete one clip (the Clips bucket's right-click delete).
  readonly deleteClip: (id: number) => Promise<void>;
  // Bulk-delete several clips at once (the Clips bucket's multi-select), mirroring deleteMatches:
  // each deletes server-side, then one state update + one counts refresh for all of them.
  readonly deleteClips: (ids: readonly number[]) => Promise<void>;
  readonly setDiskWarning: (warning: DiskWarning | null) => void;
  readonly setUpdate: (update: UpdateState | null) => void;
  // A `silent` reload (reconnect reconciliation) refreshes in the background without flipping the
  // table to its loading spinner, so an open player isn't torn down on every reconnect.
  readonly load: (opts?: { readonly silent?: boolean }) => Promise<void>;
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
    diskWarning: null,
    update: null,

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
    setDiskWarning: (diskWarning) => set({ diskWarning }),
    setUpdate: (update) => set({ update }),

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
        set((s) => ({
          matches: s.matches.map((m) => (m.id === id ? { ...m, starred: !starred } : m)),
        }));
      }
    },
    // Optimistic clip star toggle, mirroring toggleStar: flip locally for instant feedback, persist via
    // PATCH /clips/{id}, revert on failure. invalidatePendingLoad keeps an in-flight load() from
    // clobbering the flip with pre-toggle data.
    toggleClipStar: async (id, starred) => {
      set((s) => ({ clips: s.clips.map((c) => (c.id === id ? { ...c, starred } : c)) }));
      try {
        await setClipStarred(id, starred);
        invalidatePendingLoad();
      } catch {
        set((s) => ({
          clips: s.clips.map((c) => (c.id === id ? { ...c, starred: !starred } : c)),
        }));
      }
    },

    // Delete a recording (never its clips — the server leaves a videoless stub row when clips
    // exist, else drops the row). Pessimistic: delete server-side FIRST, then mirror the branch the
    // SERVER reports it took (its decision is made from the clip table under its maintenance lock;
    // our own clips list can lag it by the ~200ms clip.created reload window, so guessing locally can
    // drop a row the server stubbed). Only a missing outcome (older core) falls back to the local
    // guess. Rethrows so the caller can surface a failure.
    deleteMatch: async (id) => {
      const outcome = await apiDeleteMatch(id);
      // A coalesced match.* frame fires load() every ~200ms, so a load() that fetched the list BEFORE
      // this delete committed server-side may still be in flight; invalidate it so it can't resurrect
      // the just-deleted row (and so it doesn't leave the table wedged on the spinner).
      invalidatePendingLoad();
      const deleted = new Set([id]);
      set((s) =>
        outcome === 'stubbed'
          ? applyMatchVideosDeleted(s, deleted)
          : outcome === 'deleted'
            ? applyMatchesDeleted(s, deleted)
            : applyRecordingsDeleted(s, deleted),
      );
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    // Bulk-delete (table multi-select). Deletes each server-side first — sequentially, bounded by the
    // small selection size — then applies the server-reported branches for all survivors in ONE state
    // update and refreshes counts once (rather than the per-row count fetch deleteMatch does). A
    // single failed delete is skipped so the rest still go; the next load() reconciles any straggler.
    deleteMatches: async (ids) => {
      const removed = new Set<number>();
      const stubbed = new Set<number>();
      const unknown = new Set<number>(); // no outcome in the response (older core) -> local guess
      for (const id of ids) {
        try {
          const outcome = await apiDeleteMatch(id);
          (outcome === 'deleted' ? removed : outcome === 'stubbed' ? stubbed : unknown).add(id);
        } catch {
          /* skip this one; the rest still delete and the next load() reconciles */
        }
      }
      if (removed.size === 0 && stubbed.size === 0 && unknown.size === 0) return;
      invalidatePendingLoad();
      set((s) => {
        let next: DeleteSlice = applyMatchVideosDeleted(s, stubbed);
        next = applyMatchesDeleted(next, removed);
        return applyRecordingsDeleted(next, unknown);
      });
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    // Permanently delete one clip (the Clips bucket's right-click delete). Pessimistic like
    // deleteMatch: server-side first, then drop the row locally (clearing a clip auto-play that
    // pointed at it) and refresh the Clips badge. The player's own strip follows the clip.deleted
    // frame the core publishes. Rethrows so the caller can surface a failure.
    deleteClip: async (id) => {
      await apiDeleteClip(id);
      invalidatePendingLoad();
      set((s) => applyClipDeleted(s, id));
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    // Bulk clip delete (the Clips bucket's multi-select). Mirrors deleteMatches: each delete runs
    // server-side first — sequentially, bounded by the small selection size — then all removals land
    // in ONE state update with a single counts refresh. A failed delete is skipped so the rest still
    // go; the next load() reconciles any straggler.
    deleteClips: async (ids) => {
      const removed = new Set<number>();
      for (const id of ids) {
        try {
          await apiDeleteClip(id);
          removed.add(id);
        } catch {
          /* skip this one; the rest still delete and the next load() reconciles */
        }
      }
      if (removed.size === 0) return;
      invalidatePendingLoad();
      set((s) => applyClipsDeleted(s, removed));
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    load: async (opts) => {
      const token = ++loadToken;
      // A silent reload (reconnect reconciliation) skips the 'loading' flip so the table + open player
      // aren't torn down by a spinner; the merge below still settles loadState to 'ready'/'error'.
      if (!opts?.silent) set({ loadState: 'loading' });
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

      // A rejected individual fetch keeps the PREVIOUS slice (not empty) so one failing endpoint —
      // likeliest right after a match records — can't blank the table and tear down the open video;
      // selection survival is judged only against a fetch that actually succeeded. See mergeLibraryLoad.
      set(mergeLibraryLoad(matchesRes, countsRes, clipsRes, get()));
    },
  };
});

// Best-effort desktop notification for the storage-observability events. No-op when the Notification
// API is absent (unit tests / a stripped runtime); requests permission once while it is still default.
// Reaches the user even while the window is hidden to the tray — Electron keeps the renderer alive on
// hide (main.ts hides, never destroys, on window close), so the notification still fires.
function notify(title: string, body: string, tag: string): void {
  if (typeof Notification === 'undefined') return;
  const fire = (): void => {
    try {
      new Notification(title, { body, tag });
    } catch {
      /* some runtimes throw on construction; best-effort only */
    }
  };
  if (Notification.permission === 'granted') {
    fire();
  } else if (Notification.permission === 'default') {
    void Notification.requestPermission()
      .then((perm) => {
        if (perm === 'granted') fire();
      })
      .catch(() => {});
  }
}

/**
 * Wires the library store to live data: kicks off the initial load, primes the
 * status from a one-shot GET /status, and subscribes to the StatusSocket. The
 * socket drives the live status card and triggers a list refresh when the core
 * pushes a `match.recorded` / `match.enriched` / `retention.swept` frame; a
 * low-disk `error` frame surfaces a banner + a debounced OS notification.
 *
 * On reconnect it reconciles (a silent library refetch + status re-prime) so
 * events missed while the socket was down are picked up. It also fires a one-shot
 * notification when OBS is left recording an FSM-orphaned output for >60s.
 *
 * Returns a teardown function that closes the socket and detaches listeners.
 */
export function startLibrary(): () => void {
  const store = useLibraryStore.getState();

  void store.load();

  // Prime status from a one-shot GET /status so the card is not blank before the first WS frame.
  const primeStatus = async (): Promise<void> => {
    try {
      const snapshot = await fetchStatus();
      useLibraryStore.getState().setStatus(toStatus(snapshot));
    } catch {
      // Core not up yet; the socket will fill this in once it connects.
    }
  };
  void primeStatus();

  // App-update state (Electron only): prime the current snapshot from the main process, then
  // subscribe to pushes as the update lifecycle advances. window.dotarec is absent in
  // plain-browser dev, so both calls no-op there (offUpdate stays undefined).
  void (async () => {
    const state = await window.dotarec?.getUpdateState?.();
    if (state) useLibraryStore.getState().setUpdate(state);
  })();
  const offUpdate = window.dotarec?.onUpdateState?.((state) => {
    useLibraryStore.getState().setUpdate(state);
  });

  const socket = new StatusSocket();

  // Orphaned-recording notification: fire a single OS notification once OBS has been recording an
  // output the FSM no longer tracks for longer than the debounce window (so a self-healing blip
  // doesn't trip it), and reset the one-shot when the condition clears. Re-evaluated on every status
  // frame; a timer re-checks the threshold crossing even if no new frame arrives in the meantime.
  let orphanState: OrphanNotifyState = INITIAL_ORPHAN_NOTIFY_STATE;
  let orphanTimer: ReturnType<typeof setTimeout> | null = null;
  const clearOrphanTimer = (): void => {
    if (orphanTimer !== null) {
      clearTimeout(orphanTimer);
      orphanTimer = null;
    }
  };
  const evaluateOrphan = (): void => {
    const active = isOrphanedRecording(useLibraryStore.getState().status);
    const { next, fire } = stepOrphanNotify(orphanState, active, Date.now());
    orphanState = next;
    if (fire) {
      notify(
        'OBS is still recording',
        'A recording is running that the recorder has lost track of. Open Dota 2 Recorder to stop it.',
        'dotarec-orphan',
      );
    }
    clearOrphanTimer();
    if (active && !next.notified && next.since !== null) {
      const remaining = Math.max(0, next.since + ORPHAN_NOTIFY_DELAY_MS - Date.now());
      orphanTimer = setTimeout(evaluateOrphan, remaining);
    }
  };

  const offStatus = socket.onStatus((status) => {
    useLibraryStore.getState().setStatus(status);
    evaluateOrphan();
  });

  const offConn = socket.onConnectionChange((connected) => {
    if (!connected) {
      // On drop, clear status so the card reads "unknown" rather than going stale; the orphan streak
      // resets too (status now null -> not an orphan).
      useLibraryStore.getState().setStatus(null);
      evaluateOrphan();
      return;
    }
    // Reconnect (or first connect): reconcile so events missed while the socket was down are picked
    // up. A silent reload refreshes in the background without the loading spinner tearing down the
    // open player on every reconnect; primeStatus refills the card before the first status frame.
    void useLibraryStore.getState().load({ silent: true });
    void primeStatus();
  });

  // Low-disk warnings: surface a banner (store state) and fire a debounced OS notification. The banner
  // updates every frame; only the notification is rate-limited so repeated pre-record checks don't spam.
  let lastDiskNotifyAt: number | null = null;
  const offDisk = socket.onDiskWarning((warning) => {
    useLibraryStore.getState().setDiskWarning(warning);
    const now = Date.now();
    if (shouldNotifyDisk(lastDiskNotifyAt, now)) {
      lastDiskNotifyAt = now;
      notify('Low disk space', warning.message, 'dotarec-disk');
    }
  });

  // Beyond `status` frames, the socket forwards library-mutating events (match.recorded /
  // match.enriched / match.enrichFailed AND retention.swept) via onEvent. Any of them re-loads the
  // list + counts so an enriched row jumps Unsorted -> its real bucket, a swept row drops, and the
  // sidebar badges refresh together.
  //
  // Coalesce bursts: a backlog enriching can fire several frames in quick succession. The store's
  // loadToken already prevents stale results from clobbering, but without coalescing each frame still
  // issues its own fetch pair. Collapse a burst into a single reload fired shortly after the first.
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
    offDisk();
    off();
    offClips();
    offUpdate?.();
    clearOrphanTimer();
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
function subscribeToMatchEvents(socket: StatusSocket, onMatchEvent: () => void): () => void {
  return socket.onEvent(() => onMatchEvent());
}
