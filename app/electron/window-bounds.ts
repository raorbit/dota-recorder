// Whether a window rectangle remembered from the last close is still worth restoring. Pure; main.ts
// feeds it the saved bounds and the work area of the display Electron matches them to, so a window
// last closed on a monitor that has since been unplugged comes back on-screen instead of lost.

export interface Rect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** At least this many pixels of the saved window must overlap the display, in both directions. */
export const MIN_VISIBLE_PX = 100;

/** The saved bounds when they still overlap `workArea` usefully, else null (use the defaults). */
export function restorableBounds(saved: Rect | null, workArea: Rect | null): Rect | null {
  if (saved === null || workArea === null) return null;
  const overlapX = Math.min(saved.x + saved.width, workArea.x + workArea.width) - Math.max(saved.x, workArea.x);
  const overlapY =
    Math.min(saved.y + saved.height, workArea.y + workArea.height) - Math.max(saved.y, workArea.y);
  return overlapX >= MIN_VISIBLE_PX && overlapY >= MIN_VISIBLE_PX ? saved : null;
}
