package dev.dotarec.tagger;

import dev.dotarec.fsm.RecordingSession.TaggerState;
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
 *       two kills into one tick). The kill/assist counter diff is gated on the PLAYER block being
 *       present on BOTH frames: a heartbeat / reconnect drops the player block (counters default to
 *       0), so a [player absent: 0/0/0] -&gt; [player back: non-zero KDA] pair would otherwise emit a
 *       burst of phantom kill/assist markers -- the gate suppresses that.</li>
 *   <li>A death is detected from the {@code deaths} counter delta (primary) OR the {@code hero.alive}
 *       true-&gt;false FALLING EDGE (fallback when the counter lagged), but a single death emits a
 *       single marker even when those two signals straddle ADJACENT ticks. The falling edge is gated
 *       on the hero block being present on BOTH frames: on load / hero-select the block is absent
 *       ({@code heroPresent=false} -&gt; {@code alive=false}), and without that guard the absence would
 *       read as a phantom death.</li>
 * </ul>
 *
 * <p>Death detection is NOT a pure per-tick prev-&gt;curr diff: it carries a small {@link TaggerState}
 * across ticks (owned by the {@code RecordingSession}, written only under the FSM's synchronized
 * {@code onFrame}) so it survives two desync modes the raw diff misses:
 * <ul>
 *   <li><b>Counter/alive straddle (Finding B):</b> the deaths increment and the alive true-&gt;false
 *       edge can land on adjacent ticks. A per-episode dedupe latch ({@code deathEmittedThisEpisode})
 *       ensures exactly one marker per dead episode, reset on the next respawn (a dead-&gt;alive rising
 *       edge), so it works whichever signal leads.</li>
 *   <li><b>Block dropout on the death tick (Finding C):</b> if the player/hero block vanishes on the
 *       exact death tick (a heartbeat/reconnect zeroes the counters), the death is diffed against the
 *       last frame that actually HAD the player block ({@code lastGood*} counters) rather than the raw
 *       block-absent prev, so it is emitted when the block returns.</li>
 * </ul>
 *
 * <p>Each marker's {@code video_offset_s} comes from {@link VideoOffsetCalculator} anchored on the
 * OBS record-confirmed monotonic stamp; {@code game_clock} is stored as a display label only. The FSM
 * buffers the returned {@link PendingMarker}s on the {@code RecordingSession} and persists them at
 * finalize.
 */
@Service
public class EventTagger {

