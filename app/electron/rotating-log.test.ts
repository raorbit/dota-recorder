import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { firstLineStart, needsRotation, RotatingLog, rotationPlan } from './rotating-log';

describe('needsRotation', () => {
  it('rolls only when the next line would take the file past the cap', () => {
    expect(needsRotation(90, 10, 100)).toBe(false); // lands exactly on the cap
    expect(needsRotation(91, 10, 100)).toBe(true);
    expect(needsRotation(500, 1, 100)).toBe(true); // already over (a legacy uncapped log)
  });

  it('never rolls an empty file, even for a line bigger than the cap', () => {
    // The line has to go somewhere; rolling an empty file would just churn the generations.
    expect(needsRotation(0, 500, 100)).toBe(false);
  });
});

describe('rotationPlan', () => {
  it('drops the oldest generation, then shifts the rest up oldest-first', () => {
    expect(rotationPlan('electron.log', 3)).toEqual({
      drop: 'electron.log.3',
      moves: [
        { from: 'electron.log.2', to: 'electron.log.3' },
        { from: 'electron.log.1', to: 'electron.log.2' },
        { from: 'electron.log', to: 'electron.log.1' },
      ],
    });
  });

  it('with one kept file, replaces it with the live log', () => {
    expect(rotationPlan('electron.log', 1)).toEqual({
      drop: 'electron.log.1',
      moves: [{ from: 'electron.log', to: 'electron.log.1' }],
    });
  });
});

describe('firstLineStart', () => {
  const bytes = (s: string): Uint8Array => Buffer.from(s);

  it('keeps a tail that starts the file whole', () => {
    expect(firstLineStart(bytes('first\nsecond\n'), false)).toBe(0);
  });

  it('skips the partial line a mid-file tail opens on', () => {
    expect(firstLineStart(bytes('rst\nsecond\n'), true)).toBe(4);
  });

  it('keeps a mid-file tail with no newline rather than dropping it all', () => {
    expect(firstLineStart(bytes('one enormous line'), true)).toBe(0);
  });
});

describe('RotatingLog', () => {
  let dir: string;
  let file: string;

  beforeEach(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dotarec-log-'));
    file = path.join(dir, 'electron.log');
  });

  afterEach(() => {
    fs.rmSync(dir, { recursive: true, force: true });
  });

  const read = (p: string): string => fs.readFileSync(p, 'utf8');
  const exists = (p: string): boolean => fs.existsSync(p);

  it('appends below the cap without rolling', () => {
    const log = new RotatingLog(file, 100, 3);
    log.append('one\n');
    log.append('two\n');
    expect(read(file)).toBe('one\ntwo\n');
    expect(exists(`${file}.1`)).toBe(false);
  });

  it('rolls into .1 when the next line would cross the cap', () => {
    const log = new RotatingLog(file, 10, 3);
    log.append('aaaa\n'); // 5 bytes
    log.append('bbbb\n'); // 10: exactly at the cap, no roll yet
    log.append('cccc\n'); // would be 15: roll first
    expect(read(`${file}.1`)).toBe('aaaa\nbbbb\n');
    expect(read(file)).toBe('cccc\n');
  });

  it('keeps at most `keep` older files, newest in .1', () => {
    const log = new RotatingLog(file, 5, 2);
    for (const line of ['gen1\n', 'gen2\n', 'gen3\n', 'gen4\n']) log.append(line); // each fills the cap
    expect(read(file)).toBe('gen4\n');
    expect(read(`${file}.1`)).toBe('gen3\n');
    expect(read(`${file}.2`)).toBe('gen2\n');
    expect(exists(`${file}.3`)).toBe(false); // gen1 was dropped
  });

  it('counts a file left by an earlier run instead of starting from zero', () => {
    fs.writeFileSync(file, 'earlier\n'); // 8 bytes
    const log = new RotatingLog(file, 12, 3);
    log.append('ok\n'); // 11: still under, appended to the existing file
    expect(read(file)).toBe('earlier\nok\n');
    log.append('next\n'); // 16: rolls the earlier run's lines too
    expect(read(`${file}.1`)).toBe('earlier\nok\n');
    expect(read(file)).toBe('next\n');
  });

  it('rotates a legacy uncapped log away on the first write, keeping only its newest whole lines', () => {
    // 100 lines of 9 bytes ("line-000\n") = 900 bytes against a 64-byte cap.
    const lines = Array.from({ length: 100 }, (_, i) => `line-${String(i).padStart(3, '0')}\n`);
    fs.writeFileSync(file, lines.join(''));
    fs.writeFileSync(`${file}.1`, 'older generation\n');

    new RotatingLog(file, 64, 3).append('fresh\n');

    // The newest 64 bytes, minus the partial line they open on: lines 93..99 (63 bytes).
    expect(read(`${file}.1`)).toBe(lines.slice(93).join(''));
    expect(read(`${file}.2`)).toBe('older generation\n');
    expect(read(file)).toBe('fresh\n');
  });

  it('empties the live file in place when it cannot be moved aside', () => {
    // A non-empty directory squatting on .1 makes both the drop and the rename fail.
    fs.mkdirSync(`${file}.1`);
    fs.writeFileSync(path.join(`${file}.1`, 'blocker'), 'x');
    const log = new RotatingLog(file, 10, 1);
    log.append('aaaaaaaa\n');

    expect(() => log.append('bbbbbbbb\n')).not.toThrow();
    expect(read(file)).toBe('bbbbbbbb\n');
  });
});
