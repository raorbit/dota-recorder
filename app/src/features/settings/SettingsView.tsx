import type { StatusSnapshot } from '../../api/client';
import type { SettingsTab } from '../../components/Sidebar';
import { RecordingSettings } from './RecordingSettings';
import { GsiSettings } from './GsiSettings';
import { GeneralSettings } from './GeneralSettings';
import './settings-view.css';

interface SettingsViewProps {
  readonly tab: SettingsTab;
  // Live snapshot, lifted from the library store, so each panel can show its own
  // connectivity (the recorder for Recording, GSI for Game State Integration).
  readonly snapshot: StatusSnapshot | null;
}

// Settings host: routes the sidebar's SETTINGS group to the matching panel. Lives
// in the Main column where the library normally renders. The Recording panel backs
// the resolution / encoder / output-folder knobs from /settings.
export function SettingsView({ tab, snapshot }: SettingsViewProps): React.JSX.Element {
  return (
    <div className="settings-view">
      {tab === 'recording' && <RecordingSettings obs={snapshot?.obs ?? null} />}
      {tab === 'gsi' && <GsiSettings gsi={snapshot?.gsi ?? null} />}
      {tab === 'general' && <GeneralSettings />}
    </div>
  );
}
