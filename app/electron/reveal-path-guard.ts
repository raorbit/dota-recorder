import * as fs from 'node:fs';
import * as path from 'node:path';

// Validates a renderer-supplied path before it is handed to shell.showItemInFolder (the
// "Reveal in folder" action). Recordings are always absolute Windows paths from the core's DB,
// so we accept only a non-blank, absolute path with no `..` traversal segment and reject anything
// else — mirroring the core's storage-root containment posture for this class of input. Returns the
// trimmed path to reveal, or null when it fails the guard.
//
// Uses win32 path semantics explicitly (rather than the host-dependent `path`) so a videoPath like
// `C:\…\m.mp4` validates identically regardless of the OS the test runs on.
export function revealablePath(p: unknown): string | null {
  if (typeof p !== 'string') return null;
  const target = p.trim();
  if (target === '' || !path.win32.isAbsolute(target)) return null;
  if (target.split(/[\\/]+/).includes('..')) return null;
  return target;
}

/**
 * Best-effort "does this file still exist?" check that CANNOT hang the caller. The reveal handler runs
 * on the Electron main thread, `revealablePath` accepts UNC paths, and the recording videoDir can be a
 * network share — a synchronous `fs.existsSync` on an unreachable UNC target would block the main event
 * loop (freezing UI, tray, and crash-supervision) for the full SMB timeout. So probe asynchronously via
 * `fs.promises.access` and race it against a short timeout; if the probe hasn't answered by then we
 * report "not accessible" and move on rather than blocking. A timeout is treated as not-accessible so
 * the reveal no-ops (opening an empty folder for a truly-gone file is the outcome we want to avoid), and
 * an unreachable share never wedges the main process.
 */
export async function pathIsAccessible(target: string, timeoutMs = 1_500): Promise<boolean> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  const timeout = new Promise<boolean>((resolve) => {
    timer = setTimeout(() => resolve(false), timeoutMs);
  });
  const probe = fs.promises.access(target).then(
    () => true,
    () => false,
  );
  try {
    return await Promise.race([probe, timeout]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
