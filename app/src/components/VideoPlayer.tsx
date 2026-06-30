import { useEffect, useRef, useState } from 'react';
import type { MatchSummary, Marker, PauseSpan, Clip } from '../api/client';
import {
  fetchMarkers,
  fetchMatch,
  fetchPauses,
  fetchClips,
  createClip,
  deleteClip,
  setClipStarred,
  clipStreamUrl,
  clipThumbUrl,
  videoStreamUrl,
  StatusSocket,
} from '../api/client';
import { bucketLabelOf } from '../store/buckets';
import { heroDisplayName } from '../data/heroes';
import { useLibraryStore } from '../store/library';
import './video-player.css';

interface VideoPlayerProps {
  // The selected match, if any. Markers, duration, and the VOD file are fetched
  // per-selection and rendered over the stage; real playback degrades gracefully
  // when no .mp4 exists yet (seeded data / pruned retention).
  readonly match: MatchSummary | null;
  // When a clip is selected from the library "Clips" bucket, its id. The selection
  // already pointed `match` at the clip's parent (so the parent VOD + clip strip
  // load); this flags which clip to auto-play once that strip arrives. Null for a
  // plain match selection (play the full VOD).
  readonly initialClipId?: number | null;
  // Bumped by the store on every clip selection (even the same clip id again), so re-selecting a clip
  // after switching to the full VOD replays it. The auto-play effect keys on this, not initialClipId.
  readonly clipPlayToken?: number;
}

// Core marker `type` values are kill/death/assist/roshan, but the scrubber CSS
// keys are death/kill/fight/roshan (video-player.css). Map type → data-kind so
// every marker — including unknown/future replay types — renders a visible bar.
function markerKind(type: string): 'death' | 'kill' | 'fight' | 'roshan' {
  switch (type) {
    case 'death':
      return 'death'; // red   --loss
    case 'kill':
      return 'kill'; // green  --win
    case 'roshan':
      return 'roshan'; // blue  --marker-blue
    case 'assist':
      return 'fight'; // gold   --gold (assist → fight bucket)
    default:
      return 'fight'; // unknown/future types get the neutral gold bar
  }
}

// gameClock is in-game clock seconds (nullable, can be negative pre-horn / during
// a pause). Render as mm:ss; the sign is preserved for negative clocks.
function formatClock(seconds: number): string {
  const sign = seconds < 0 ? '-' : '';
  const abs = Math.abs(Math.trunc(seconds));
  const mm = Math.floor(abs / 60);
  const ss = abs % 60;
  return `${sign}${mm}:${String(ss).padStart(2, '0')}`;
}

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v));
}

// The media element's duration when it's a usable positive, finite number; otherwise null. A
// seeded/empty player reports 0 or NaN, which must not drive the readout, the playhead fill, or
// scrubbing. Single source of truth for "is this duration usable" so the readout/playhead/scrub
// checks can't drift apart.
function usableDuration(v: HTMLVideoElement | null): number | null {
  return v && Number.isFinite(v.duration) && v.duration > 0 ? v.duration : null;
}

// Playback time (non-negative seconds) as M:SS for the transport readout.
function fmtPlayTime(seconds: number): string {
  const s = Number.isFinite(seconds) && seconds > 0 ? Math.trunc(seconds) : 0;
  const mm = Math.floor(s / 60);
  const ss = s % 60;
  return `${mm}:${String(ss).padStart(2, '0')}`;
}

// A clip's display label: its explicit `label`, else a kind-derived fallback
// ("Rampage" for an auto/triggered clip, "Manual" otherwise). Mirrors the
// triggerReason → human-name idea without inventing names for unknown triggers.
function clipLabel(clip: Clip): string {
  if (clip.label != null && clip.label.trim() !== '') return clip.label;
  if (clip.kind === 'auto') {
    return clip.triggerReason === 'rampage' ? 'Rampage' : (clip.triggerReason ?? 'Auto');
  }
  return 'Manual';
}

// A clip span's length as a compact +Ns badge (whole seconds, never negative).
function clipDuration(clip: Clip): string {
  const s = Math.max(0, Math.round(clip.endOffsetS - clip.startOffsetS));
  return `${s}s`;
}

