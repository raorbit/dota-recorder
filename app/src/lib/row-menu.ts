// Which rows a table's right-click menu acts on. Pure (React-free) so it unit-tests in plain Node (see
// row-menu.test.ts); MatchTable's recording and clip tables both use it.

/**
 * The rows the menu opened on `clickedId` acts on: the whole multi-selection when the clicked row is
 * part of it, otherwise just that row. Only rows still listed count. The tables already drop a row
 * from the selection once it leaves the list (see row-selection.ts), so this is a second guard: a
 * selected id that isn't listed would show "3 SELECTED" for two rows and send a delete for a row
 * that is gone. The clicked row counts only while it is listed too: a menu left open on a row that
 * has since gone (its queued delete finished, say) acts on nothing, as the next row inserted can
 * take its id and the menu's Delete would then take that row. The tables close such a menu.
 */
export function menuTargetIds(
  selection: ReadonlySet<number>,
  clickedId: number,
  isListed: (id: number) => boolean,
): number[] {
  if (!isListed(clickedId)) return [];
  const ids = selection.has(clickedId) ? [...selection] : [clickedId];
  return ids.filter(isListed);
}
