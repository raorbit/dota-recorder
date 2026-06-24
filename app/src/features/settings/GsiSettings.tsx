import { useCallback, useState } from 'react';
import {
  GSI_LAUNCH_OPTION,
  fetchGsiManualInstructions,
  installGsi,
  type GsiInstallResult,
  type GsiManualInstructions,
  type StatusSnapshot,
} from '../../api/client';
import './gsi-settings.css';

interface GsiSettingsProps {
  // Live GSI status, lifted from the shared StatusSocket. Null until the first
  // frame (or while the core is unreachable) so we show "unknown" rather than a
  // misleading "disconnected".
  readonly gsi: StatusSnapshot['gsi'] | null;
}

// The setup flow is a small state machine: idle -> working -> (installed | manual
// | error). `manual` carries the cfg body the core renders (the SAME bytes the
// auto-install writes), so the on-screen instructions can never drift from reality.
type SetupState =
  | { readonly phase: 'idle' }
  | { readonly phase: 'working' }
  | { readonly phase: 'installed'; readonly result: GsiInstallResult }
  | { readonly phase: 'manual'; readonly instructions: GsiManualInstructions }
  | { readonly phase: 'error'; readonly message: string };

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

function errorMessage(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

// Manual fallback: the exact cfg the core would write, shown verbatim so the user
// can place it by hand when Dota can't be auto-located.
function ManualInstall({
  instructions,
}: {
  readonly instructions: GsiManualInstructions;
}): React.JSX.Element {
  const dir =
    instructions.targetDir ??
    '…/steamapps/common/dota 2 beta/game/dota/cfg/gamestate_integration/';
  return (
    <div className="gsi-result">
      <ol className="gsi-doc-steps">
        <li>
          Create the folder <code className="gsi-code">{dir}</code> if it does not exist.
        </li>
        <li>
          Save a file named{' '}
          <code className="gsi-code">{instructions.cfgFileName}</code> there with exactly
          this content:
        </li>
      </ol>
      <pre className="gsi-cfg">{instructions.cfgBody}</pre>
      <p className="gsi-doc-foot">
        Then add <code className="gsi-code">{GSI_LAUNCH_OPTION}</code> to Dota 2's Steam
        launch options and restart Dota.
      </p>
    </div>
  );
}

// Game State Integration settings. The core owns the GSI listener on localhost
// :3223; this panel surfaces its live health and drives the one-time .cfg install
// (auto into the discovered Dota tree, or a manual fallback) so Dota starts POSTing
// state. The cfg the core writes also carries the auth token the listener checks.
export function GsiSettings({ gsi }: GsiSettingsProps): React.JSX.Element {
  const status = gsiStatusLabel(gsi);
  const [setup, setSetup] = useState<SetupState>({ phase: 'idle' });

  const autoInstall = useCallback(async () => {
    setSetup({ phase: 'working' });
    try {
      const result = await installGsi();
      if (result.installed) {
        setSetup({ phase: 'installed', result });
      } else {
        // Dota not discovered: fall back to the manual cfg the user can place by hand.
        const instructions = await fetchGsiManualInstructions();
        setSetup({ phase: 'manual', instructions });
      }
    } catch (e) {
      setSetup({ phase: 'error', message: errorMessage(e) });
    }
  }, []);

  const showManual = useCallback(async () => {
    setSetup({ phase: 'working' });
    try {
      const instructions = await fetchGsiManualInstructions();
      setSetup({ phase: 'manual', instructions });
    } catch (e) {
      setSetup({ phase: 'error', message: errorMessage(e) });
    }
  }, []);

  const working = setup.phase === 'working';

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
        local HTTP listener this app runs at{' '}
        <code className="gsi-code">127.0.0.1:3223/gsi</code>. That feed triggers recording
        and tags timeline moments; the dot above turns green once Dota is POSTing frames.
      </p>

      <div className="gsi-doc" role="note">
        <div className="gsi-doc-title">One-time setup</div>
        <p className="gsi-intro">
          Let the app write the GSI config into your Dota install, or copy it in yourself.
          Either way you'll add a launch option and restart Dota once.
        </p>

        <div className="gsi-actions">
          <button
            className="gsi-btn"
            type="button"
            onClick={autoInstall}
            disabled={working}
          >
            {working ? 'Working…' : 'Set up automatically'}
          </button>
          <button
            className="gsi-btn gsi-btn-ghost"
            type="button"
            onClick={showManual}
            disabled={working}
          >
            Show manual setup
          </button>
        </div>

        {setup.phase === 'installed' && (
          <div className="gsi-result" role="status">
            <p>
              Installed the GSI config at{' '}
              <code className="gsi-code">{setup.result.cfgPath}</code>.
            </p>
            <p>
              Add <code className="gsi-code">{GSI_LAUNCH_OPTION}</code> to Dota 2's Steam
              launch options, then restart Dota. The status above goes green once a match
              is in progress and recording arms automatically.
            </p>
          </div>
        )}

        {setup.phase === 'manual' && <ManualInstall instructions={setup.instructions} />}

        {setup.phase === 'error' && (
          <p className="gsi-error" role="alert">
            Setup failed: {setup.message}
          </p>
        )}
      </div>

      <p className="gsi-doc-foot">
        GSI gives full fidelity for <strong>your</strong> player; allies and enemies are
        coarse without spectating. Precise kill/Roshan timings are backfilled later from the
        match replay.
      </p>
    </section>
  );
}
