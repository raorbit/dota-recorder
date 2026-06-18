import type { StatusSnapshot } from '../../api/client';
import type { SettingsTab } from '../../components/Sidebar';
import { SceneObsPanel } from './SceneObsPanel';
import { GsiSettings } from './GsiSettings';
import './settings-view.css';

interface SettingsViewProps {
  readonly tab: SettingsTab;
  // Live snapshot, lifted from the library store, so each panel can show its own
  // connectivity (OBS for Scene & OBS, GSI for Game State Integration).
  readonly snapshot: StatusSnapshot | null;
}

// General settings placeholder. The /settings endpoint backs resolution / encoder
// / retention / video dir, but the only built form so far is Scene & OBS. Rather
// than ship a half-wired General form, this names what will live here.
function GeneralSettings(): React.JSX.Element {
  return (
    <section className="general-panel" aria-label="General settings">
      <header className="general-head">
        <h2 className="general-title">General</h2>
      </header>
      <p className="general-note">
        Recording resolution, encoder, capture folder, and disk-retention cap will
        live here. For now, configure capture under <strong>Scene &amp; OBS</strong>{' '}
        and the live feed under <strong>Game State Integration</strong>.
      </p>
    </section>
  );
}

// Settings host: routes the sidebar's SETTINGS group to the matching panel,
// preserving the existing SceneObsPanel and GsiSettings. Lives in the Main column
// where the library normally renders.
export function SettingsView({ tab, snapshot }: SettingsViewProps): React.JSX.Element {
  return (
    <div className="settings-view">
      {tab === 'general' && <GeneralSettings />}
      {tab === 'sceneObs' && <SceneObsPanel obs={snapshot?.obs ?? null} />}
      {tab === 'gsi' && <GsiSettings gsi={snapshot?.gsi ?? null} />}
    </div>
  );
}
