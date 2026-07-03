import { describe, expect, it } from 'vitest';
import { scrubSecrets } from './scrub';

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
