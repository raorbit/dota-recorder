import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  RELAUNCH_HIDDEN_MARKER,
  RELAUNCH_MARKER_MAX_AGE_MS,
  clearRelaunchHiddenMarker,
  consumeRelaunchHiddenMarker,
  writeRelaunchHiddenMarker,
} from './relaunch-marker';

let dir: string;

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dotarec-relaunch-'));
});

afterEach(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

describe('relaunch-hidden marker', () => {
  it('is consumed exactly once while fresh', () => {
    writeRelaunchHiddenMarker(dir, 1_000_000);
    expect(fs.existsSync(path.join(dir, RELAUNCH_HIDDEN_MARKER))).toBe(true);
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000 + 20_000)).toBe(true);
    // Gone after the first read: the launch after next starts normally.
    expect(fs.existsSync(path.join(dir, RELAUNCH_HIDDEN_MARKER))).toBe(false);
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000 + 30_000)).toBe(false);
  });

  it('ignores (and removes) a stale marker, so a later manual launch is not hidden', () => {
    writeRelaunchHiddenMarker(dir, 1_000_000);
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000 + RELAUNCH_MARKER_MAX_AGE_MS + 1)).toBe(false);
    expect(fs.existsSync(path.join(dir, RELAUNCH_HIDDEN_MARKER))).toBe(false);
  });

  it('ignores a marker from the future or with garbage in it', () => {
    writeRelaunchHiddenMarker(dir, 2_000_000);
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000)).toBe(false);
    fs.writeFileSync(path.join(dir, RELAUNCH_HIDDEN_MARKER), 'soon', 'utf8');
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000)).toBe(false);
  });

  it('is absent by default and clear() tolerates that', () => {
    expect(consumeRelaunchHiddenMarker(dir, 1_000_000)).toBe(false);
    clearRelaunchHiddenMarker(dir);
    clearRelaunchHiddenMarker(path.join(dir, 'missing', 'deeper'));
  });

  it('creates the directory when writing', () => {
    const nested = path.join(dir, 'a', 'b');
    writeRelaunchHiddenMarker(nested, 5);
    expect(consumeRelaunchHiddenMarker(nested, 10)).toBe(true);
  });
});
