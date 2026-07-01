package dev.dotarec.tagger;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.fsm.RecordingSession.TaggerState;
import dev.dotarec.gsi.GsiFrame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the tagger's diff rules on synthetic frame pairs derived from the real GSI shape:
 * per-counter deltas are independent (a tick can be kill + assist + death at once), a falling-edge
 * death is gated on hero presence on BOTH frames (an absent hero never manufactures a phantom
 * death), and each marker's video_offset_s is the MONOTONIC delta from the record-confirmed anchor.
 */
class EventTaggerTest {

    private final EventTagger tagger = new EventTagger();

    /** Anchor = OBS confirmed start (a System.nanoTime() stamp); offsets are measured from here. */
    private static final long ANCHOR = 1_000_000_000_000L;
    private static final double DURATION = 600.0;

    /** Milliseconds expressed as nanos, since offsets are a monotonic-nanos delta. */
    private static long nanos(long millis) {
        return millis * 1_000_000L;
    }

    @Test
    void firstFrameOfRecording_noPrev_emitsNothing() {
        GsiFrame curr = frame().wall(ANCHOR + 5_000L).kills(3).build();
        assertThat(tagger.diff(null, curr, ANCHOR, DURATION)).isEmpty();
    }

    @Test
    void noChange_emitsNothing() {
        GsiFrame prev = frame().wall(ANCHOR + 1_000L).kills(2).deaths(1).assists(4).build();
        GsiFrame curr = frame().wall(ANCHOR + 1_100L).kills(2).deaths(1).assists(4).build();
        assertThat(tagger.diff(prev, curr, ANCHOR, DURATION)).isEmpty();
    }

    @Test
    void independentCounters_killAssistAndDeathInOneTick() {
        // One ~10Hz tick trades a kill, an assist AND the player's own death (counter increments)
        // plus the alive falling edge. Each counter is diffed independently.
        GsiFrame prev =
                frame().wall(ANCHOR + 10_000L).kills(2).assists(3).deaths(1).alive(true).build();
        GsiFrame curr =
                frame().wall(ANCHOR + 10_100L).kills(3).assists(4).deaths(2).alive(false).build();

        List<PendingMarker> markers = tagger.diff(prev, curr, ANCHOR, DURATION);

        // The deaths counter (+1) and the alive falling edge describe the SAME death, so it must
        // emit exactly ONE death marker, not two -- the edge is suppressed when the counter moved.
        assertThat(markers).extracting(PendingMarker::type)
                .containsExactlyInAnyOrder("kill", "assist", "death");
        assertThat(markers).filteredOn(m -> m.type().equals("kill")).hasSize(1);
        assertThat(markers).filteredOn(m -> m.type().equals("assist")).hasSize(1);
        assertThat(markers).filteredOn(m -> m.type().equals("death")).hasSize(1);
    }

    @Test
    void multiKillDelta_emitsOneMarkerPerIncrement() {
        // A dropped frame can batch two kills into one tick (delta of 2 -> two markers).
        GsiFrame prev = frame().wall(ANCHOR + 5_000L).kills(4).build();
        GsiFrame curr = frame().wall(ANCHOR + 5_100L).kills(6).build();

        List<PendingMarker> markers = tagger.diff(prev, curr, ANCHOR, DURATION);
        assertThat(markers).extracting(PendingMarker::type).containsExactly("kill", "kill");
    }

    @Test
    void fallingEdgeDeath_requiresHeroPresentOnBothFrames() {
        // Hero present + alive -> dead: a real death edge.
        GsiFrame alivePrev = frame().wall(ANCHOR + 20_000L).alive(true).heroPresent(true).build();
        GsiFrame deadCurr = frame().wall(ANCHOR + 20_100L).alive(false).heroPresent(true).build();

        assertThat(tagger.diff(alivePrev, deadCurr, ANCHOR, DURATION))
                .extracting(PendingMarker::type)
                .containsExactly("death");
    }

