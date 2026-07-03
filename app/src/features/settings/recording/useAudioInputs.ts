import { useState } from 'react';
import { fetchAudioInputs, type AudioInputOption, type AudioSourceKind } from '../../../api/client';

// A per-kind cache of picker options for the audio mixer. Priming is driven by the
// PARENT, not by this hook's own effect: the settings load must resolve first, then the
// three kinds prime (see primeAll, called from the parent's load-settings effect after
// fetchSettings). The cache degrades to [] when OBS is down and each picker still shows
// the stored target.
export function useAudioInputs(): {
  inputsByKind: Record<AudioSourceKind, AudioInputOption[]>;
  refreshInputs: (kind: AudioSourceKind) => void;
  primeAll: () => void;
} {
  const [inputsByKind, setInputsByKind] = useState<Record<AudioSourceKind, AudioInputOption[]>>({
    application: [],
    output: [],
    input: [],
  });

  // Refetch one kind's picker options on demand (e.g. when adding an application
  // source, whose process list is volatile). Best-effort; failures keep the cache.
  const refreshInputs = (kind: AudioSourceKind): void => {
    void (async (): Promise<void> => {
      try {
        const opts = await fetchAudioInputs(kind);
        setInputsByKind((prev) => ({ ...prev, [kind]: opts }));
      } catch {
        /* keep prior options */
      }
    })();
  };

  // Prime each kind's options once the form is up. Called by the parent AFTER
  // fetchSettings resolves so settings load before the audio kinds prime.
  const primeAll = (): void => {
    refreshInputs('application');
    refreshInputs('output');
    refreshInputs('input');
  };

  return { inputsByKind, refreshInputs, primeAll };
}
