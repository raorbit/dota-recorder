package dev.dotarec.fsm;

/**
 * States of the recording lifecycle FSM.
 *
 * <p>Plan (Match lifecycle): GSI {@code map.game_state} drives transitions
 * HERO_SELECTION (arm) -> PRE_GAME (roll/start) -> GAME_IN_PROGRESS (tag) -> POST_GAME (cut/stop).
 * These coarse states collapse that flow: ARMED captures the match, RECORDING is rolling,
 * STOPPING is the cut-and-finalize phase.
 */
public enum MatchState {
    IDLE,
    ARMED,
    RECORDING,
    STOPPING
}