    @Test
    void heroBlockVanishes_isNotAPhantomDeath() {
        // Alive -> hero block absent (load screen / reconnect). alive flips to false ONLY because
        // the block is gone, not because the player died. Must NOT emit a death.
        GsiFrame alivePrev = frame().wall(ANCHOR + 30_000L).alive(true).heroPresent(true).build();
        GsiFrame goneCurr = frame().wall(ANCHOR + 30_100L).noHero().build();

        assertThat(tagger.diff(alivePrev, goneCurr, ANCHOR, DURATION)).isEmpty();
    }

    @Test
    void heroBlockAppears_fromAbsent_isNotADeathNorRevive() {
        // Hero-select (no block) -> hero loads alive. No falling edge, no counters move.
        GsiFrame gonePrev = frame().wall(ANCHOR + 1_000L).noHero().build();
        GsiFrame alivePrev = frame().wall(ANCHOR + 1_100L).alive(true).heroPresent(true).build();

        assertThat(tagger.diff(gonePrev, alivePrev, ANCHOR, DURATION)).isEmpty();
    }

    @Test
    void playerBlockVanishesThenReturnsNonZero_isNotPhantomMarkers() {
        // The player block drops on a heartbeat / reconnect (counters default to 0), then returns
        // with the real running totals. Without the player-presence gate this pair would emit a
        // burst of phantom markers (8 kills + 5 deaths); the gate must suppress them entirely.
        GsiFrame absent = frame().wall(ANCHOR + 40_000L).noPlayer().build();
        GsiFrame back =
                frame().wall(ANCHOR + 40_100L).playerPresent(true).kills(8).deaths(5).assists(3).build();

        assertThat(tagger.diff(absent, back, ANCHOR, DURATION)).isEmpty();
    }

    @Test
    void videoOffsetIsMonotonicDeltaFromAnchor_notGameClock() {
        // Event lands 42.5s of elapsed (monotonic) time after the confirmed start. game_clock is
        // deliberately a wildly different value to prove it is NOT the offset source.
        GsiFrame prev = frame().mono(ANCHOR).kills(0).clock(0).build();
        GsiFrame curr = frame().mono(ANCHOR + nanos(42_500L)).kills(1).clock(999).build();

        List<PendingMarker> markers = tagger.diff(prev, curr, ANCHOR, DURATION);
        assertThat(markers).hasSize(1);
        PendingMarker kill = markers.get(0);
        assertThat(kill.videoOffsetS()).isEqualTo(42.5);
        // game_clock is stored as a display label only.
        assertThat(kill.gameClock()).isEqualTo(999);
        assertThat(kill.source()).isEqualTo("gsi");
    }

    @Test
    void offsetIgnoresWallClock_soABackwardClockStepDoesNotShiftTheMarker() {
        // The frame's WALL stamp jumped backwards far before the anchor (an NTP step), but its
        // MONOTONIC stamp is a clean 12s after the anchor. The offset must follow the monotonic
        // delta (12s), proving it no longer reads wall-clock (Finding #4).
        GsiFrame prev = frame().wall(ANCHOR).mono(ANCHOR).kills(0).build();
        GsiFrame curr =
                frame().wall(ANCHOR - 9_000_000L).mono(ANCHOR + nanos(12_000L)).kills(1).build();

        assertThat(tagger.diff(prev, curr, ANCHOR, DURATION).get(0).videoOffsetS())
                .isEqualTo(12.0);
    }

    @Test
    void offsetClampsToDuration_andFloorsAtZero() {
        // An event whose elapsed delta exceeds the (now-known) duration clamps to duration.
        GsiFrame prev = frame().mono(ANCHOR).kills(0).build();
        GsiFrame over = frame().mono(ANCHOR + nanos(10_000_000L)).kills(1).build();
        assertThat(tagger.diff(prev, over, ANCHOR, DURATION).get(0).videoOffsetS())
                .isEqualTo(DURATION);

        // A frame stamped before the anchor floors at 0 rather than going negative.
        GsiFrame before = frame().mono(ANCHOR - nanos(5_000L)).kills(1).build();
        assertThat(tagger.diff(prev, before, ANCHOR, DURATION).get(0).videoOffsetS())
                .isZero();
    }

