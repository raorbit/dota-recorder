import { useEffect, useRef, useState } from 'react';
import type { MatchSummary, Marker, PauseSpan } from '../api/client';
import { fetchMarkers, fetchMatch, fetchPauses, videoStreamUrl } from '../api/client';
import { bucketLabelOf } from '../store/buckets';
import { useLibraryStore } from '../store/library';
import './video-player.css';

interface VideoPlayerProps {
  // The selected match, if any. Markers, duration, and the VOD file are fetched
  // per-selection and rendered over the stage; real playback degrades gracefully
  // when no .mp4 exists yet (seeded data / pruned retention).
  readonly match: MatchSummary | null;
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

// Playback time (non-negative seconds) as M:SS for the transport readout.
function fmtPlayTime(seconds: number): string {
  const s = Number.isFinite(seconds) && seconds > 0 ? Math.trunc(seconds) : 0;
  const mm = Math.floor(s / 60);
  const ss = s % 60;
  return `${mm}:${String(ss).padStart(2, '0')}`;
}

// The 300px video stage. A real <video> sits behind the existing placeholder /
// score / clock / scrubber chrome. Markers are data-driven from GET
// /matches/{id}/markers, positioned by video_offset_s / duration, and click-to-seek
// sets video.currentTime via a ref.
export function VideoPlayer({ match }: VideoPlayerProps): React.JSX.Element {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  // Marker / duration / video state is LOCAL to the player — the zustand library
  // store is scoped to list/filter/selection and deliberately doesn't carry it.
  const [markers, setMarkers] = useState<readonly Marker[]>([]);
  const [pauses, setPauses] = useState<readonly PauseSpan[]>([]);
  // Wall-clock epoch millis of recording start, the anchor for converting pause
  // spans (also wall-clock) into video offsets. Null on seeded/legacy rows -> the
  // pause loop renders nothing rather than guessing.
  const [recordStartWall, setRecordStartWall] = useState<number | null>(null);
  const [durationS, setDurationS] = useState<number | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [progress, setProgress] = useState(0); // playhead %, driven by timeupdate
  const [currentTimeS, setCurrentTimeS] = useState(0); // playhead seconds, for the time readout
  const [playing, setPlaying] = useState(false);
  const [muted, setMuted] = useState(false);
  // Two-step delete: the first click arms a confirm bar so a permanent delete can't fire on one tap.
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);

  const deleteMatch = useLibraryStore((s) => s.deleteMatch);

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
    setVideoUrl(null);
    setProgress(0);
    setCurrentTimeS(0); // else the readout shows the previous match's position for a video-less row
    setConfirmDelete(false);
    setDeleting(false);

    if (matchId === null) return;

    let cancelled = false;
    const id = matchId;

    void Promise.allSettled([fetchMarkers(id), fetchMatch(id), fetchPauses(id)]).then(
      ([markersRes, detailRes, pausesRes]) => {
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
      },
    );

    return () => {
      cancelled = true;
    };
  }, [matchId]);

  const caption = match
    ? `${match.hero || 'Unknown hero'} · ${bucketLabelOf(match)}`
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

  // Live playhead from the media element. duration unknown (seeded empty video)
  // leaves progress at 0 and the fill collapsed — no misleading mock playhead.
  function handleTimeUpdate(): void {
    const v = videoRef.current;
    if (!v || !Number.isFinite(v.duration) || v.duration <= 0) {
      setProgress(0);
      setCurrentTimeS(0);
      return;
    }
    setProgress((v.currentTime / v.duration) * 100);
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
    void videoRef.current?.requestFullscreen?.().catch(() => {});
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
    if (!v || !Number.isFinite(v.duration) || v.duration <= 0) return;
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0) return;
    const fraction = clamp01((e.clientX - rect.left) / rect.width);
    seekTo(fraction * v.duration);
  }

  const dur = durationS ?? 0;
  // Only place bars when we have a usable positive duration; otherwise hide them
  // rather than pile every marker at 0 (a misleading stack). Seeded rows usually
  // carry a real durationS, so bars position even without a video file.
  const canPosition = dur > 0;

  return (
    <div className="vp-stage">
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
        src={videoUrl ?? undefined}
        onTimeUpdate={handleTimeUpdate}
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
        <div className="vp-scrub" onClick={handleScrubClick}>
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
              const endOffsetS = p.endWall != null ? (p.endWall - anchor) / 1000 : dur;
              if (Number.isNaN(startOffsetS) || Number.isNaN(endOffsetS)) return null;
              const startPct = Math.min(100, Math.max(0, (startOffsetS / dur) * 100));
              const endPct = Math.min(100, Math.max(0, (endOffsetS / dur) * 100));
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
            markers.map((m) => {
              const offset = m.videoOffsetS;
              // Defensive: Marker.videoOffsetS is typed non-null, but skip a
              // malformed null/NaN offset rather than position it at NaN%.
              if (offset == null || Number.isNaN(offset)) return null;
              // Clamp to [0,100]%: offsets past the recorded end pin to the right
              // edge instead of overflowing the scrubber.
              const pct = Math.min(100, Math.max(0, (offset / dur) * 100));
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
            {fmtPlayTime(currentTimeS)} / {fmtPlayTime(dur)}
          </span>
          <span className="vp-controls-spacer" />
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
