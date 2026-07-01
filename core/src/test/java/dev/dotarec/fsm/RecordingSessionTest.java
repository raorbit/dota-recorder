package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.fsm.RecordingSession.TaggerState;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the small per-session {@link TaggerState} the {@code EventTagger} threads across ticks:
 * the last-good present counters (Finding C) and the per-dead-episode dedupe latch (Finding B). The
 * end-to-end death-detection behavior these back is covered in {@code EventTaggerTest}; this pins the
 * state primitives directly.
 */
class RecordingSessionTest {

    @Test
    void taggerState_startsWithNoGoodCounters() {
        TaggerState state = new TaggerState();
        assertThat(state.hasNoGoodCounters())
                .as("no player-present frame seen yet -> the first one must not diff against 0/0/0")
                .isTrue();
        assertThat(state.deathEmittedThisEpisode()).isFalse();
    }

    @Test
    void updateLastGoodCounters_marksCountersSeen_andHoldsTheValues() {
        TaggerState state = new TaggerState();
        state.updateLastGoodCounters(8, 5, 3);

        assertThat(state.hasNoGoodCounters()).isFalse();
        assertThat(state.lastGoodKills()).isEqualTo(8);
        assertThat(state.lastGoodDeaths()).isEqualTo(5);
        assertThat(state.lastGoodAssists()).isEqualTo(3);
    }

    @Test
    void deathEpisodeLatch_setsAndResets() {
        TaggerState state = new TaggerState();
        state.markDeathEmittedThisEpisode();
        assertThat(state.deathEmittedThisEpisode())
                .as("latch suppresses a second marker for the same dead episode").isTrue();

        state.resetDeathEpisode();
        assertThat(state.deathEmittedThisEpisode())
                .as("respawn clears the latch so the next death can emit").isFalse();
    }

    @Test
    void eachSessionGetsItsOwnTaggerState() {
        RecordingSession a = new RecordingSession();
        RecordingSession b = new RecordingSession();

        a.getTaggerState().markDeathEmittedThisEpisode();
        a.getTaggerState().updateLastGoodCounters(1, 1, 1);

        assertThat(b.getTaggerState().deathEmittedThisEpisode())
                .as("state is per-session, not a shared static").isFalse();
        assertThat(b.getTaggerState().hasNoGoodCounters()).isTrue();
    }

    @Test
    void getTaggerState_returnsTheSameStableInstance() {
        RecordingSession s = new RecordingSession();
        assertThat(s.getTaggerState()).isSameAs(s.getTaggerState());
    }
}
