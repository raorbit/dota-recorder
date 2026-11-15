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
import { deleteFailureMessage } from '../lib/delete-labels';
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
  // Recordings and clips whose delete is queued or in flight. A delete can wait minutes for the core's
  // maintenance lock (an archive pass holds it), so the tables dim these rows ("Deleting…") and offer
  // no second delete for them; one asked for anyway joins the first (see the client's delete queue). A
  // row leaves its set when its delete settles: done, refused, or timed out (the core can still finish
  // a timed-out one, and its match.deleted / clip.deleted frame reloads the list when it does).
  readonly deletingMatchIds: ReadonlySet<number>;
  readonly deletingClipIds: ReadonlySet<number>;
  // Why the last recording or clip delete failed, or null. Backs the delete-failure banner; cleared
  // when the banner is dismissed or another delete starts. A delete that timed out isn't reported:
  // the core still finishes it once its maintenance lock frees (see isRequestTimeout).
  readonly deleteError: string | null;
  // The latest low-disk warning: pushed by the core's "error" frame (scope "disk") and seeded from
  // GET /status (storage.lowDisk) on mount/reconnect, or null when none is outstanding. Backs the
  // in-app banner only; the OS notification is the Electron main process's (it outlives the window).
  // Cleared via setDiskWarning(null) when the banner is dismissed.
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
  // Permanently delete one clip (the Clips bucket's right-click delete, and the player strip's).
  readonly deleteClip: (id: number) => Promise<void>;
  // Bulk-delete several clips at once (the Clips bucket's multi-select), mirroring deleteMatches:
  // each deletes server-side, then one state update + one counts refresh for all of them.
  readonly deleteClips: (ids: readonly number[]) => Promise<void>;
  readonly setDeleteError: (message: string | null) => void;
  readonly setDiskWarning: (warning: DiskWarning | null) => void;
  readonly setUpdate: (update: UpdateState | null) => void;
  // A `silent` reload (reconnect reconciliation, a library frame, a delete's reconcile) refreshes in
  // the background without flipping the table to its loading spinner, so the rows stay on screen and
  // an open player isn't torn down.
  readonly load: (opts?: { readonly silent?: boolean }) => Promise<void>;
}

// Monotonic token guarding load() against out-of-order resolution: each load() bumps
// it, so when an older in-flight load() resolves it sees a stale token and drops its
// result rather than clobbering the fresher load that superseded it. Module-scoped (not
// store state) so it never triggers a re-render.
let loadToken = 0;

// One of the deleting* sets, `from`, with `ids` added or taken out. Always a new set, never `from`
// changed in place, so a table reading it re-renders.
function withIds(from: ReadonlySet<number>, ids: Iterable<number>): ReadonlySet<number> {
  const next = new Set(from);
  for (const id of ids) next.add(id);
  return next;
}

function withoutIds(from: ReadonlySet<number>, ids: Iterable<number>): ReadonlySet<number> {
  const next = new Set(from);
  for (const id of ids) next.delete(id);
  return next;
}

// Whether a request gave up on its own timeout (AbortSignal.timeout rejects with a TimeoutError). For
// a delete that means the core held its maintenance lock (or hung) for all ten minutes, not that it
// refused: it still finishes the delete once the lock frees, and the frame it sends then reloads the
// list. So a timeout is reconciled like a failure, but not reported as one.
function isRequestTimeout(err: unknown): boolean {
  return (
    typeof err === 'object' && err !== null && (err as { name?: unknown }).name === 'TimeoutError'
  );
}

