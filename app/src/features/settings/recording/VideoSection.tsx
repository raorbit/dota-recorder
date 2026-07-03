import type { Dispatch, SetStateAction } from 'react';
import {
  ENCODER_LABELS,
  FORMAT_PRESETS,
  FPS_PRESETS,
  QUALITY_PRESETS,
  withStoredOption,
} from './settings-helpers';
import type { SaveState } from './settings-helpers';

// The encoder-override picker offers `auto` (the blank sentinel — re-arms the GPU
// probe at boot) plus the four EncoderProbe tokens. Any other string silently falls
// back to x264 in OBS, so only these are offered.
const ENCODER_OVERRIDE_TOKENS: ReadonlyArray<string> = ['x264', 'nvenc', 'qsv', 'amd'];

interface VideoSectionProps {
  readonly encoderChoice: string;
  readonly setEncoderChoice: Dispatch<SetStateAction<string>>;
  // The core's auto-probed encoder token, shown in the "Auto — …" option label.
  readonly encoderToken: string;
  readonly fps: number;
  readonly setFps: Dispatch<SetStateAction<number>>;
  readonly quality: string;
  readonly setQuality: Dispatch<SetStateAction<string>>;
  readonly recFormat: string;
  readonly setRecFormat: Dispatch<SetStateAction<string>>;
  readonly setSaveState: Dispatch<SetStateAction<SaveState>>;
}

export function VideoSection({
  encoderChoice,
  setEncoderChoice,
  encoderToken,
  fps,
  setFps,
  quality,
  setQuality,
  recFormat,
  setRecFormat,
  setSaveState,
}: VideoSectionProps): React.JSX.Element {
  const qualityOptions = withStoredOption(QUALITY_PRESETS, quality);
  const formatOptions = withStoredOption(FORMAT_PRESETS, recFormat);

  return (
    <section className="rec-card">
      <h3 className="rec-sec">Video</h3>
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label">
            Encoder
            {encoderChoice === 'auto' && <span className="rec-badge">auto</span>}
          </label>
          <p className="rec-desc">
            Auto picks the best hardware encoder for your GPU. Override only if you know which one
            you want.
          </p>
        </div>
        <div className="rec-control">
          <select
            id="rec-encoder"
            className="rec-select"
            aria-label="Encoder"
            value={encoderChoice}
            onChange={(e) => {
              setEncoderChoice(e.target.value);
              setSaveState('idle');
            }}
          >
            <option value="auto">
              Auto — {ENCODER_LABELS[encoderToken] ?? (encoderToken || 'detecting')}
            </option>
            {ENCODER_OVERRIDE_TOKENS.map((t) => (
              <option key={t} value={t}>
                {ENCODER_LABELS[t] ?? t}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label" htmlFor="rec-fps">
            Frame rate
          </label>
          <p className="rec-desc">Frames per second captured into the recording.</p>
        </div>
        <div className="rec-control">
          <select
            id="rec-fps"
            className="rec-select"
            value={fps}
            onChange={(e) => {
              setFps(Number(e.target.value));
              setSaveState('idle');
            }}
          >
            {FPS_PRESETS.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label" htmlFor="rec-quality">
            Quality
          </label>
          <p className="rec-desc">Higher quality means larger files.</p>
        </div>
        <div className="rec-control">
          <select
            id="rec-quality"
            className="rec-select"
            value={quality}
            onChange={(e) => {
              setQuality(e.target.value);
              setSaveState('idle');
            }}
          >
            {qualityOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </div>
      <div className="rec-row">
        <div className="rec-rowlabel">
          <label className="rec-label" htmlFor="rec-format">
            Format
          </label>
          <p className="rec-desc">Recording container. All options are crash-safe.</p>
        </div>
        <div className="rec-control">
          <select
            id="rec-format"
            className="rec-select"
            value={recFormat}
            onChange={(e) => {
              setRecFormat(e.target.value);
              setSaveState('idle');
            }}
          >
            {formatOptions.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </select>
        </div>
      </div>
    </section>
  );
}
