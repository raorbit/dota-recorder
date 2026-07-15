import { describe, expect, it } from 'vitest';
import { scrubChunk, scrubSecrets } from './scrub';

describe('scrubSecrets', () => {
  it('replaces every occurrence of a secret with ***', () => {
    expect(scrubSecrets('token=abc123 again abc123', ['abc123'])).toBe('token=*** again ***');
  });

  it('scrubs multiple secrets in one line', () => {
    expect(scrubSecrets('pw=secret tok=token', ['secret', 'token'])).toBe('pw=*** tok=***');
  });

  it('leaves a line without any secret untouched', () => {
    expect(scrubSecrets('nothing to hide', ['abc123'])).toBe('nothing to hide');
  });

  it('skips an empty-string secret instead of splitting into per-character garbage', () => {
    // Regression guard: split('') would explode the line character-by-character.
    expect(scrubSecrets('safe line', [''])).toBe('safe line');
  });

  it('skips an undefined secret', () => {
    expect(scrubSecrets('safe line', [undefined])).toBe('safe line');
  });

  it('scrubs the truthy secrets and skips the falsy ones in a mixed list', () => {
    expect(scrubSecrets('a=real b=none', ['real', '', undefined])).toBe('a=*** b=none');
  });
});

describe('scrubChunk', () => {
  it('emits only complete lines and buffers the trailing partial line', () => {
    const r = scrubChunk('', 'line one\nline two\npart', []);
    expect(r.lines).toEqual(['line one', 'line two']);
    expect(r.leftover).toBe('part'); // no trailing newline -> held back for the next chunk
  });

  it('redacts a secret split across two chunk boundaries', () => {
    // The core defect: a secret straddling two 'data' events lands in neither chunk's line, so a
    // per-chunk scrub would leak it. Buffering the partial line rejoins the halves before scrubbing.
    const first = scrubChunk('', 'token=abc', ['abc123']);
    expect(first.lines).toEqual([]); // no newline yet -> nothing emitted, the secret's first half held
    expect(first.leftover).toBe('token=abc');
    const second = scrubChunk(first.leftover, '123 tail\n', ['abc123']);
    expect(second.lines).toEqual(['token=*** tail']); // rejoined 'abc' + '123' -> matched + redacted
    expect(second.leftover).toBe('');
  });

  it('splits on both LF and CRLF and drops blank lines', () => {
    const r = scrubChunk('', 'a\r\nb\n\nc\n', []);
    expect(r.lines).toEqual(['a', 'b', 'c']);
    expect(r.leftover).toBe('');
  });

  it('carries a CRLF straddling two chunks (trailing CR buffered)', () => {
    const first = scrubChunk('', 'a\r', []);
    expect(first.lines).toEqual([]);
    expect(first.leftover).toBe('a\r');
    const second = scrubChunk(first.leftover, '\nb\n', []);
    expect(second.lines).toEqual(['a', 'b']); // the \r\n is not double-counted into a blank line
  });
});
