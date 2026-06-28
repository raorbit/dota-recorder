package dev.dotarec.tagger;

import static dev.dotarec.gsi.GsiFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;

import dev.dotarec.gsi.GsiFrame;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the tagger's diff rules on synthetic frame pairs derived from the real GSI shape:
 * per-counter deltas are independent (a tick can be kill + assist + death at once), a falling-edge
 * death is gated on hero presence on BOTH frames (an absent hero never manufactures a phantom
 * death), and each marker's video_offset_s is the wall-clock delta from the record-confirmed anchor.
 */
class EventTaggerTest {

    private final EventTagger tagger = new EventTagger();

    /** Anchor = OBS confirmed start; offsets are measured from here. */
    private static final long ANCHOR = 1_000_000L;
    private static final double DURATION = 600.0;

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
    void videoOffsetIsWallDeltaFromAnchor_notGameClock() {
        // Event lands 42.5s of wall time after the confirmed start. game_clock is deliberately a
        // wildly different value to prove it is NOT the offset source.
        GsiFrame prev = frame().wall(ANCHOR).kills(0).clock(0).build();
        GsiFrame curr = frame().wall(ANCHOR + 42_500L).kills(1).clock(999).build();

        List<PendingMarker> markers = tagger.diff(prev, curr, ANCHOR, DURATION);
        assertThat(markers).hasSize(1);
        PendingMarker kill = markers.get(0);
        assertThat(kill.videoOffsetS()).isEqualTo(42.5);
        // game_clock is stored as a display label only.
        assertThat(kill.gameClock()).isEqualTo(999);
        assertThat(kill.source()).isEqualTo("gsi");
    }

    @Test
    void offsetClampsToDuration_andFloorsAtZero() {
        // An event whose wall delta exceeds the (now-known) duration clamps to duration.
        GsiFrame prev = frame().wall(ANCHOR).kills(0).build();
        GsiFrame over = frame().wall(ANCHOR + 10_000_000L).kills(1).build();
        assertThat(tagger.diff(prev, over, ANCHOR, DURATION).get(0).videoOffsetS())
                .isEqualTo(DURATION);

        // A frame stamped before the anchor (clock skew) floors at 0 rather than going negative.
        GsiFrame before = frame().wall(ANCHOR - 5_000L).kills(1).build();
        assertThat(tagger.diff(prev, before, ANCHOR, DURATION).get(0).videoOffsetS())
                .isZero();
    }
}