    // ---- Finding B: one death across a counter/alive straddle on ADJACENT ticks ----------------

    @Test
    void deathEdgeLeadsCounterOnAdjacentTick_emitsExactlyOneDeath() {
        // The alive true->false edge lands one tick BEFORE the deaths counter catches up. A per-tick
        // "same-tick only" suppression would let both paths fire and double-count; the episode dedupe
        // must collapse them to a single death marker.
        GsiFrame f0 = frame().wall(ANCHOR + 1_000L).deaths(1).alive(true).build();
        GsiFrame f1 = frame().wall(ANCHOR + 1_100L).deaths(1).alive(false).build(); // edge, counter lags
        GsiFrame f2 = frame().wall(ANCHOR + 1_200L).deaths(2).alive(false).build(); // counter catches up

        List<PendingMarker> all = replay(f0, f1, f2);

        assertThat(all).filteredOn(m -> m.type().equals("death"))
                .as("edge-leads-counter across adjacent ticks is ONE death").hasSize(1);
    }

    @Test
    void deathCounterLeadsEdgeOnAdjacentTick_emitsExactlyOneDeath() {
        // Symmetric: the deaths counter increments while alive is STILL true, and the alive->dead flip
        // arrives on the next tick. The counter emits the death; the later falling edge describes the
        // SAME death and must be suppressed (the episode is still open -- no respawn in between).
        GsiFrame f0 = frame().wall(ANCHOR + 2_000L).deaths(1).alive(true).build();
        GsiFrame f1 = frame().wall(ANCHOR + 2_100L).deaths(2).alive(true).build();  // counter, alive lags
        GsiFrame f2 = frame().wall(ANCHOR + 2_200L).deaths(2).alive(false).build(); // alive flips dead

        List<PendingMarker> all = replay(f0, f1, f2);

        assertThat(all).filteredOn(m -> m.type().equals("death"))
                .as("counter-leads-edge across adjacent ticks is ONE death").hasSize(1);
    }

    @Test
    void twoSeparateDeathsWithRespawnBetween_emitTwoDeaths() {
        // A respawn (dead->alive rising edge) closes the episode so the SECOND death emits again --
        // the dedupe latch must not permanently swallow later deaths.
        GsiFrame f0 = frame().wall(ANCHOR + 3_000L).deaths(1).alive(true).build();
        GsiFrame f1 = frame().wall(ANCHOR + 3_100L).deaths(2).alive(false).build(); // first death
        GsiFrame f2 = frame().wall(ANCHOR + 3_200L).deaths(2).alive(true).build();  // respawn
        GsiFrame f3 = frame().wall(ANCHOR + 3_300L).deaths(3).alive(false).build(); // second death

        List<PendingMarker> all = replay(f0, f1, f2, f3);

        assertThat(all).filteredOn(m -> m.type().equals("death")).hasSize(2);
    }

    @Test
    void twoDeathsWithTheRespawnWindowEntirelyUnobserved_emitTwoDeaths() {
        // Regression from the review of PR #47: the WHOLE alive window between two deaths is dropped by
        // GSI, so no respawn rising edge is ever seen. The deaths counter is still monotonic, so the
        // second death must still be tagged -- the episode latch must NOT swallow it. (A latch that gated
        // the counter path emitted only 1 here; the high-water-mark counter path emits both.)
        GsiFrame f0 = frame().wall(ANCHOR + 5_000L).deaths(1).alive(true).build();
        GsiFrame f1 = frame().wall(ANCHOR + 5_100L).deaths(2).alive(false).build(); // first death
        GsiFrame f2 = frame().wall(ANCHOR + 5_200L).deaths(3).alive(false).build(); // second death, respawn unseen

        assertThat(replay(f0, f1, f2)).filteredOn(m -> m.type().equals("death"))
                .as("a second death is still tagged when its respawn window was never observed")
                .hasSize(2);
    }

