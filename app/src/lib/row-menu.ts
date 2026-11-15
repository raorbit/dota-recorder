// Which rows a table's right-click menu acts on. Pure (React-free) so it unit-tests in plain Node (see
// row-menu.test.ts); MatchTable's recording and clip tables both use it.

/**
 * The rows the menu opened on `clickedId` acts on: the whole multi-selection when the clicked row is
 * part of it, otherwise just that row. Only rows still listed count: the selection can still hold the
 * id of a row that has left the list since it was picked (deleted on its own from its menu, or from
 * the player's strip), and counting it would show "3 SELECTED" for two rows and send a delete for a
 * row that is gone. The clicked row always counts, since the menu was opened on it.
 */
export function menuTargetIds(
  selection: ReadonlySet<number>,
  clickedId: number,
  isListed: (id: number) => boolean,
): number[] {
  const ids = selection.has(clickedId) ? [...selection] : [clickedId];
  return ids.filter((id) => id === clickedId || isListed(id));
}
