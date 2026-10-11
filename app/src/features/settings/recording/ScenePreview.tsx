import type { ScenePreview as ScenePreviewFrame } from '../../../api/client';
// Recorder status as recorderStatusLabel produces it: a display string plus a data-state
// token that drives the badge tint.
import type { RecorderStatus } from '../../../lib/recorder-status';

interface ScenePreviewProps {
  // Latest polled OBS scene-preview frame (null / dataUri null → placeholder).
  readonly preview: ScenePreviewFrame | null;
  readonly status: RecorderStatus;
}

export function ScenePreview({ preview, status }: ScenePreviewProps): React.JSX.Element {
  return (
    <div className="rec-preview">
      {preview?.dataUri ? (
        <img className="rec-preview-img" src={preview.dataUri} alt="Live scene preview" />
      ) : (
        <div className="rec-preview-empty">OBS preview unavailable</div>
      )}
      <span className="rec-preview-badge" data-state={status.state}>
        {status.text}
      </span>
    </div>
  );
}
