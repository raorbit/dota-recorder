package dev.dotarec.fsm;

/**
 * States of the recording lifecycle FSM.
 *
 * <p>Plan (Match lifecycle): GSI {@code map.game_state} drives transitions
 * HERO_SELECTION (arm) -&gt; PRE_GAME (roll/start) -&gt; GAME_IN_PROGRESS (tag) -&gt; POST_GAME
 * (cut/stop). These coarse states collapse that flow: the FSM arms and StartRecords EARLY (at
 * hero-select / pre-game), so {@code RECORDING} covers everything from the moment OBS is rolling
 * through GAME_IN_PROGRESS, and {@code STOPPING} is the cut-and-finalize phase. {@code IDLE} is the
 * resting state between matches.
 *
 * <p>Recording deliberately starts before the horn rather than at clock 0: OBS's StartRecord ->
 * OUTPUT_STARTED has latency, so arming early guarantees the first kill is on tape (see
 * {@code VideoOffsetCalculator}, which anchors offsets on the confirmed start, never game_clock).
 */
public enum MatchState {
    IDLE,
    ARMED,
    RECORDING,
    STOPPING
}
