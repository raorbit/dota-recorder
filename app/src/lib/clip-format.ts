// Pure, dependency-free clip formatting shared by the clip list (MatchTable) and the player strip
// (VideoPlayer) so both render an identical label for the same clip. Kept free of React / JSX so it
// can be unit-tested in a plain Node environment (see clip-format.test.ts).
import type { Clip } from '../api/client';

// A clip's display label: its explicit `label`, else a kind-derived fallback ("Rampage" for an
// auto/triggered clip, "Manual" otherwise). Passes an unknown auto trigger reason through as-is
// rather than inventing a name for it.
export function clipLabel(clip: Clip): string {
  if (clip.label != null && clip.label.trim() !== '') return clip.label;
  if (clip.kind === 'auto') {
    return clip.triggerReason === 'rampage' ? 'Rampage' : (clip.triggerReason ?? 'Auto');
  }
  return 'Manual';
}
