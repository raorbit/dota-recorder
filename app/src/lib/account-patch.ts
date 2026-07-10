// Which account-id fields a settings PUT should carry. Pure (React-free) so the
// rule — never resend the account id unless the user actually edited it — is pinned
// by unit tests (see account-patch.test.ts). The core auto-captures the account id
// from GSI *after* the settings form mounts, so the form's loaded value goes stale
// the moment a match starts. Blindly PUTting the whole form state on any unrelated
// save let an untouched, still-empty field send clearAccountId and wipe the id the
// core had captured in the meantime — and SettingsController latches
// accountCaptureDone, so tagging then stayed broken until a core restart.

import type { SettingsPatch } from '../api/client';

export interface AccountFieldEdit {
  // Whether the user edited the Account ID field this session. False on a fresh load
  // and reset after each save; an untouched field is left out of the patch entirely.
  readonly touched: boolean;
  // Current field text. The input strips non-digits, but it may still be empty.
  readonly value: string;
  // The account id the form last loaded/saved from the core, or null if none stored.
  // Can be stale (the core may have captured an id since), so it only ever gates the
  // clear path — never the "leave it alone" decision, which rides on `touched`.
  readonly baseline: number | null;
}

/**
 * Decide which account-id fields (if any) belong in a settings PUT:
 *   - untouched           → {} (omit; the core keeps whatever it has)
 *   - edited to a value   → { accountId }
 *   - cleared a stored id → { clearAccountId: true } (only when there was one to clear)
 */
export function buildAccountPatch(
  edit: AccountFieldEdit,
): Pick<SettingsPatch, 'accountId' | 'clearAccountId'> {
  if (!edit.touched) return {};
  const trimmed = edit.value.trim();
  if (trimmed !== '') return { accountId: Number(trimmed) };
  // Field cleared. Only ask the core to clear when a stored id actually existed: a
  // blank baseline means nothing to clear, and possibly an id captured after load
  // that we must not wipe, so omit rather than send clearAccountId.
  if (edit.baseline !== null) return { clearAccountId: true };
  return {};
}