    @Test
    void deathCounterCatchesUpAfterRespawnResetsTheLatch_stillOneDeath() {
        // Regression from the review of PR #47: the falling edge tags a death while the counter lags; the
        // hero then respawns (clearing the latch) BEFORE the counter catches up. Without a high-water mark
        // the lagging counter increment would re-emit the same death (double count). The mark suppresses it.
        GsiFrame f0 = frame().wall(ANCHOR + 6_000L).deaths(0).alive(true).build();
        GsiFrame f1 = frame().wall(ANCHOR + 6_100L).deaths(0).alive(false).build(); // edge death, counter lags
        GsiFrame f2 = frame().wall(ANCHOR + 6_200L).deaths(0).alive(true).build();  // respawn, counter still lags
        GsiFrame f3 = frame().wall(ANCHOR + 6_300L).deaths(1).alive(true).build();  // counter catches up post-respawn

        assertThat(replay(f0, f1, f2, f3)).filteredOn(m -> m.type().equals("death"))
                .as("a counter increment landing after respawn must not re-emit the edge death")
                .hasSize(1);
    }

    @Test
    void fallingEdgeBeforeAnyPlayerPresentFrame_tagsNothing_andDoesNotCorruptTheBaseline() {
        // A hero alive->dead edge can land before ANY player-present frame has seeded the deaths baseline
        // (emittedDeaths still UNSEEN). The edge path's deathsSeeded() guard must skip it -- without the
        // guard the edge would emit a phantom death AND seed the high-water mark at 0, so the first real
        // player-present frame (deaths=3) would then burst three more phantom deaths via the counter path.
        // A player-block-less frame keeps the hero defaults (heroPresent=true, alive=true), so the pair
        // below forms a real-looking falling edge while the baseline is still unseeded.
        GsiFrame beforeSeed0 =
                frame().wall(ANCHOR + 7_000L).noPlayer().alive(true).heroPresent(true).build();
        GsiFrame beforeSeed1 =
                frame().wall(ANCHOR + 7_100L).alive(false).heroPresent(true).playerPresent(false).build();
        GsiFrame firstPresent =
                frame().wall(ANCHOR + 7_200L).deaths(3).alive(true).heroPresent(true).playerPresent(true).build();

        assertThat(replay(beforeSeed0, beforeSeed1, firstPresent))
                .filteredOn(m -> m.type().equals("death"))
                .as("an unseeded falling edge tags no death and never seeds the baseline at 0")
                .isEmpty();
    }

    // ---- Finding C: a death during a single-frame player-block dropout is not lost -------------

    @Test
    void deathDuringSingleFrameBlockDropout_isEmittedWhenBlockReturns() {
        // The player+hero blocks drop on the EXACT death tick (a heartbeat/reconnect zeroes the
        // counters). Diffing the returning frame against the raw absent prev would measure the death
        // delta against a zeroed baseline and drop it. Diffing against the last-good present counters
        // instead emits it when the block returns.
        GsiFrame present1 =
                frame().wall(ANCHOR + 4_000L).deaths(1).alive(true).heroPresent(true)
                        .playerPresent(true).build();
        GsiFrame heartbeat = frame().wall(ANCHOR + 4_100L).noHero().noPlayer().build();
        GsiFrame present2 =
                frame().wall(ANCHOR + 4_200L).deaths(2).alive(false).heroPresent(true)
                        .playerPresent(true).build();

        List<PendingMarker> all = replay(present1, heartbeat, present2);

        assertThat(all).filteredOn(m -> m.type().equals("death"))
                .as("a death on a block-dropout tick is still emitted when the block returns")
                .hasSize(1);
    }

    /** Replays frames through ONE shared TaggerState (as the FSM does) and collects every marker. */
    private List<PendingMarker> replay(GsiFrame... frames) {
        TaggerState state = new TaggerState();
        List<PendingMarker> all = new ArrayList<>();
        for (int i = 1; i < frames.length; i++) {
            all.addAll(tagger.diff(frames[i - 1], frames[i], state, ANCHOR, DURATION));
        }
        return all;
    }
}
