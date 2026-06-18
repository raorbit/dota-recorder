package dev.dotarec.fsm;

import dev.dotarec.gsi.GsiFrame;
import org.springframework.stereotype.Service;

/**
 * Match state machine: interprets GSI frames and decides when to start/stop recording.
 *
 * <p>Plan (Match lifecycle): draft -> arm + pre-roll buffer; PRE_GAME -> START recording at
 * clock 0; GAME_IN_PROGRESS -> tag; POST_GAME -> STOP + finalize; then async enrich.
 *
 * <p>Robustness notes for the real implementation:
 * <ul>
 *   <li>{@code GAME_IN_PROGRESS} (DOTA_GAMERULES_STATE_GAME_IN_PROGRESS) is a valid ENTRY state
 *       too: if the app starts mid-match the FSM must arm/roll from it, not only from PRE_GAME.</li>
 *   <li>Unknown / unrecognized game_state values are no-ops (hold current state) rather than
 *       errors, to survive Dota client version churn.</li>
 * </ul>
 *
 * <p>TODO(plan): implement transitions and side effects (OBS start/stop, tagging, finalize).
 */
@Service
public class MatchFsm {

    private volatile MatchState state = MatchState.IDLE;

    public MatchState getState() {
        return state;
    }

    /** TODO(plan: Match lifecycle): map frame.gameState -> transitions + side effects. */
    public void onFrame(GsiFrame frame) {
        // No-op for v0.1 foundation.
    }
}