// The delete-failure banner's message for a delete of `total` recordings or clips that failed with
// `errors`, as state to merge into the update that ends the delete: none when every one timed out.
function failureReport(
  kind: 'recording' | 'clip',
  errors: readonly unknown[],
  total: number,
): { readonly deleteError?: string } {
  const failures = errors.filter((err) => !isRequestTimeout(err));
  if (failures.length === 0) return {};
  return { deleteError: deleteFailureMessage(kind, failures.length, total, failures[0]) };
}

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
    deletingMatchIds: new Set<number>(),
    deletingClipIds: new Set<number>(),
    deleteError: null,
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
    setDeleteError: (deleteError) => set({ deleteError }),
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
    // guess. The row is in deletingMatchIds until the delete settles, and a failure is reported in
    // the banner (deleteError). Rethrows so the caller can tell a failure.
    deleteMatch: async (id) => {
      const deleted = new Set([id]);
      set((s) => ({ deletingMatchIds: withIds(s.deletingMatchIds, deleted), deleteError: null }));
      let outcome: Awaited<ReturnType<typeof apiDeleteMatch>>;
      try {
        outcome = await apiDeleteMatch(id);
      } catch (err) {
        // Failed, or gave up while the core waits out an archive pass (it then finishes the delete
        // and its match.deleted frame reloads the list). Reconcile with the server either way, so a
        // delete that did land never leaves its row listed.
        set((s) => ({
          deletingMatchIds: withoutIds(s.deletingMatchIds, deleted),
          ...failureReport('recording', [err], 1),
        }));
        void get().load({ silent: true });
        throw err;
      }
      // A coalesced match.* frame fires load() every ~200ms, so a load() that fetched the list BEFORE
      // this delete committed server-side may still be in flight; invalidate it so it can't resurrect
      // the just-deleted row (and so it doesn't leave the table wedged on the spinner).
      invalidatePendingLoad();
      set((s) => ({
        ...(outcome === 'stubbed'
          ? applyMatchVideosDeleted(s, deleted)
          : outcome === 'deleted'
            ? applyMatchesDeleted(s, deleted)
            : applyRecordingsDeleted(s, deleted)),
        deletingMatchIds: withoutIds(s.deletingMatchIds, deleted),
      }));
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
    // So is one that timed out behind a long archive pass: the core still finishes it once its
    // maintenance lock frees, and the rows sent after it then too (the client's delete queue sends
    // them one at a time, so waiting on the lock holds one connection to the core, not one per row).
    // Every row is in deletingMatchIds from the start until the whole run is done, and the run's
    // failures are reported in the banner together.
    deleteMatches: async (ids) => {
      set((s) => ({ deletingMatchIds: withIds(s.deletingMatchIds, ids), deleteError: null }));
      const removed = new Set<number>();
      const stubbed = new Set<number>();
      const unknown = new Set<number>(); // no outcome in the response (older core) -> local guess
      const errors: unknown[] = [];
      for (const id of ids) {
        try {
          const outcome = await apiDeleteMatch(id);
          (outcome === 'deleted' ? removed : outcome === 'stubbed' ? stubbed : unknown).add(id);
        } catch (err) {
          errors.push(err); // skip this one; the rest still delete
        }
      }
      // Reconcile a failed (or timed-out, see deleteMatch) delete with the server's list. Started
      // only after invalidatePendingLoad below, which would otherwise drop it as stale.
      const reconcile = (): void => {
        if (errors.length > 0) void get().load({ silent: true });
      };
      if (removed.size === 0 && stubbed.size === 0 && unknown.size === 0) {
        set((s) => ({
          deletingMatchIds: withoutIds(s.deletingMatchIds, ids),
          ...failureReport('recording', errors, ids.length),
        }));
        reconcile();
        return;
      }
      invalidatePendingLoad();
      set((s) => {
        let next: DeleteSlice = applyMatchVideosDeleted(s, stubbed);
        next = applyMatchesDeleted(next, removed);
        return {
          ...applyRecordingsDeleted(next, unknown),
          deletingMatchIds: withoutIds(s.deletingMatchIds, ids),
          ...failureReport('recording', errors, ids.length),
        };
      });
      reconcile();
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    // Permanently delete one clip (the Clips bucket's right-click delete, and the player strip's).
    // Pessimistic like deleteMatch: server-side first, then drop the row locally (clearing a clip
    // auto-play that pointed at it) and refresh the Clips badge. The clip is in deletingClipIds until
    // the delete settles, and a failure is reported in the banner and reconciled as in deleteMatch.
    // Rethrows so the caller (the player, for its own strip) can tell a failure.
    deleteClip: async (id) => {
      const deleted = new Set([id]);
      set((s) => ({ deletingClipIds: withIds(s.deletingClipIds, deleted), deleteError: null }));
      try {
        await apiDeleteClip(id);
      } catch (err) {
        set((s) => ({
          deletingClipIds: withoutIds(s.deletingClipIds, deleted),
          ...failureReport('clip', [err], 1),
        }));
        void get().load({ silent: true });
        throw err;
      }
      invalidatePendingLoad();
      set((s) => ({
        ...applyClipDeleted(s, id),
        deletingClipIds: withoutIds(s.deletingClipIds, deleted),
      }));
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    // Bulk clip delete (the Clips bucket's multi-select). Mirrors deleteMatches: each delete runs
    // server-side first — sequentially, bounded by the small selection size — then all removals land
    // in ONE state update with a single counts refresh. A failed or timed-out delete is skipped so the
    // rest still go, as in deleteMatches, and a reconcile reload follows. Every clip is in
    // deletingClipIds from the start until the whole run is done, and the run's failures are reported
    // in the banner together.
    deleteClips: async (ids) => {
      set((s) => ({ deletingClipIds: withIds(s.deletingClipIds, ids), deleteError: null }));
      const removed = new Set<number>();
      const errors: unknown[] = [];
      for (const id of ids) {
        try {
          await apiDeleteClip(id);
          removed.add(id);
        } catch (err) {
          errors.push(err); // skip this one; the rest still delete
        }
      }
      // Started only after invalidatePendingLoad below, which would otherwise drop it as stale.
      const reconcile = (): void => {
        if (errors.length > 0) void get().load({ silent: true });
      };
      if (removed.size === 0) {
        set((s) => ({
          deletingClipIds: withoutIds(s.deletingClipIds, ids),
          ...failureReport('clip', errors, ids.length),
        }));
        reconcile();
        return;
      }
      invalidatePendingLoad();
      set((s) => ({
        ...applyClipsDeleted(s, removed),
        deletingClipIds: withoutIds(s.deletingClipIds, ids),
        ...failureReport('clip', errors, ids.length),
      }));
      reconcile();
      try {
        set({ counts: await fetchBucketCounts() });
      } catch {
        /* leave the stale badge; the next load() reconciles */
      }
    },

    load: async (opts) => {
      const token = ++loadToken;
      // A silent reload (see LibraryState.load) skips the 'loading' flip so the table + open player
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

/**
 * Wires the library store to live data: kicks off the initial load, primes the
 * status from a one-shot GET /status, and subscribes to the StatusSocket. The
 * socket drives the live status card and triggers a list refresh when the core
 * pushes a `match.recorded` / `match.enriched` / `retention.swept` frame; a
 * low-disk `error` frame surfaces the banner.
 *
 * On reconnect it reconciles (a silent library refetch + status re-prime) so
 * events missed while the socket was down are picked up.
 *
 * OS notifications (an orphaned recording, low disk) are NOT raised here: a window
 * closed to the tray is destroyed, so the Electron main process owns them.
 *
 * Returns a teardown function that closes the socket and detaches listeners.
 */
export function startLibrary(): () => void {
  const store = useLibraryStore.getState();

  void store.load();

  // Prime status from a one-shot GET /status so the card is not blank before the first WS frame. The
  // same snapshot seeds the low-disk banner: the core's hourly check may have warned long before this
  // window existed, and its one-off `error` frame is gone, so /status carries the latest observation.
  // Mount/reconnect only, never per frame, so a dismissed banner doesn't come back every 5 s.
  const primeStatus = async (): Promise<void> => {
    try {
      const snapshot = await fetchStatus();
      useLibraryStore.getState().setStatus(toStatus(snapshot));
      useLibraryStore.getState().setDiskWarning(snapshot.storage?.lowDisk ?? null);
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

  const offStatus = socket.onStatus((status) => {
    useLibraryStore.getState().setStatus(status);
  });

  const offConn = socket.onConnectionChange((connected) => {
    if (!connected) {
      // On drop, clear status so the card reads "unknown" rather than going stale.
      useLibraryStore.getState().setStatus(null);
      return;
    }
    // Reconnect (or first connect): reconcile so events missed while the socket was down are picked
    // up. A silent reload refreshes in the background without the loading spinner tearing down the
    // open player on every reconnect; primeStatus refills the card before the first status frame.
    void useLibraryStore.getState().load({ silent: true });
    void primeStatus();
  });

  // Low-disk warnings: surface the banner (store state). The OS notification is the main process's.
  const offDisk = socket.onDiskWarning((warning) => {
    useLibraryStore.getState().setDiskWarning(warning);
  });

  // Beyond `status` frames, the socket forwards library-mutating events (match.recorded /
  // match.enriched / match.enrichFailed / match.deleted AND retention.swept) via onEvent. Any of
  // them re-loads the list + counts so an enriched row jumps Unsorted -> its real bucket, a swept or
  // deleted row drops, and the sidebar badges refresh together.
  //
  // Coalesce bursts: a backlog enriching can fire several frames in quick succession. The store's
  // loadToken already prevents stale results from clobbering, but without coalescing each frame still
  // issues its own fetch pair. Collapse a burst into a single reload fired shortly after the first.
  //
  // Silent, so the list refreshes in place: the loading state hides every row until the fetches land,
  // which after each delete (its match.deleted frame) blanked the table and threw away its scroll
  // position. Silent only skips that state; a core that can't be reached still shows the error state,
  // and the first load above still shows the loading one.
  let reloadTimer: ReturnType<typeof setTimeout> | null = null;
  const scheduleReload = (): void => {
    if (reloadTimer !== null) return;
    reloadTimer = setTimeout(() => {
      reloadTimer = null;
      void useLibraryStore.getState().load({ silent: true });
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
