// Keeps a table's multi-selection to rows that are still there. Pure (React-free) so it unit-tests in
// plain Node (see row-selection.test.ts); MatchTable's recording and clip tables both use it.
//
// Recording and clip ids are SQLite rowids with no AUTOINCREMENT, so once the newest row is deleted the
// next row inserted gets its id. A selection still holding the deleted id would then hold that new row
// (a clip just cut, a match just recorded), and the next bulk delete would take it too. So a row leaves
// the selection when its own delete is asked for, and whenever it leaves the list; menuTargetIds
// (row-menu.ts) still checks the ids it acts on are listed.

/**
 * `selection` without the ids that are no longer listed. The very same set when every id still is, so
 * a table that keeps it in state doesn't re-render on every list change.
 */
export function keepListed(
  selection: ReadonlySet<number>,
  isListed: (id: number) => boolean,
): ReadonlySet<number> {
  for (const id of selection) {
    if (!isListed(id)) return new Set([...selection].filter(isListed));
  }
  return selection;
}

/** `selection` without `id`, whose delete was just asked for. The very same set when `id` isn't in it. */
export function withoutSelected(selection: ReadonlySet<number>, id: number): ReadonlySet<number> {
  if (!selection.has(id)) return selection;
  const next = new Set(selection);
  next.delete(id);
  return next;
}