// The 300px video stage. A real <video> sits behind the existing placeholder /
// score / clock / scrubber chrome. Markers are data-driven from GET
// /matches/{id}/markers, positioned by video_offset_s / duration, and click-to-seek
// sets video.currentTime via a ref.
export function VideoPlayer({
  match,
  initialClipId = null,
  clipPlayToken = 0,
}: VideoPlayerProps): React.JSX.Element {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  // Fullscreen targets the whole stage, not the bare <video>: the scrub bar, markers, and
  // controls are siblings of the <video> inside .vp-stage, so fullscreening the element alone
  // would show only the raw video surface (with the browser's native controls) and hide them.
  const stageRef = useRef<HTMLDivElement | null>(null);

  // Marker / duration / video state is LOCAL to the player — the zustand library
  // store is scoped to list/filter/selection and deliberately doesn't carry it.
  const [markers, setMarkers] = useState<readonly Marker[]>([]);
  const [pauses, setPauses] = useState<readonly PauseSpan[]>([]);
  // Wall-clock epoch millis of recording start, the anchor for converting pause
  // spans (also wall-clock) into video offsets. Null on seeded/legacy rows -> the
  // pause loop renders nothing rather than guessing.
  const [recordStartWall, setRecordStartWall] = useState<number | null>(null);
  const [durationS, setDurationS] = useState<number | null>(null);
  // The loaded <video>'s REAL duration, once known. Used for the time readout so it reflects the
  // actual file rather than the DB's recorded estimate (they can disagree). Marker/pause positioning
  // still uses the DB durationS so bars place even on a seeded/no-file row.
  const [mediaDurationS, setMediaDurationS] = useState<number | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [progress, setProgress] = useState(0); // playhead %, driven by timeupdate
  const [currentTimeS, setCurrentTimeS] = useState(0); // playhead seconds, for the time readout
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  // Two-step delete: the first click arms a confirm bar so a permanent delete can't fire on one tap.
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  // Clips cut from this match's VOD, LOCAL to the player (like markers/pauses — not
  // in the zustand store). Fetched per-selection and kept fresh by the clip.* socket.
  const [clips, setClips] = useState<readonly Clip[]>([]);
  // Live generation progress (clip.progress percent) keyed by clip id, so a
  // generating clip can show a percentage rather than a bare spinner.
  const [clipProgress, setClipProgress] = useState<Record<number, number>>({});
  // The clip a play action pointed the <video> at, or null while playing the full
  // VOD. Drives which src the media element loads.
  const [activeClipId, setActiveClipId] = useState<number | null>(null);
  // Clip-range mode: a scissors-armed overlay with draggable in/out handles over the
  // scrub track. inS/outS are video offsets (seconds); null = not arming a clip.
  const [clipRange, setClipRange] = useState<{ inS: number; outS: number } | null>(null);
  const [creatingClip, setCreatingClip] = useState(false);
  // Which handle is being dragged (pointer-move updates the range until release).
  const dragHandleRef = useRef<'in' | 'out' | null>(null);
  // Tracks the clipPlayToken we've already auto-played, so auto-play fires once per library clip
  // selection — not again after the user clicks "Full VOD" (token unchanged → no re-snap on the next
  // clips refresh). A fresh selectClip (even of the same clip id) bumps the token and re-arms it.
  const lastPlayedTokenRef = useRef<number | null>(null);
  // Monotonic sequence token for clip-list fetches, mirroring store/library.ts's loadToken. A local
  // mutation that changes the clip strip (onDeleteClip / onToggleClipStar / onConfirmClip) bumps it;
  // each async clip fetch captures it at the start and drops its setClips() if it's since changed, so
  // an in-flight fetch (e.g. a clip.* WS refresh issued before a delete) can't resolve afterward and
  // resurrect the just-mutated state. The matchId `cancelled` guard still handles selection changes.
  const clipFetchTokenRef = useRef(0);

  const deleteMatch = useLibraryStore((s) => s.deleteMatch);
  // Refresh the library list/counts after a clip delete so the "Clips" bucket + badge stay in sync
  // (the player's clip strip is local state; no WS event fires on delete).
  const reloadLibrary = useLibraryStore((s) => s.load);

  const matchId = match?.id ?? null;

  // Self-contained per-selection fetch: markers + detail (for durationS + video
  // availability) + pauses in parallel via allSettled. Video availability comes
  // from the detail's `videoPath` — non-null/non-blank means the file exists, so
  // videoUrl points at the authed range-streaming endpoint; null/blank (pruned by
  // retention / seeded no-file) leaves videoUrl null and the placeholder renders.
  // No separate /video round-trip needed. Reset on matchId change / null; a
  // `cancelled` guard drops late responses for a superseded selection so
  // out-of-order resolves can't overwrite the current match's state.
  useEffect(() => {
    setMarkers([]);
    setPauses([]);
    setRecordStartWall(null);
    setDurationS(null);
    setMediaDurationS(null);
    setVideoUrl(null);
    setProgress(0);
    setCurrentTimeS(0); // else the readout shows the previous match's position for a video-less row
    setConfirmDelete(false);
    setDeleting(false);
    setClips([]);
    setClipProgress({});
    setActiveClipId(null);
    setClipRange(null);
    setCreatingClip(false);

    if (matchId === null) return;

    let cancelled = false;
    const id = matchId;
    // Capture the clip-fetch token so a local mutation that bumps it mid-flight drops this
    // fetch's setClips() (but not the markers/detail/pauses, which mutations don't touch).
    const clipToken = clipFetchTokenRef.current;

    void Promise.allSettled([
      fetchMarkers(id),
      fetchMatch(id),
      fetchPauses(id),
      fetchClips(id),
    ]).then(([markersRes, detailRes, pausesRes, clipsRes]) => {
      if (cancelled) return; // a newer selection (or unmount) superseded this fetch
      if (markersRes.status === 'fulfilled') setMarkers(markersRes.value);
      if (detailRes.status === 'fulfilled') {
        setDurationS(detailRes.value.durationS);
        setRecordStartWall(detailRes.value.recordStartedWallMs);
        // Only point at the stream when a file actually exists. A blank/null
        // videoPath (pruned/seeded) leaves videoUrl null -> placeholder shows.
        const path = detailRes.value.videoPath;
        if (path != null && path.trim() !== '') setVideoUrl(videoStreamUrl(id));
      }
      // A failed /pauses (none, or seeded) just leaves the span list empty.
      if (pausesRes.status === 'fulfilled') setPauses(pausesRes.value);
      // A failed /clips (none, or seeded) just leaves the clips strip empty. Skip if a
      // local mutation bumped the token after this fetch began (stale pre-mutation data).
      if (clipsRes.status === 'fulfilled' && clipToken === clipFetchTokenRef.current)
        setClips(clipsRes.value);
    });

    return () => {
      cancelled = true;
    };
  }, [matchId]);

  // Auto-play the library-selected clip. When the user clicks a clip in the "Clips" bucket, the store
  // opens its parent match here and bumps clipPlayToken; once that match's clip strip has loaded (and
  // the clip is ready), point the <video> at it. Keyed on clipPlayToken (not initialClipId) so it
  // fires once per selection AND re-fires when the user re-selects the same clip after "Full VOD";
  // clicking "Full VOD" leaves the token unchanged, so a strip refetch won't re-snap to the clip.
  useEffect(() => {
    if (initialClipId === null) {
      lastPlayedTokenRef.current = null;
      return;
    }
    if (lastPlayedTokenRef.current === clipPlayToken) return;
    const target = clips.find((c) => c.id === initialClipId);
    if (target && target.status === 'ready') {
      lastPlayedTokenRef.current = clipPlayToken;
      setActiveClipId(initialClipId);
    }
  }, [initialClipId, clipPlayToken, clips]);

  // Live clip lifecycle for the open match: a dedicated StatusSocket (the shared one
  // lives privately inside startLibrary) scoped to this matchId via onClipEvent.
  // clip.created/clip.ready re-fetch the strip; clip.progress just updates the
  // per-clip percent. Self-contained: the socket auto-reconnects and is closed on
  // unmount / matchId change so there's no leak.
  useEffect(() => {
    if (matchId === null) return;

    let cancelled = false;
    const id = matchId;
    const socket = new StatusSocket();

    const refreshClips = (): void => {
      // Capture the clip-fetch token so a local mutation (delete/star/clip) that bumps it while
      // this WS-triggered fetch is in flight drops the stale pre-mutation rows in .then.
      const clipToken = clipFetchTokenRef.current;
      void fetchClips(id)
        .then((rows) => {
          if (!cancelled && clipToken === clipFetchTokenRef.current) setClips(rows);
        })
        .catch(() => {
          /* transient: the next clip.* frame (or a re-select) reconciles */
        });
    };

    const off = socket.onClipEvent(id, (evt) => {
      if (cancelled) return;
      if (evt.type === 'clip.progress') {
        const { clipId, percent } = evt.payload;
        setClipProgress((prev) => ({ ...prev, [clipId]: percent }));
        return;
      }
      // clip.created / clip.ready: refresh the strip so a new pending row appears and
      // a finished one flips to ready (or failed) with its playable path.
      refreshClips();
    });

    socket.connect();

    return () => {
      cancelled = true;
      off();
      socket.close();
    };
  }, [matchId]);

  // After the <video> src swaps to a selected clip's stream, load + start it. Keyed on
  // activeClipId so it fires once per selection, after React has rendered the new src
  // (avoids playing the stale src). Null (back to full VOD) is a no-op here.
  useEffect(() => {
    if (activeClipId === null) return;
    const v = videoRef.current;
    if (!v) return;
    v.load?.();
    void v.play?.().catch(() => {});
  }, [activeClipId]);

  const caption = match
    ? `${heroDisplayName(match.hero)} · ${bucketLabelOf(match)}`
    : 'Storm Spirit · Mid · 38:12';

  // Seek the <video> to a marker's video offset. Harmless no-op on an empty /
  // seeded video element (no src, no duration) thanks to the try/catch + finite
  // guards.
  function seekTo(offsetS: number): void {
    const v = videoRef.current;
    if (!v) return;
    const target = Math.max(0, offsetS);
    try {
      v.currentTime = Number.isFinite(v.duration) ? Math.min(target, v.duration) : target;
      void v.play?.().catch(() => {}); // optional autoplay; safe if play() rejects
    } catch {
      /* empty/seeded video: setting currentTime is a harmless no-op */
    }
  }

  // Capture the media element's real duration once it (or a fresh src) reports one. Falls back to
  // null for a seeded/empty player so the readout uses the DB estimate instead.
  function handleDurationChange(): void {
    setMediaDurationS(usableDuration(videoRef.current));
  }

  // Live playhead from the media element. duration unknown (seeded empty video)
  // leaves progress at 0 and the fill collapsed — no misleading mock playhead.
  function handleTimeUpdate(): void {
    const v = videoRef.current;
    const d = usableDuration(v);
    if (!v || d === null) {
      setProgress(0);
      setCurrentTimeS(0);
      return;
    }
    setProgress((v.currentTime / d) * 100);
    setCurrentTimeS(v.currentTime);
  }

  // Transport controls, wired to the real <video> element (no-ops on an empty/seeded player).
  function togglePlay(): void {
    const v = videoRef.current;
    if (!v) return;
    if (v.paused) {
      void v.play?.().catch(() => {});
    } else {
      v.pause();
    }
  }

  function toggleMute(): void {
    const v = videoRef.current;
    if (!v) return;
    v.muted = !v.muted;
    setMuted(v.muted);
  }

  function toggleFullscreen(): void {
    // Fullscreen (or exit) the whole stage so the custom scrub bar + markers + controls stay
    // visible. Fullscreening the bare <video> instead shows only the video surface with the
    // browser's native controls, hiding the marker overlay (which lives outside the element).
    const stage = stageRef.current;
    if (!stage) return;
    if (document.fullscreenElement) {
      void document.exitFullscreen?.().catch(() => {});
    } else {
      void stage.requestFullscreen?.().catch(() => {});
    }
  }

  // Enter/Space activates a role="button" control (the glyph controls aren't <button>s).
  const keyActivate = (fn: () => void) => (e: React.KeyboardEvent): void => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      fn();
    }
  };

  // Confirmed delete: removes the row + .mp4/thumbnail. On success the store clears the
  // selection, so this view falls back to the library (no local cleanup needed); on failure
  // the confirm bar stays open so the user can retry.
  async function onConfirmDelete(): Promise<void> {
    if (!match) return;
    setDeleting(true);
    try {
      await deleteMatch(match.id);
    } catch {
      setDeleting(false);
    }
  }

  // Click anywhere on the scrub track to seek by fraction. No-op without a finite
  // media duration.
  function handleScrubClick(e: React.MouseEvent<HTMLDivElement>): void {
    const v = videoRef.current;
    const d = usableDuration(v);
    if (!v || d === null) return;
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const fraction = clamp01((e.clientX - rect.left) / rect.width);
    seekTo(fraction * d);
  }

  // Enter clip-range mode: seed a window around the playhead (lead 2s, trail 8s),
  // clamped to the recording. No-op without a playable video / known duration.
  function enterClipMode(): void {
    const v = videoRef.current;
    const d = usableDuration(v);
    if (!v || d === null) return;
    const t = v.currentTime;
    setClipRange({ inS: Math.max(0, t - 2), outS: Math.min(d, t + 8) });
  }

  function cancelClip(): void {
    dragHandleRef.current = null;
    setClipRange(null);
  }

  // Confirm the armed range: POST the cut, exit clip mode. The created row arrives via
  // the immediate response (pending) and the clip.* socket flips it to ready; refresh
  // the strip from the response so it shows up without waiting for the frame.
  async function onConfirmClip(): Promise<void> {
    if (matchId === null || clipRange === null) return;
    const startOffsetS = Math.min(clipRange.inS, clipRange.outS);
    const endOffsetS = Math.max(clipRange.inS, clipRange.outS);
    if (endOffsetS - startOffsetS <= 0) return; // degenerate window — nothing to cut
    setCreatingClip(true);
    try {
      const created = await createClip(matchId, { startOffsetS, endOffsetS });
      // Bump so an in-flight clip fetch can't resolve afterward and drop the just-created row.
      clipFetchTokenRef.current++;
      setClips((prev) => [...prev, created]);
      setClipRange(null);
    } catch {
      /* leave clip mode armed so the user can retry */
    } finally {
      setCreatingClip(false);
    }
  }

  // Drag an in/out handle: convert the pointer x over the scrub track to a video
  // offset and move that edge. The drag is tracked on the scrub element (pointer
  // capture) so it keeps following past the handle's own bounds.
  function onHandleDrag(e: React.PointerEvent<HTMLDivElement>): void {
    const which = dragHandleRef.current;
    if (which === null || clipRange === null) return;
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    // Map against the media element's REAL duration — the same timebase the playhead,
    // seek, and the offsets POSTed to ffmpeg use. The DB durationS estimate can disagree,
    // which would otherwise land the cut on the wrong sub-range of the VOD.
    const d = usableDuration(videoRef.current) ?? (durationS ?? 0);
    const fraction = clamp01((e.clientX - rect.left) / rect.width);
    const offset = fraction * d;
    setClipRange((prev) =>
      prev === null ? prev : which === 'in' ? { ...prev, inS: offset } : { ...prev, outS: offset },
    );
  }

  function startHandleDrag(which: 'in' | 'out', e: React.PointerEvent<HTMLDivElement>): void {
    e.stopPropagation(); // don't seek-by-click the track
    dragHandleRef.current = which;
    e.currentTarget.setPointerCapture?.(e.pointerId);
  }

  function endHandleDrag(): void {
    dragHandleRef.current = null;
  }

  // Play a ready clip: point the <video> at its stream. A clip whose file isn't ready
  // yet has no playable src, so this is gated on status==='ready'. Playback itself is
  // kicked by the activeClipId effect once the new src has rendered (setting src then
  // calling play() synchronously would race the old src).
  function playClip(clip: Clip): void {
    if (clip.status !== 'ready') return;
    // Leaving clip-arming mode if it was active: while a clip plays the media timebase is
    // clip-relative, so the in/out handles (which map to parent-VOD offsets) would be wrong.
    setClipRange(null);
    dragHandleRef.current = null;
    setActiveClipId(clip.id);
  }

  // Return to the full match VOD (clears the clip src).
  function playFullVod(): void {
    setActiveClipId(null);
  }

  // Delete a clip (unlinks the .mp4, drops the row), then refresh the strip. If the
  // deleted clip was playing, fall back to the full VOD.
  async function onDeleteClip(clip: Clip): Promise<void> {
    try {
      await deleteClip(clip.id);
      if (activeClipId === clip.id) setActiveClipId(null);
      // Bump so an in-flight clip fetch (e.g. a clip.* refresh issued before this delete) can't
      // resolve afterward and resurrect the just-deleted clip with pre-delete data.
      clipFetchTokenRef.current++;
      setClips((prev) => prev.filter((c) => c.id !== clip.id));
      // The strip above is player-local; refresh the store so the library "Clips" bucket list and the
      // sidebar badge drop the deleted clip too (no clip.* socket event fires on delete).
      void reloadLibrary();
    } catch {
      /* leave the row; the next clip.* frame / re-select reconciles */
    }
  }

  // Star/unstar a clip: a starred clip is exempt from the retention sweep. Optimistic flip with revert
  // on failure; refresh the library so the Clips bucket reflects the new star state.
  async function onToggleClipStar(clip: Clip): Promise<void> {
    const next = !clip.starred;
    // Bump so an in-flight clip fetch can't resolve afterward and revert the optimistic flip.
    clipFetchTokenRef.current++;
    setClips((prev) => prev.map((c) => (c.id === clip.id ? { ...c, starred: next } : c)));
    try {
      await setClipStarred(clip.id, next);
      void reloadLibrary();
    } catch {
      setClips((prev) => prev.map((c) => (c.id === clip.id ? { ...c, starred: clip.starred } : c)));
    }
  }

  const dur = durationS ?? 0;
  // The time readout prefers the actual loaded media duration (the DB value is a recorded estimate
  // that can disagree); falls back to the DB duration before metadata loads / on a no-file row.
  const readoutDur = mediaDurationS ?? dur;
  // Markers, pause spans, and the clip-range overlay must all align with the PLAYHEAD, which moves on
  // the loaded media's real duration. The DB durationS can disagree — notably, enrichment overwrites
  // it with the OpenDota IN-GAME length, which is shorter than the VIDEO (the recording also spans
  // draft / pre-game / post-game). Positioning on durationS drifts every marker rightward and
  // overflows any past the in-game end. So position on the media duration; fall back to durationS only
  // before metadata loads / on a no-file row (marker.videoOffsetS is already in the video timebase).
  const scrubDur = mediaDurationS ?? dur;
  // Place bars whenever we have a usable positive duration (media if loaded, else the DB value);
  // otherwise hide them rather than pile every marker at 0 (a misleading stack).
  const canPosition = scrubDur > 0;
  // Assist markers are hidden from the scrub bar: a teamfight racks up many assists, which clutter
  // the timeline and bury the kill/death moments that matter. They're still tagged + stored and
  // counted in KDA — just not drawn here.
  const visibleMarkers = markers.filter((m) => m.type !== 'assist');

  // Clip controls (scissors + range handles) only make sense over a real, playable
  // VOD — disabled on a seeded / pruned no-file row.
  const hasVideo = videoUrl !== null;
  // Clipping is only valid against the FULL VOD: while a clip is playing (activeClipId set) the media
  // element's currentTime/duration are clip-relative, but createClip sends parent-VOD offsets — so
  // arming the scissors then would cut the wrong range. Disable it until "Full VOD" is selected.
  const canClip = hasVideo && activeClipId === null;
  // The src the media element loads: a playing clip's stream when one is selected,
  // else the full match VOD (or nothing on a no-file row).
  const playSrc = activeClipId !== null ? clipStreamUrl(activeClipId) : (videoUrl ?? undefined);

  return (
    <div className="vp-stage" ref={stageRef}>
      <div className="vp-hatch" aria-hidden="true" />

      {/* Real VOD behind the chrome. videoUrl points at the authed loopback range
          stream (GET /matches/{id}/video/stream) — a plain http(s) media load that
          Chromium can seek; the bridge token rides the ?token= query param since a
          <video> element can't set the X-Dotarec-Token header.
          src omitted when videoUrl is null (not fetched, or seeded/pruned no-file):
          an empty media element over which markers + scrubber still render. */}
      <video
        ref={videoRef}
        className="vp-video"
        src={playSrc}
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={handleDurationChange}
        onDurationChange={handleDurationChange}
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onVolumeChange={(e) => setMuted(e.currentTarget.muted)}
        playsInline
        preload="metadata"
      />

      {videoUrl === null && (
        <div className="vp-placeholder">
          <div className="vp-placeholder-tag">[ no video · recording removed ]</div>
          <div className="vp-placeholder-sub">{caption}</div>
        </div>
      )}

      {match && (
        <div className="vp-strip">
          <span className="vp-pill vp-pill-score">{caption}</span>
        </div>
      )}

      <div className="vp-controls">
        <div
          className="vp-scrub"
          onClick={handleScrubClick}
          onPointerMove={clipRange !== null ? onHandleDrag : undefined}
          onPointerUp={clipRange !== null ? endHandleDrag : undefined}
        >
          <div className="vp-scrub-fill" style={{ width: `${progress}%` }} />
          <div className="vp-scrub-head" style={{ left: `${progress}%` }} />
          {/* Dimmed pause spans render BEHIND the markers/playhead (lower z-index,
              pointer-events:none in CSS) so they never intercept seek clicks. Only
              when we have a positive duration AND a record-start anchor; seeded rows
              (null anchor) render nothing rather than mis-position. */}
          {canPosition &&
            recordStartWall !== null &&
            pauses.map((p) => {
              // Convert wall-clock pause edges into video offsets relative to the
              // record-start anchor. An open pause (null endWall) extends to the
              // known end of the recording.
              const anchor = recordStartWall;
              const startOffsetS = (p.startWall - anchor) / 1000;
              const endOffsetS = p.endWall != null ? (p.endWall - anchor) / 1000 : scrubDur;
              if (Number.isNaN(startOffsetS) || Number.isNaN(endOffsetS)) return null;
              const startPct = Math.min(100, Math.max(0, (startOffsetS / scrubDur) * 100));
              const endPct = Math.min(100, Math.max(0, (endOffsetS / scrubDur) * 100));
              const widthPct = Math.max(0, endPct - startPct);
              if (widthPct <= 0) return null; // degenerate span collapses to nothing
              return (
                <div
                  key={p.id}
                  className="vp-pause"
                  style={{ left: `${startPct}%`, width: `${widthPct}%` }}
                  title="paused"
                  aria-hidden="true"
                />
              );
            })}
          {canPosition &&
            visibleMarkers.map((m) => {
              const offset = m.videoOffsetS;
              // Defensive: Marker.videoOffsetS is typed non-null, but skip a
              // malformed null/NaN offset rather than position it at NaN%.
              if (offset == null || Number.isNaN(offset)) return null;
              // Clamp to [0,100]%: offsets past the recorded end pin to the right
              // edge instead of overflowing the scrubber.
              const pct = Math.min(100, Math.max(0, (offset / scrubDur) * 100));
              const title = `${m.type}${
                m.gameClock != null && Number.isFinite(m.gameClock)
                  ? ` · ${formatClock(m.gameClock)}`
                  : ''
              }`;
              return (
                <div
                  key={m.id}
                  className="vp-marker"
                  data-kind={markerKind(m.type)}
                  style={{ left: `${pct}%` }}
                  title={title}
                  role="button"
                  tabIndex={0}
                  onClick={(e) => {
                    e.stopPropagation(); // don't also trigger scrub-by-click
                    seekTo(offset);
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      seekTo(offset);
                    }
                  }}
                />
              );
            })}
          {/* Clip-range overlay: a highlighted span between the two draggable in/out
              handles. Reuses the same offset/dur fraction math as markers. Only shown
              while arming a clip with a positive duration. */}
          {clipRange !== null &&
            canPosition &&
            (() => {
              const lo = Math.min(clipRange.inS, clipRange.outS);
              const hi = Math.max(clipRange.inS, clipRange.outS);
              const inPct = Math.min(100, Math.max(0, (clipRange.inS / scrubDur) * 100));
              const outPct = Math.min(100, Math.max(0, (clipRange.outS / scrubDur) * 100));
              const loPct = Math.min(100, Math.max(0, (lo / scrubDur) * 100));
              const hiPct = Math.min(100, Math.max(0, (hi / scrubDur) * 100));
              return (
                <>
                  <div
                    className="vp-clip-span"
                    style={{ left: `${loPct}%`, width: `${Math.max(0, hiPct - loPct)}%` }}
                    aria-hidden="true"
                  />
                  <div
                    className="vp-clip-handle"
                    data-edge="in"
                    style={{ left: `${inPct}%` }}
                    role="slider"
                    tabIndex={0}
                    aria-label="Clip start"
                    onPointerDown={(e) => startHandleDrag('in', e)}
                    onClick={(e) => e.stopPropagation()}
                  />
                  <div
                    className="vp-clip-handle"
                    data-edge="out"
                    style={{ left: `${outPct}%` }}
                    role="slider"
                    tabIndex={0}
                    aria-label="Clip end"
                    onPointerDown={(e) => startHandleDrag('out', e)}
                    onClick={(e) => e.stopPropagation()}
                  />
                </>
              );
            })()}
        </div>

        <div className="vp-controls-row">
          <span
            className="vp-play"
            role="button"
            tabIndex={0}
            aria-label={playing ? 'Pause' : 'Play'}
            onClick={togglePlay}
            onKeyDown={keyActivate(togglePlay)}
          >
            {playing ? '⏸' : '▶'}
          </span>
          <span
            className="vp-icon"
            role="button"
            tabIndex={0}
            aria-label={muted ? 'Unmute' : 'Mute'}
            onClick={toggleMute}
            onKeyDown={keyActivate(toggleMute)}
          >
            {muted ? '🔇' : '🔊'}
          </span>
          <span className="vp-time">
            {fmtPlayTime(currentTimeS)} / {fmtPlayTime(readoutDur)}
          </span>
          <span className="vp-controls-spacer" />
          {/* Clip control. Idle: a scissors glyph arms clip-range mode. While arming:
              an inline confirm/cancel pair (POST the cut / exit). Disabled with no
              playable video. */}
          {match && clipRange === null && (
            <span
              className="vp-icon vp-clip"
              role="button"
              tabIndex={canClip ? 0 : -1}
              aria-label="Clip"
              aria-disabled={!canClip}
              title={
                !hasVideo
                  ? 'No video to clip'
                  : activeClipId !== null
                    ? 'Return to the full VOD to clip'
                    : 'Clip a moment'
              }
              data-disabled={!canClip}
              onClick={() => canClip && enterClipMode()}
              onKeyDown={keyActivate(() => canClip && enterClipMode())}
            >
              ✂
            </span>
          )}
          {clipRange !== null && (
            <span className="vp-clip-actions">
              <button
                type="button"
                className="vp-clip-confirm"
                onClick={() => void onConfirmClip()}
                disabled={creatingClip}
              >
                {creatingClip ? 'Clipping…' : 'Clip'}
              </button>
              <button
                type="button"
                className="vp-clip-cancel"
                onClick={cancelClip}
                disabled={creatingClip}
              >
                Cancel
              </button>
            </span>
          )}
          <span
            className="vp-icon"
            role="button"
            tabIndex={0}
            aria-label="Fullscreen"
            onClick={toggleFullscreen}
            onKeyDown={keyActivate(toggleFullscreen)}
          >
            ⛶
          </span>
          {match && (
            <span
              className="vp-icon vp-delete"
              role="button"
              tabIndex={0}
              aria-label="Delete recording"
              title="Delete recording"
              onClick={() => setConfirmDelete(true)}
              onKeyDown={keyActivate(() => setConfirmDelete(true))}
            >
              🗑
            </span>
          )}
        </div>
      </div>

      {/* Per-match clips strip under the player. One row per clip: its label +
          duration, a status indicator (generating spinner / ready / failed), a play
          action that points the <video> at the clip stream, and a delete action.
          Hidden when the match has no clips. */}
      {match && clips.length > 0 && (
        <div className="vp-clips" aria-label="Clips">
          {activeClipId !== null && (
            <span
              className="vp-clip-back"
              role="button"
              tabIndex={0}
              title="Back to full recording"
              onClick={playFullVod}
              onKeyDown={keyActivate(playFullVod)}
            >
              ← Full VOD
            </span>
          )}
          {clips.map((clip) => {
            const pct = clipProgress[clip.id];
            return (
              <div
                className="vp-clip-item"
                key={clip.id}
                data-status={clip.status}
                data-active={activeClipId === clip.id}
              >
                {/* Clip thumbnail (GET /clips/{id}/thumb), only for a ready clip with a
                    rendered thumb. The endpoint 404s on not-ready/missing files, so an
                    onError handler hides the broken <img> and the label/icon below stand
                    in. Non-ready clips skip the <img> entirely (no flash of a 404). */}
                {clip.status === 'ready' && clip.thumbPath != null && (
                  <img
                    className="vp-clip-thumb"
                    src={clipThumbUrl(clip.id)}
                    alt=""
                    aria-hidden="true"
                    onError={(e) => {
                      e.currentTarget.style.display = 'none';
                    }}
                  />
                )}
                <span
                  className="vp-clip-play"
                  role="button"
                  tabIndex={clip.status === 'ready' ? 0 : -1}
                  aria-label={`Play clip ${clipLabel(clip)}`}
                  aria-disabled={clip.status !== 'ready'}
                  onClick={() => playClip(clip)}
                  onKeyDown={keyActivate(() => playClip(clip))}
                >
                  ▶
                </span>
                <span className="vp-clip-label">{clipLabel(clip)}</span>
                <span className="vp-clip-dur">{clipDuration(clip)}</span>
                <span className="vp-clip-status" data-status={clip.status}>
                  {clip.status === 'ready' && '●'}
                  {clip.status === 'failed' && '⚠'}
                  {(clip.status === 'pending' || clip.status === 'generating') &&
                    (pct != null ? `${Math.round(pct)}%` : '⟳')}
                </span>
                <span
                  className="vp-icon vp-clip-star"
                  role="button"
                  tabIndex={0}
                  aria-label={clip.starred ? `Unstar clip ${clipLabel(clip)}` : `Star clip ${clipLabel(clip)}`}
                  aria-pressed={clip.starred}
                  title={clip.starred ? 'Starred — kept from auto-delete' : 'Star to keep from auto-delete'}
                  data-on={clip.starred ? 'true' : 'false'}
                  onClick={() => void onToggleClipStar(clip)}
                  onKeyDown={keyActivate(() => void onToggleClipStar(clip))}
                >
                  {clip.starred ? '★' : '☆'}
                </span>
                <span
                  className="vp-icon vp-clip-del"
                  role="button"
                  tabIndex={0}
                  aria-label={`Delete clip ${clipLabel(clip)}`}
                  title="Delete clip"
                  onClick={() => void onDeleteClip(clip)}
                  onKeyDown={keyActivate(() => void onDeleteClip(clip))}
                >
                  🗑
                </span>
              </div>
            );
          })}
        </div>
      )}

      {confirmDelete && (
        <div className="vp-confirm" role="alertdialog" aria-label="Confirm delete recording">
          <span className="vp-confirm-text">Delete this recording permanently?</span>
          <div className="vp-confirm-actions">
            <button
              type="button"
              className="vp-confirm-del"
              onClick={() => void onConfirmDelete()}
              disabled={deleting}
            >
              {deleting ? 'Deleting…' : 'Delete'}
            </button>
            <button
              type="button"
              className="vp-confirm-cancel"
              onClick={() => setConfirmDelete(false)}
              disabled={deleting}
            >
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
