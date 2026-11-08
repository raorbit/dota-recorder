// A short-lived marker that tells the NEXT launch to start in the tray. Written right before an
// unattended (idle-in-the-tray) update install hands off to the NSIS installer, which relaunches the
// app afterwards: without it the relaunch would pop the window onto a desktop nobody asked to see.
// Consumed (read and deleted) on startup, and honoured only while fresh, so a relaunch that never
// came — the install failed and the app recovered, or the user launched by hand days later — can't
// make an ordinary launch start hidden.
import * as fs from 'node:fs';
import * as path from 'node:path';

export const RELAUNCH_HIDDEN_MARKER = 'relaunch-hidden';

/** A marker older than this is stale and ignored. An install + relaunch takes well under a minute. */
export const RELAUNCH_MARKER_MAX_AGE_MS = 5 * 60_000;

function markerPath(userDataDir: string): string {
  return path.join(userDataDir, RELAUNCH_HIDDEN_MARKER);
}

/** Best-effort: a failed write only means the relaunch shows the window. */
export function writeRelaunchHiddenMarker(userDataDir: string, nowMs: number): void {
  try {
    fs.mkdirSync(userDataDir, { recursive: true });
    fs.writeFileSync(markerPath(userDataDir), String(nowMs), 'utf8');
  } catch {
    /* cosmetic only. */
  }
}

export function clearRelaunchHiddenMarker(userDataDir: string): void {
  try {
    fs.rmSync(markerPath(userDataDir), { force: true });
  } catch {
    /* cosmetic only. */
  }
}

/** Reads AND removes the marker. True only when it exists and was written within `maxAgeMs`. */
export function consumeRelaunchHiddenMarker(
  userDataDir: string,
  nowMs: number,
  maxAgeMs = RELAUNCH_MARKER_MAX_AGE_MS,
): boolean {
  let writtenAt: number;
  try {
    writtenAt = Number(fs.readFileSync(markerPath(userDataDir), 'utf8').trim());
  } catch {
    return false;
  }
  clearRelaunchHiddenMarker(userDataDir);
  const age = nowMs - writtenAt;
  return Number.isFinite(writtenAt) && age >= 0 && age <= maxAgeMs;
}
