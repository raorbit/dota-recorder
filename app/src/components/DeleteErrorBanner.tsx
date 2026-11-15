import { useLibraryStore } from '../store/library';
import './disk-warning-banner.css';

// A dismissible bar across the top of the main column saying a recording or clip delete failed, and
// why. The store sets `deleteError` when a delete is refused or the core can't be reached, and reloads
// the list at the same time, so the rows on screen are what the core actually holds. A delete that
// only timed out isn't reported here: the core still finishes it once its maintenance lock frees. The
// bar is the low-disk banner's (disk-warning-banner.css) in the error tone, since the user's own
// action did not happen. Dismissing it clears the message, and so does starting another delete.
export function DeleteErrorBanner(): React.JSX.Element | null {
  const message = useLibraryStore((s) => s.deleteError);
  const setDeleteError = useLibraryStore((s) => s.setDeleteError);

  if (message === null) return null;

  return (
    <div className="dwb-banner dwb-banner-error" role="alert">
      <span className="dwb-icon" aria-hidden="true">
        !
      </span>
      <p className="dwb-text">{message}</p>
      <button
        type="button"
        className="dwb-dismiss"
        aria-label="Dismiss delete failure"
        onClick={() => setDeleteError(null)}
      >
        &#x2715;
      </button>
    </div>
  );
}
