import { useEffect, useState } from 'react';
import { fetchScenePreview, type ScenePreview } from '../../../api/client';

// Live scene preview. Polls GET /obs/preview every ~1s while the settings panel is
// mounted (the parent unmounts on navigation away, so the interval cleanup stops
// polling). Each tick degrades to {dataUri:null} on failure → the UI shows the placeholder.
export function useScenePreview(): ScenePreview | null {
  // Latest polled OBS scene-preview frame (null = no frame / OBS down → placeholder).
  const [preview, setPreview] = useState<ScenePreview | null>(null);

  useEffect(() => {
    let cancelled = false;
    // Skip a tick while the previous fetch is still in flight, so a slow/contended OBS screenshot
    // (up to the 5s fetch timeout) can't stack overlapping requests on the fixed 1s interval.
    let inFlight = false;

    const tick = async (): Promise<void> => {
      if (inFlight) return;
      inFlight = true;
      try {
        const next = await fetchScenePreview();
        if (!cancelled) setPreview(next);
      } catch {
        if (!cancelled) setPreview({ dataUri: null });
      } finally {
        inFlight = false;
      }
    };

    void tick();
    const id = window.setInterval(() => void tick(), 1000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, []);

  return preview;
}