    /**
     * Diffs {@code prev} -&gt; {@code curr} for the own player against a persistent {@code state} and
     * returns the markers detected on this tick (possibly empty, never null). The FSM passes the
     * in-flight recording's {@link TaggerState} so death detection survives cross-tick counter/alive
     * desync and single-frame block dropouts (see class doc).
     *
     * @param prev                   the previous frame, or null for the first frame of a recording
     *                               (no diff is possible, so no markers)
     * @param curr                   the current frame
     * @param state                  the recording's cross-tick tagger working state
     * @param recordConfirmedNanos   {@code System.nanoTime()} stamp OBS confirmed OUTPUT_STARTED (the
     *                               offset anchor; same monotonic clock as {@code curr.monotonicNanos()})
     * @param durationS              upper clamp bound passed to {@link VideoOffsetCalculator}; live
     *                               callers pass a generous bound, finalize re-clamps to real duration
     */
    public List<PendingMarker> diff(
            GsiFrame prev,
            GsiFrame curr,
            TaggerState state,
            long recordConfirmedNanos,
            double durationS) {
        List<PendingMarker> markers = new ArrayList<>();
        if (prev == null || curr == null) {
            return markers;
        }

        double offset =
                VideoOffsetCalculator.offsetSeconds(
                        curr.monotonicNanos(), recordConfirmedNanos, durationS);
        Integer gameClock = curr.gameClock();

        // Seed the last-good baseline from prev on the FIRST diff (state still empty): a single unit-test
        // diff() call, and the FSM's first tagAndObserve, otherwise have no earlier player-present frame
        // folded in yet. Redundant-but-harmless once the state has been primed by prior curr updates.
        if (state.hasNoGoodCounters() && prev.playerPresent()) {
            state.updateLastGoodCounters(prev.kills(), prev.deaths(), prev.assists());
        }

        // Respawn resets the dead-episode dedupe latch so the NEXT death can emit again. A rising edge
        // (dead / hero-absent prev -> alive+hero-present curr) marks the end of the current dead episode.
        // Reset on the RISING edge only (not on every alive frame) so the counter-leads-edge case -- where
        // the deaths counter increments while alive is still true, the flip coming a tick later -- does not
        // clear the latch between the two signals and double-count.
        boolean respawned =
                curr.heroPresent() && curr.alive() && (!prev.heroPresent() || !prev.alive());
        if (respawned) {
            state.resetDeathEpisode();
        }

        // Kill/assist counters: unchanged raw prev->curr diff, gated on the player block being present on
        // BOTH frames (a dropout zeroes the counters, so a returning frame would otherwise burst-emit).
        if (prev.playerPresent() && curr.playerPresent()) {
            emitIncrements(markers, "kill", curr.kills() - prev.kills(), offset, gameClock);
            emitIncrements(markers, "assist", curr.assists() - prev.assists(), offset, gameClock);
        }

        // Death counter path (primary). Diffed against the LAST-GOOD deaths total (the last player-present
        // frame's), not the raw prev, so a death on a single-frame block dropout is still caught when the
        // block returns (Finding C). Gated on curr carrying the player block AND on having a good baseline
        // (hasNoGoodCounters == a player block has never been seen yet -> diffing would burst-emit the full
        // running total). Deduped per dead episode: if the falling edge already emitted this death on an
        // earlier tick, the latch suppresses the counter catching up now (Finding B).
        boolean deathCounterFired = false;
        if (curr.playerPresent() && !state.hasNoGoodCounters()) {
            int deathDelta = curr.deaths() - state.lastGoodDeaths();
            if (deathDelta > 0 && !state.deathEmittedThisEpisode()) {
                emitIncrements(markers, "death", deathDelta, offset, gameClock);
                state.markDeathEmittedThisEpisode();
                deathCounterFired = true;
            }
        }

        // Falling-edge death (fallback) for when the counter lagged or didn't move. Gated on hero presence
        // on BOTH frames so a vanished hero block (load / hero-select / reconnect) can't manufacture a
        // phantom death. Skipped when the counter already emitted a death this tick, or when this dead
        // episode already produced a marker (the counter emitted it on an earlier tick) -- so one death is
        // one marker whether the counter and edge land on the same or adjacent ticks (Finding B).
        if (!deathCounterFired
                && !state.deathEmittedThisEpisode()
                && prev.heroPresent()
                && curr.heroPresent()
                && prev.alive()
                && !curr.alive()) {
            markers.add(PendingMarker.gsi("death", offset, gameClock));
            state.markDeathEmittedThisEpisode();
        }

        // Roll the last-good counters forward to curr when it carries the player block, so the next tick
        // diffs against the freshest present totals (and a dropout tick holds the prior good baseline).
        if (curr.playerPresent()) {
            state.updateLastGoodCounters(curr.kills(), curr.deaths(), curr.assists());
        }

        return markers;
    }

    /**
     * Stateless convenience overload: diffs {@code prev} -&gt; {@code curr} with a FRESH one-shot
     * {@link TaggerState}. Retained for callers/tests that diff a single synthetic frame pair in
     * isolation; the FSM uses the stateful overload above with the recording's persistent state.
     */
    public List<PendingMarker> diff(
            GsiFrame prev, GsiFrame curr, long recordConfirmedNanos, double durationS) {
        return diff(prev, curr, new TaggerState(), recordConfirmedNanos, durationS);
    }

    private void emitIncrements(
            List<PendingMarker> markers, String type, int delta, double offset, Integer gameClock) {
        for (int i = 0; i < delta; i++) {
            markers.add(PendingMarker.gsi(type, offset, gameClock));
        }
    }
}
