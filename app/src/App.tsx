import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import { fetchHealth, type Health, type StatusSnapshot, StatusSocket } from './api/client';
import { SceneObsPanel } from './features/settings/SceneObsPanel';

type ConnState = 'connecting' | 'connected' | 'error';
type View = 'home' | 'settings';

// App-shell layout tweaks. Kept inline because the shared tokens.css is owned by
// another part of the build; these reuse the same CSS custom properties.
const statusRowStyle: CSSProperties = {
  display: 'flex',
  flexWrap: 'wrap',
  gap: '14px',
};

const navToggleStyle: CSSProperties = {
  marginLeft: 'auto',
  padding: '5px 14px',
  background: 'var(--surface)',
  border: '1px solid var(--border-strong)',
  borderRadius: 'var(--radius-chip)',
  color: 'var(--text-secondary)',
  fontFamily: 'var(--font-display)',
  fontWeight: 600,
  fontSize: '12px',
  letterSpacing: '0.04em',
  cursor: 'pointer',
};

// Step 0 skeleton: poll /health and reflect core connectivity. The pixel-faithful
// browse UI (sidebar, video player, match table) is a later step - this is a
// placeholder so the wiring is verifiable end to end. This step adds a live OBS
// status feed (via StatusSocket) and a Scene & OBS settings view.
export function App(): React.JSX.Element {
  const [state, setState] = useState<ConnState>('connecting');
  const [health, setHealth] = useState<Health | null>(null);
  const [obs, setObs] = useState<StatusSnapshot['obs'] | null>(null);
  const [view, setView] = useState<View>('home');

  useEffect(() => {
    let cancelled = false;

    const poll = async (): Promise<void> => {
      try {
        const h = await fetchHealth();
        if (cancelled) return;
        setHealth(h);
        setState(h.status === 'ok' ? 'connected' : 'error');
      } catch {
        if (cancelled) return;
        setState('error');
      }
    };

    void poll();
    const timer = setInterval(() => void poll(), 3_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  // Live status feed: drives the OBS chip and the settings panel. When the socket
  // drops we clear OBS to null ("unknown") rather than show a stale value.
  useEffect(() => {
    const socket = new StatusSocket();
    const offStatus = socket.onStatus((status) => setObs(status.snapshot.obs));
    const offConn = socket.onConnectionChange((connected) => {
      if (!connected) setObs(null);
    });
    socket.connect();
    return () => {
      offStatus();
      offConn();
      socket.close();
    };
  }, []);

  const obsState: 'unknown' | 'disconnected' | 'connected' | 'recording' =
    obs === null
      ? 'unknown'
      : !obs.connected
        ? 'disconnected'
        : obs.recording
          ? 'recording'
          : 'connected';

  const obsText =
    obs === null
      ? 'unknown'
      : !obs.connected
        ? 'disconnected'
        : obs.recording
          ? 'recording'
          : obs.sceneActive
            ? 'connected'
            : 'connected · no scene';

  return (
    <main className="app-shell">
      <header className="app-titlebar">
        <span className="brand-diamond" aria-hidden="true" />
        <span className="brand-name">Dota 2 Recorder</span>
        <button
          type="button"
          style={navToggleStyle}
          onClick={() => setView((v) => (v === 'settings' ? 'home' : 'settings'))}
        >
          {view === 'settings' ? 'Close' : 'Settings'}
        </button>
        <span className="brand-version">v0.1.0</span>
      </header>

      <section className="app-body">
        <div style={statusRowStyle}>
          <div className="status-card" data-state={state}>
            <span className="status-dot" data-state={state} aria-hidden="true" />
            <div>
              <div className="status-label">Core</div>
              <div className="status-value">
                {state === 'connecting' && 'connecting…'}
                {state === 'connected' && 'connected'}
                {state === 'error' && 'error'}
              </div>
            </div>
          </div>

          <div className="status-card" data-state={obsState === 'unknown' ? 'connecting' : obsState === 'disconnected' ? 'error' : 'connected'}>
            <span className="status-dot" data-state={obsState === 'unknown' ? 'connecting' : obsState === 'disconnected' ? 'error' : 'connected'} aria-hidden="true" />
            <div>
              <div className="status-label">OBS</div>
              <div className="status-value">{obsText}</div>
            </div>
          </div>
        </div>

        {view === 'home' && (
          <>
            {health && (
              <dl className="status-meta">
                <dt>version</dt>
                <dd>{health.version}</dd>
                <dt>schema</dt>
                <dd>{health.schemaVersion}</dd>
                <dt>db</dt>
                <dd>{health.dbReady ? 'ready' : 'not ready'}</dd>
              </dl>
            )}

            <p className="placeholder-note">
              Browse / library UI lands in a later step. This screen confirms the
              Electron shell can reach the recorder core. Open <strong>Settings</strong>{' '}
              to configure OBS capture.
            </p>
          </>
        )}

        {view === 'settings' && <SceneObsPanel obs={obs} />}
      </section>
    </main>
  );
}
