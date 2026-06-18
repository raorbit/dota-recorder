import { useEffect, useState } from 'react';
import { fetchHealth, type Health } from './api/client';

type ConnState = 'connecting' | 'connected' | 'error';

// Step 0 skeleton: poll /health and reflect core connectivity. The pixel-faithful
// browse UI (sidebar, video player, match table) is a later step - this is a
// placeholder so the wiring is verifiable end to end.
export function App(): React.JSX.Element {
  const [state, setState] = useState<ConnState>('connecting');
  const [health, setHealth] = useState<Health | null>(null);

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

  return (
    <main className="app-shell">
      <header className="app-titlebar">
        <span className="brand-diamond" aria-hidden="true" />
        <span className="brand-name">Dota 2 Recorder</span>
        <span className="brand-version">v0.1.0</span>
      </header>

      <section className="app-body">
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
          Electron shell can reach the recorder core.
        </p>
      </section>
    </main>
  );
}
