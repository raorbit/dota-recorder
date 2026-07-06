// The label pair for the right-click "Delete recording" item. Pure (React-free) so the wording — the
// thing that once let a "delete this clip" intent silently take a whole match and its clips with it —
// is pinned by unit tests (see delete-labels.test.ts). There is exactly ONE delete per object kind:
// a recording delete never touches clips (they have their own right-click delete), and when clips
// exist the label says so, since the row will visibly survive as a videoless stub.

export interface DeleteMenuLabel {
  readonly label: string;
  // The label once armed (two-step confirm: first click arms, second executes).
  readonly armedLabel: string;
}

/**
 * The delete item for a right-click on `matchCount` selected recordings carrying `clipCount`
 * clips between them.
 */
export function deleteMenuLabel(matchCount: number, clipCount: number): DeleteMenuLabel {
  const what = matchCount === 1 ? 'recording' : `${matchCount} recordings`;
  const suffix = matchCount === 1 ? '' : ` (${matchCount})`;
  const hint =
    clipCount === 0 ? '' : ` (keeps ${clipCount} clip${clipCount === 1 ? '' : 's'})`;
  return {
    label: `Delete ${what}${hint}`,
    armedLabel: `Click to confirm delete${suffix}`,
  };
}

/**
 * The label shown INSTEAD of the delete item when every targeted recording is already videoless AND
 * still has clips — a delete would change nothing server-side (the row only remains because its
 * clips need it), so an armed confirm there would be a silent, feedback-free no-op. Point at the
 * action that actually frees the entry instead.
 */
export function deleteBlockedLabel(matchCount: number, clipCount: number): string {
  const whose = matchCount === 1 ? 'its' : 'their';
  return `Delete ${whose} ${clipCount} clip${clipCount === 1 ? '' : 's'} first`;
}
