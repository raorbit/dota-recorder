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
