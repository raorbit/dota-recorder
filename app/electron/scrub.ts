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

export interface ScrubbedChunk {
  /** Complete, newline-terminated log lines from this chunk (blank lines dropped), each scrubbed. */
  readonly lines: string[];
  /** The trailing incomplete segment (after the last newline) to carry into the next chunk. */
  readonly leftover: string;
}

// Turn one raw stdout/stderr chunk — with any partial line carried over from the previous chunk
// prepended — into scrubbed complete lines plus the new trailing partial line to buffer. Buffering
// that partial line is what closes the chunk-boundary hole: a child's stdout arrives in arbitrary
// slices, so a secret can straddle two 'data' events. Scrubbing each slice independently would match
// neither half and leak the secret into the durable electron.log, and would emit a partial line as if
// it were complete. Only newline-terminated lines are emitted here; the caller flushes the residual
// `leftover` when the stream ends (a final line the child wrote without a trailing newline).
export function scrubChunk(
  leftover: string,
  chunk: string,
  secrets: ReadonlyArray<string | undefined>,
): ScrubbedChunk {
  const parts = (leftover + chunk).split(/\r?\n/);
  // The last element is the segment after the final newline: empty when the chunk ended on a newline,
  // otherwise an incomplete line. Hold it back for the next chunk instead of emitting it.
  const nextLeftover = parts.pop() ?? '';
  const lines: string[] = [];
  for (const part of parts) {
    if (part.length === 0) continue; // drop blank lines, mirroring the old per-line emit
    lines.push(scrubSecrets(part, secrets));
  }
  return { lines, leftover: nextLeftover };
}
