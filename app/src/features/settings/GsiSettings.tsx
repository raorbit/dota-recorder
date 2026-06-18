import type { StatusSnapshot } from '../../api/client';
import './gsi-settings.css';

interface GsiSettingsProps {
  // Live GSI status, lifted from the shared StatusSocket. Null until the first
  // frame (or while the core is unreachable) so we show "unknown" rather than a
  // misleading "disconnected".
  readonly gsi: StatusSnapshot['gsi'] | null;
}

function gsiStatusLabel(gsi: StatusSnapshot['gsi'] | null): {
  readonly text: string;
  readonly state: 'unknown' | 'disconnected' | 'connected';
} {
  if (gsi === null) return { text: 'unknown', state: 'unknown' };
  if (!gsi.connected) return { text: 'no frames received', state: 'disconnected' };
  const ago = gsi.lastFrameAgoMs;
  if (ago !== null) {
    return { text: `receiving · last frame ${Math.round(ago / 1000)}s ago`, state: 'connected' };
  }
  return { text: 'receiving frames', state: 'connected' };
}

// Game State Integration settings/help. The core owns the GSI listener on
// localhost :3223; this panel surfaces its live health and the one-time .cfg
// install the user must do for Dota to start POSTing state. It is read-only:
// there is no config write endpoint yet, so this is guidance + status, never a
// form that could break against an empty/unreachable core.
export function GsiSettings({ gsi }: GsiSettingsProps): React.JSX.Element {
  const status = gsiStatusLabel(gsi);

  return (
    <section className="gsi-panel" aria-label="Game State Integration settings">
      <header className="gsi-panel-head">
        <h2 className="gsi-panel-title">Game State Integration</h2>
        <div className="gsi-conn" data-state={status.state}>
          <span className="gsi-conn-dot" data-state={status.state} aria-hidden="true" />
          <span className="gsi-conn-text">{status.text}</span>
        </div>
      </header>

      <p className="gsi-intro">
        Dota 2 broadcasts live match state (clock, your hero, K/D/A, game phase) to a
        local HTTP listener this app runs. That feed triggers recording and tags
        timeline moments. The listener is always on at{' '}
        <code className="gsi-code">127.0.0.1:3223</code>; the dot above turns green
        once Dota is POSTing frames.
      </p>

      <div className="gsi-doc" role="note">
        <div className="gsi-doc-title">One-time setup</div>
        <ol className="gsi-doc-steps">
          <li>
            Locate your Dota 2 config folder:{' '}
            <code className="gsi-code">
              …/steamapps/common/dota 2 beta/game/dota/cfg/gamestate_integration/
            </code>{' '}
            (create the <code className="gsi-code">gamestate_integration</code> folder
            if it does not exist).
          </li>
          <li>
            Add a file named{' '}
            <code className="gsi-code">gamestate_integration_dota2recorder.cfg</code>{' '}
            that points GSI at <code className="gsi-code">http://127.0.0.1:3223/</code>{' '}
            and requests the map, hero, and player blocks.
          </li>
          <li>
            Restart Dota 2. With a match in progress the status above goes green and
            recording arms automatically.
          </li>
        </ol>
        <p className="gsi-doc-foot">
          GSI gives full fidelity for <strong>your</strong> player; allies and enemies
          are coarse without spectating. Precise kill/Roshan timings are backfilled
          later from the match replay.
        </p>
      </div>
    </section>
  );
}
