import { useEffect, useState } from 'react';
import { fetchStorageUsage, type StorageUsage } from '../../../api/client';

// Per-drive disk usage backs the free/total readout and the cap-exceeds-drive warning.
// Fetched on mount and re-fetched after a save (so a newly added drive gets stat'd) — the
// parent calls refreshUsage() in its onSave.
export function useStorageUsage(): {
  usage: StorageUsage | null;
  refreshUsage: () => void;
} {
  // Live per-drive disk usage that backs the free/total readout + the cap-exceeds-drive
  // warning. usage is keyed by path at render.
  const [usage, setUsage] = useState<StorageUsage | null>(null);

  const refreshUsage = (): void => {
    void (async (): Promise<void> => {
      try {
        setUsage(await fetchStorageUsage());
      } catch {
        /* leave the prior usage; the readout degrades to em dashes */
      }
    })();
  };

  useEffect(() => {
    refreshUsage();
  }, []);

  return { usage, refreshUsage };
}
