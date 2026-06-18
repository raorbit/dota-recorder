package dev.dotarec.tagger;

import dev.dotarec.gsi.GsiFrame;
import org.springframework.stereotype.Service;

/**
 * Diffs consecutive GSI frames into timeline markers.
 *
 * <p>Plan (Event detection): emit a kill/assist marker on each {@code player.kills}/{@code assists}
 * increment, and a death marker on the {@code hero.alive} true->false falling edge; team-fight
 * markers are a heuristic over a rolling K/D/A window. Each marker stores a
 * {@code video_offset_s} from {@link VideoOffsetCalculator} (source = 'gsi'); Roshan/ward and
 * exact timings are corrected later by the replay pass (source = 'replay').
 *
 * <p>TODO(plan): hold the previous frame, compute per-counter deltas + falling-edge death,
 * and persist markers. No-op for v0.1 foundation.
 */
@Service
public class EventTagger {

    /** TODO(plan: Event detection): diff prev vs frame -> markers. */
    public void onFrame(GsiFrame frame) {
        // No-op for v0.1 foundation.
    }
}
