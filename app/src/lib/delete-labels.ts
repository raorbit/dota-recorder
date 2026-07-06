// Labels for the right-click delete options on recordings. Pure (React-free) so the wording — the
// thing that once let a "delete this clip" intent silently take a whole match and its clips with it —
// is pinned by unit tests (see delete-labels.test.ts). A recording with clips ALWAYS gets two explicit
// options (keep the clips / take them too); only a clipless one gets a bare "Delete recording".

export interface DeleteMenuOption {
  // 'keep' = delete the recording's video but keep the row + every clip (DELETE ?keepClips=true);
  // 'full' = delete the row, the video, and all its clips.
  readonly kind: 'keep' | 'full';
  readonly label: string;
  // The label once the option is armed (two-step confirm: first click arms, second executes).
  readonly armedLabel: string;
}

function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

/**
 * The delete options for a right-click on `matchCount` selected recordings carrying `clipCount`
 * clips between them. Order is safest-first: the keep-clips option (when there are clips) precedes
 * the full delete.
 */
export function deleteMenuOptions(matchCount: number, clipCount: number): DeleteMenuOption[] {
  const what = matchCount === 1 ? 'recording' : `${matchCount} recordings`;
  const suffix = matchCount === 1 ? '' : ` (${matchCount})`;
  if (clipCount === 0) {
    return [
      { kind: 'full', label: `Delete ${what}`, armedLabel: `Click to confirm delete${suffix}` },
    ];
  }
  return [
    {
      kind: 'keep',
      label: `Delete ${what}, keep ${plural(clipCount, 'clip')}`,
      armedLabel: `Click to confirm delete${suffix}`,
    },
    {
      kind: 'full',
      label: `Delete ${what} + ${plural(clipCount, 'clip')}`,
      armedLabel: `Click to confirm delete all${suffix}`,
    },
  ];
}
