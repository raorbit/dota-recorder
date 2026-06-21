import { useEffect, useState } from 'react';
import { WindowFrame } from './components/WindowFrame';
import { Sidebar, type SettingsTab } from './components/Sidebar';
import { VideoPlayer } from './components/VideoPlayer';
import { FilterRow } from './components/FilterRow';
import { MatchTable } from './components/MatchTable';
import { SettingsView } from './features/settings/SettingsView';
import { useLibraryStore, startLibrary } from './store/library';
import './app.css';

type View = 'library' | 'settings';

// The Browse / Library shell. Top-level layout is the custom title bar
// (WindowFrame) over a two-column body: a 230px Sidebar and a fluid Main column.
// Main shows either the library (VideoPlayer + FilterRow + MatchTable) or the
// Settings view, which reaches the RecordingSettings + GsiSettings panels.
//
// Live data (matches, bucket counts, status) is owned by the zustand library
// store; startLibrary() kicks off the initial load and the StatusSocket here.
export function App(): React.JSX.Element {
  const [view, setView] = useState<View>('library');
  const [settingsTab, setSettingsTab] = useState<SettingsTab>('recording');

  const matches = useLibraryStore((s) => s.matches);
  const selectedMatchId = useLibraryStore((s) => s.selectedMatchId);
  const status = useLibraryStore((s) => s.status);

  // Boot the store: initial REST load + StatusSocket subscription. Teardown on
  // unmount closes the socket and detaches listeners.
  useEffect(() => startLibrary(), []);

  const selectedMatch =
    selectedMatchId === null
      ? null
      : (matches.find((m) => m.matchId === selectedMatchId) ?? null);

  const openSettings = (tab: SettingsTab): void => {
    setSettingsTab(tab);
    setView('settings');
  };

  return (
    <WindowFrame>
      <div className="app-grid">
        <Sidebar
          view={view}
          settingsTab={settingsTab}
          onOpenSettings={openSettings}
          onOpenLibrary={() => setView('library')}
        />

        <main className="app-main">
          {view === 'library' ? (
            <>
              <VideoPlayer match={selectedMatch} />
              <FilterRow />
              <MatchTable />
            </>
          ) : (
            <SettingsView tab={settingsTab} snapshot={status?.snapshot ?? null} />
          )}
        </main>
      </div>
    </WindowFrame>
  );
}
