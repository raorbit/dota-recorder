import { useEffect, useState } from 'react';
import { fetchScenePreview, type ScenePreview } from '../../../api/client';
import { createPreviewPoller } from '../../../lib/preview-poller';

// Live scene preview. Polls GET /obs/preview every ~1s while the settings panel is mounted AND the
// window is visible; a hidden or minimized window polls nothing (each poll renews the core's OBS
// demand lease, so a background poll would keep the on-demand OBS running). The parent unmounts on
// navigation away, which disposes the poller. Each tick degrades to {dataUri:null} on failure → the
// UI shows the placeholder. The polling logic itself lives in lib/preview-poller.ts (unit-tested).
export function useScenePreview(): ScenePreview | null {
  // Latest polled OBS scene-preview frame (null = no frame / OBS down → placeholder).
  const [preview, setPreview] = useState<ScenePreview | null>(null);

  useEffect(() => {
    const poller = createPreviewPoller<ScenePreview>({
      fetchFrame: fetchScenePreview,
      onFrame: (frame) => setPreview(frame ?? { dataUri: null }),
      setInterval: (fn, ms) => window.setInterval(fn, ms),
      clearInterval: (handle) => window.clearInterval(handle as number),
    });
    // Electron's hide-to-tray and minimize both flip visibilityState to 'hidden'.
    const sync = (): void => poller.setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', sync);
    sync();
    return () => {
      document.removeEventListener('visibilitychange', sync);
      poller.dispose();
    };
  }, []);

  return preview;
}
