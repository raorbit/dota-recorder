import { useEffect, useRef, useState } from 'react';
import type { MatchSummary, Marker } from '../api/client';
import { fetchMarkers, fetchMatch, fetchVideo } from '../api/client';
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

// The 300px video stage. A real <video> sits behind the existing placeholder /
// score / clock / scrubber chrome. Markers are data-driven from GET
// /matches/{id}/markers, positioned by video_offset_s / duration, and click-to-seek
// sets video.currentTime via a ref.
export function VideoPlayer({ match }: VideoPlayerProps): React.JSX.Element {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  // Marker / duration / video state is LOCAL to the player — the zustand library
  // store is scoped to list/filter/selection and deliberately doesn't carry it.
  const [markers, setMarkers] = useState<readonly Marker[]>([]);
  const [durationS, setDurationS] = useState<number | null>(null);
  const [videoUrl, setVideoUrl] = useState<string | null>(null);
  const [progress, setProgress] = useState(0); // playhead %, driven by timeupdate

  const matchId = match?.matchId ?? null;

  // Self-contained per-selection fetch: markers + detail (for durationS) + video
  // (file:// url) in parallel via allSettled, so a 404 on /video (pruned/seeded,
  // no file) still lets markers + duration render. Reset on matchId change /
  // null; a `cancelled` guard drops late responses for a superseded selection so
  // out-of-order resolves can't overwrite the current match's state.
  useEffect(() => {
    setMarkers([]);
    setDurationS(null);
    setVideoUrl(null);
    setProgress(0);

    if (matchId === null) return;

    let cancelled = false;
    const id = matchId;

    void Promise.allSettled([fetchMarkers(id), fetchMatch(id), fetchVideo(id)]).then(
      ([markersRes, detailRes, videoRes]) => {
        if (cancelled) return; // a newer selection (or unmount) superseded this fetch
        if (markersRes.status === 'fulfilled') setMarkers(markersRes.value);
        if (detailRes.status === 'fulfilled') setDurationS(detailRes.value.durationS);
        // 404 (no file) just leaves videoUrl null — markers/duration still render.
        if (videoRes.status === 'fulfilled') setVideoUrl(videoRes.value.url);
      },
    );

    return () => {
      cancelled = true;
    };
  }, [matchId]);

  const caption = match
    ? `${match.hero || 'Unknown hero'} · ${match.category}`
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
      return;
    }
    setProgress((v.currentTime / v.duration) * 100);
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

      {/* Real VOD behind the chrome. file:// works in this Electron renderer; if a
          CSP/origin change later blocks it, add an HTTP-range endpoint core-side
          and point videoUrl at that stream url — no change needed below.
          src omitted when videoUrl is null (not fetched, 404, or seeded no-file):
          an empty media element over which markers + scrubber still render. */}
      <video
        ref={videoRef}
        className="vp-video"
        src={videoUrl ?? undefined}
        onTimeUpdate={handleTimeUpdate}
        playsInline
        preload="metadata"
      />

      {videoUrl === null && (
        <div className="vp-placeholder">
          <div className="vp-placeholder-tag">[ no video · recording removed ]</div>
          <div className="vp-placeholder-sub">{caption}</div>
        </div>
      )}

      <div className="vp-strip">
        <span className="vp-pill vp-pill-score">RADIANT 31 — 24 DIRE</span>
        <span className="vp-pill vp-pill-clock">⏱ 38:12</span>
      </div>

      <div className="vp-controls">
        <div className="vp-scrub" onClick={handleScrubClick}>
          <div className="vp-scrub-fill" style={{ width: `${progress}%` }} />
          <div className="vp-scrub-head" style={{ left: `${progress}%` }} />
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
          <span className="vp-play">▶</span>
          <span className="vp-icon">🔊</span>
          <span className="vp-time">17:34 / 38:12</span>
          <span className="vp-controls-spacer" />
          <span className="vp-time">1×</span>
          <span className="vp-icon">✂</span>
          <span className="vp-icon">⤓</span>
          <span className="vp-icon">⛶</span>
        </div>
      </div>
    </div>
  );
}
