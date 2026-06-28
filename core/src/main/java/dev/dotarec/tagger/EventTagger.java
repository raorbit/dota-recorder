package dev.dotarec.tagger;

import dev.dotarec.gsi.GsiFrame;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Diffs consecutive GSI frames into timeline markers for the OWN player.
 *
 * <p>Detection rules (from the plan + the real GSI shape):
 * <ul>
 *   <li>{@code kills}/{@code deaths}/{@code assists} are running TOTALS. Each counter is diffed
 *       INDEPENDENTLY and a marker is emitted per increment, because a single ~10Hz tick can carry
 *       several at once (e.g. you trade a kill, an assist, and your own death in the same frame).
 *       A positive delta of N emits N markers of that type (rare, but a dropped frame can batch
 *       two kills into one tick).</li>
 *   <li>A death is detected from the {@code deaths} counter delta (primary) OR the {@code hero.alive}
 *       true-&gt;false FALLING EDGE (fallback when the counter lagged), but never BOTH for the same
 *       tick -- a single death emits a single marker. The falling edge is gated on the hero block
 *       being present on BOTH frames: on load / hero-select the block is absent
 *       ({@code heroPresent=false} -&gt; {@code alive=false}), and without that guard the absence would
 *       read as a phantom death.</li>
 * </ul>
 *
 * <p>Each marker's {@code video_offset_s} comes from {@link VideoOffsetCalculator} anchored on the
 * OBS record-confirmed wall clock; {@code game_clock} is stored as a display label only. The FSM
 * buffers the returned {@link PendingMarker}s on the {@code RecordingSession} and persists them at
 * finalize. This class is stateless and pure so it is trivial to unit-test on synthetic frame pairs.
 */
@Service
public class EventTagger {

    /**
     * Diffs {@code prev} -&gt; {@code curr} for the own player and returns the markers detected on
     * this tick (possibly empty, never null).
     *
     * @param prev                  the previous frame, or null for the first frame of a recording
     *                              (no diff is possible, so no markers)
     * @param curr                  the current frame
     * @param recordConfirmedWallMs wall-clock millis OBS confirmed OUTPUT_STARTED (the offset anchor)
     * @param durationS             upper clamp bound passed to {@link VideoOffsetCalculator}; live
     *                              callers pass a generous bound, finalize re-clamps to real duration
     */
    public List<PendingMarker> diff(
            GsiFrame prev, GsiFrame curr, long recordConfirmedWallMs, double durationS) {
        List<PendingMarker> markers = new ArrayList<>();
        if (prev == null || curr == null) {
            return markers;
        }

        double offset =
                VideoOffsetCalculator.offsetSeconds(
                        curr.wallClockMillis(), recordConfirmedWallMs, durationS);
        Integer gameClock = curr.gameClock();

        // Running-total counters: one marker per increment, each counter independent.
        emitIncrements(markers, "kill", curr.kills() - prev.kills(), offset, gameClock);
        emitIncrements(markers, "assist", curr.assists() - prev.assists(), offset, gameClock);
        int deathDelta = curr.deaths() - prev.deaths();
        emitIncrements(markers, "death", deathDelta, offset, gameClock);

        // Falling-edge death as a FALLBACK signal, used only when the deaths counter did NOT already
        // catch this death on the same tick -- otherwise a single death emits two identical markers.
        // The counter is the primary signal; the edge covers the case where it lagged or didn't move.
        // Gated on hero presence on BOTH frames so a vanished hero block (load / hero-select /
        // reconnect) cannot manufacture a phantom death.
        if (deathDelta <= 0
                && prev.heroPresent()
                && curr.heroPresent()
                && prev.alive()
                && !curr.alive()) {
            markers.add(PendingMarker.gsi("death", offset, gameClock));
        }

        return markers;
    }

    private void emitIncrements(
            List<PendingMarker> markers, String type, int delta, double offset, Integer gameClock) {
        for (int i = 0; i < delta; i++) {
            markers.add(PendingMarker.gsi(type, offset, gameClock));
        }
    }
}
