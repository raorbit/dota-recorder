// Size-capped append-only log file for electron.log. Earlier versions appended to it forever (it
// reached ~1 GB on the dev machine), and dev + the installed app share %APPDATA%\dota-recorder\log, so
// installed users grew one too. It now rolls at LOG_MAX_BYTES into electron.log.1 … .LOG_KEEP, oldest
// dropped. A pre-cap log left by an older version is rotated on the first write: only its newest
// LOG_MAX_BYTES are kept (as .1), so the history right before the upgrade survives and the rest goes.
//
// The policy (when to roll, which renames, where the kept tail starts) is pure and unit-tested; the
// RotatingLog class applies it with node:fs.
import * as fs from 'node:fs';

/** Roll electron.log once the next line would take it past this size. */
export const LOG_MAX_BYTES = 10 * 1024 * 1024;
/** Rotated files kept beside the live log (electron.log.1 … .3), so at most ~40 MB in all. */
export const LOG_KEEP = 3;

/** Whether a log holding `size` bytes should roll before `incoming` more are appended. */
export function needsRotation(size: number, incoming: number, maxBytes: number): boolean {
  return size > 0 && size + incoming > maxBytes;
}

export interface RotationPlan {
  /** The oldest generation, deleted first to make room. */
  readonly drop: string;
  /** Renames to run in order: oldest first, so none overwrites a file still waiting to move. */
  readonly moves: ReadonlyArray<{ readonly from: string; readonly to: string }>;
}

/** The deletes and renames that shift `base` → `base.1` → … → `base.keep`, dropping `base.keep`. */
export function rotationPlan(base: string, keep: number): RotationPlan {
  const moves: Array<{ from: string; to: string }> = [];
  for (let i = keep - 1; i >= 1; i--) moves.push({ from: `${base}.${i}`, to: `${base}.${i + 1}` });
  moves.push({ from: base, to: `${base}.1` });
  return { drop: `${base}.${keep}`, moves };
}

/**
 * Index into `tail` (the last bytes of a file too big to keep whole) where the first complete line
 * starts, so the kept copy never opens on half a line. `tail` that starts the file is kept whole, as
 * is one with no newline at all (a single huge line; better a fragment than nothing).
 */
export function firstLineStart(tail: Uint8Array, startsMidFile: boolean): number {
  if (!startsMidFile) return 0;
  const newline = tail.indexOf(0x0a);
  return newline === -1 ? 0 : newline + 1;
}

/**
 * electron.log with rotation. Not safe against a second process appending to the same file at once
 * (its bytes aren't counted here), but a roll re-reads the real size first, so the worst case is a
 * late roll, never a lost or doubled one.
 */
export class RotatingLog {
  // Bytes in the live file as far as this process knows; read from disk on the first append so an
  // existing file (including a legacy uncapped one) is counted.
  private size: number | null = null;

  constructor(
    private readonly file: string,
    private readonly maxBytes = LOG_MAX_BYTES,
    private readonly keep = LOG_KEEP,
  ) {}

  /** Append `text`, rolling the file first if it would go over the cap. Throws only if the append does. */
  append(text: string): void {
    const incoming = Buffer.byteLength(text);
    if (this.size === null) this.size = sizeOf(this.file);
    if (needsRotation(this.size, incoming, this.maxBytes)) {
      this.size = sizeOf(this.file);
      if (needsRotation(this.size, incoming, this.maxBytes)) this.rotate();
    }
    fs.appendFileSync(this.file, text);
    this.size += incoming;
  }

  private rotate(): void {
    const plan = rotationPlan(this.file, this.keep);
    removeQuietly(plan.drop);
    // Every move but the last shifts an older generation; a missing one just means fewer exist yet.
    for (const { from, to } of plan.moves.slice(0, -1)) renameQuietly(from, to);
    try {
      const live = sizeOf(this.file);
      if (live > this.maxBytes) {
        // Only a legacy uncapped log (or a second writer) gets here: keep just its newest bytes.
        copyTail(this.file, `${this.file}.1`, this.maxBytes);
        fs.rmSync(this.file, { force: true });
      } else {
        fs.renameSync(this.file, `${this.file}.1`);
      }
    } catch {
      // Couldn't move it aside (.1 held open by another program, say). Staying bounded matters more
      // than this file's contents, so empty it in place; if even that fails, the next roll retries.
      try {
        fs.truncateSync(this.file, 0);
      } catch {
        /* best-effort. */
      }
    }
    this.size = 0;
  }
}

/** Write the last `maxBytes` of `src` (from its first whole line) to `dest`. */
function copyTail(src: string, dest: string, maxBytes: number): void {
  const fd = fs.openSync(src, 'r');
  try {
    const size = fs.fstatSync(fd).size;
    const length = Math.min(size, maxBytes);
    const tail = Buffer.alloc(length);
    fs.readSync(fd, tail, 0, length, size - length);
    fs.writeFileSync(dest, tail.subarray(firstLineStart(tail, size > length)));
  } finally {
    fs.closeSync(fd);
  }
}

function sizeOf(file: string): number {
  try {
    return fs.statSync(file).size;
  } catch {
    return 0; // not created yet
  }
}

function removeQuietly(file: string): void {
  try {
    fs.rmSync(file, { force: true });
  } catch {
    /* a locked oldest generation just survives a little longer. */
  }
}

function renameQuietly(from: string, to: string): void {
  try {
    fs.renameSync(from, to);
  } catch {
    /* absent (fewer generations so far) or locked: skip it. */
  }
}
