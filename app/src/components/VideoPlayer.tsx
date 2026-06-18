import type { MatchSummary } from '../api/client';
import './video-player.css';

interface VideoPlayerProps {
  // The selected match, if any, so the placeholder caption can name it. Real VOD
  // playback, marker positions, and seek land in Phase B — for now this is a
  // styled placeholder regardless of selection.
  readonly match: MatchSummary | null;
}

// Static marker positions matching the mockup. In Phase B these are replaced by
// real marker.video_offset_s values from GET /matches/{id}/markers.
const PLACEHOLDER_MARKERS: readonly {
  readonly left: string;
  readonly kind: 'death' | 'kill' | 'fight' | 'roshan';
  readonly title: string;
}[] = [
  { left: '18%', kind: 'death', title: 'death' },
  { left: '31%', kind: 'kill', title: 'kill' },
  { left: '58%', kind: 'fight', title: 'team fight' },
  { left: '72%', kind: 'roshan', title: 'roshan' },
];

// The 300px video stage. PLACEHOLDER visuals only (per the brief): the radial bg +
// hatch, the score / clock pills, a styled scrubber with marker bars, and a
// controls row. Nothing here fetches video or markers, so it is safe against empty
// data — clicking a row selects a match but real playback is deferred.
export function VideoPlayer({ match }: VideoPlayerProps): React.JSX.Element {
  const caption = match
    ? `${match.hero || 'Unknown hero'} · ${match.category}`
    : 'Storm Spirit · Mid · 38:12';

  return (
    <div className="vp-stage">
      <div className="vp-hatch" aria-hidden="true" />

      <div className="vp-placeholder">
        <div className="vp-placeholder-tag">[ match VOD · 1920×1080 ]</div>
        <div className="vp-placeholder-sub">{caption}</div>
      </div>

      <div className="vp-strip">
        <span className="vp-pill vp-pill-score">RADIANT 31 — 24 DIRE</span>
        <span className="vp-pill vp-pill-clock">⏱ 38:12</span>
      </div>

      <div className="vp-controls">
        <div className="vp-scrub">
          <div className="vp-scrub-fill" />
          <div className="vp-scrub-head" />
          {PLACEHOLDER_MARKERS.map((m) => (
            <div
              key={`${m.kind}-${m.left}`}
              className="vp-marker"
              data-kind={m.kind}
              style={{ left: m.left }}
              title={m.title}
            />
          ))}
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
