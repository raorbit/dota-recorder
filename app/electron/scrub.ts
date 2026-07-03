// Shared secret-scrubbing for the process supervisors. Electron pipes each child's stdout/stderr
// into electron.log, which is durable and often attached to bug reports; a child that echoes its
// launch args or environment (OBS prints --websocket_password on startup; the core could dump its
// env in a verbose trace) would otherwise leak plaintext secrets into that log.

// Replace every occurrence of each secret in `line` with '***'. Falsy/empty secrets are skipped:
// String.split('') would explode the line into per-character garbage, so an unset token/password is
// a no-op rather than corruption. Secrets are applied left to right; order is irrelevant since the
// '***' sentinel contains none of them.
export function scrubSecrets(line: string, secrets: ReadonlyArray<string | undefined>): string {
  let safe = line;
  for (const secret of secrets) {
    if (secret) safe = safe.split(secret).join('***');
  }
  return safe;
}
