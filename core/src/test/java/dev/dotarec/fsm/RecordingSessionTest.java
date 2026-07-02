package dev.dotarec.fsm;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.fsm.RecordingSession.TaggerState;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests the small per-session {@link TaggerState} the {@code EventTagger} threads across ticks:
 * the deaths high-water mark (Findings B + C) and the per-dead-episode dedupe latch (Finding B). The
 * end-to-end death-detection behavior these back is covered in {@code EventTaggerTest}; this pins the
 * state primitives directly.
 */
class RecordingSessionTest {

    @Test
    void taggerState_startsUnseeded() {
        TaggerState state = new TaggerState();
        assertThat(state.deaths().seeded())
                .as("no player-present frame seen yet -> deaths baseline not seeded")
                .isFalse();
        assertThat(state.deathEmittedThisEpisode()).isFalse();
    }

    @Test
    void seedIfUnseeded_marksSeeded_andHoldsTheBaseline() {
        TaggerState state = new TaggerState();
        state.deaths().seedIfUnseeded(5);

        assertThat(state.deaths().seeded()).isTrue();
        assertThat(state.deaths().value()).isEqualTo(5);
    }

    @Test
    void seedIfUnseeded_neverReseedsAnAlreadySeededMark() {
        TaggerState state = new TaggerState();
        state.deaths().seedIfUnseeded(1);
        state.deaths().seedIfUnseeded(7);

        assertThat(state.deaths().value())
                .as("only the FIRST player-present frame seeds the baseline")
                .isEqualTo(1);
    }

    @Test
    void set_advancesTheHighWaterMark() {
        TaggerState state = new TaggerState();
        state.deaths().seedIfUnseeded(1);
        state.deaths().set(3);

        assertThat(state.deaths().value()).isEqualTo(3);
    }

    @Test
    void deathEpisodeLatch_setsAndResets() {
        TaggerState state = new TaggerState();
        state.markDeathEmittedThisEpisode();
        assertThat(state.deathEmittedThisEpisode())
                .as("latch suppresses a second edge marker for the same dead episode").isTrue();

        state.resetDeathEpisode();
        assertThat(state.deathEmittedThisEpisode())
                .as("respawn clears the latch so the next death can emit").isFalse();
    }

    @Test
    void eachSessionGetsItsOwnTaggerState() {
        RecordingSession a = new RecordingSession();
        RecordingSession b = new RecordingSession();

        a.getTaggerState().markDeathEmittedThisEpisode();
        a.getTaggerState().deaths().seedIfUnseeded(1);

        assertThat(b.getTaggerState().deathEmittedThisEpisode())
                .as("state is per-session, not a shared static").isFalse();
        assertThat(b.getTaggerState().deaths().seeded()).isFalse();
    }

    @Test
    void getTaggerState_returnsTheSameStableInstance() {
        RecordingSession s = new RecordingSession();
        assertThat(s.getTaggerState()).isSameAs(s.getTaggerState());
    }
}
