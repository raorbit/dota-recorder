import { describe, expect, it } from 'vitest';
import { buildAccountPatch } from './account-patch';

describe('buildAccountPatch', () => {
  it('omits the account fields entirely when the field is untouched', () => {
    expect(buildAccountPatch({ touched: false, value: '', baseline: null })).toEqual({});
    // Untouched wins even when the field text lags the baseline (the core may have
    // auto-captured an id since load) — this is the case that used to wipe it.
    expect(buildAccountPatch({ touched: false, value: '', baseline: 96828122 })).toEqual({});
    expect(buildAccountPatch({ touched: false, value: '96828122', baseline: null })).toEqual({});
  });

  it('sends accountId when the user entered a value', () => {
    expect(buildAccountPatch({ touched: true, value: '96828122', baseline: null })).toEqual({
      accountId: 96828122,
    });
    // Trims surrounding whitespace before coercing to a number.
    expect(buildAccountPatch({ touched: true, value: ' 42 ', baseline: 7 })).toEqual({
      accountId: 42,
    });
  });

  it('clears only when the user emptied a previously stored id', () => {
    expect(buildAccountPatch({ touched: true, value: '', baseline: 96828122 })).toEqual({
      clearAccountId: true,
    });
    expect(buildAccountPatch({ touched: true, value: '   ', baseline: 7 })).toEqual({
      clearAccountId: true,
    });
  });

  it('does not clear an emptied field that never had a stored id', () => {
    // Guards against wiping an id the core auto-captured after load, when the user
    // typed-then-cleared over a baseline that was empty at load time.
    expect(buildAccountPatch({ touched: true, value: '', baseline: null })).toEqual({});
    expect(buildAccountPatch({ touched: true, value: '   ', baseline: null })).toEqual({});
  });
});
