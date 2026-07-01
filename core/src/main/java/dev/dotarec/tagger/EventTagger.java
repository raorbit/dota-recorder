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
 * {@code onFrame}). It keeps {@code emittedDeaths} -- a HIGH-WATER MARK of deaths already tagged,
 * seeded to the running total on the first player-present frame -- plus a per-dead-episode dedupe
 * latch, so it survives desync modes the raw diff misses:
 * <ul>
 *   <li><b>Counter/alive straddle (Finding B):</b> the deaths increment and the alive true-&gt;false
 *       edge describe one death but can land on adjacent ticks. The counter path emits deaths beyond
 *       the high-water mark; the falling edge is a fallback gated by the latch that also advances the
 *       high-water mark, so the counter catching up later -- even after a respawn cleared the latch --
 *       never re-emits it. Exactly one marker per death whichever signal leads and however far apart.</li>
 *   <li><b>Block dropout / unobserved respawn (Finding C):</b> if the player/hero block vanishes on the
 *       death tick, or the whole respawn window between two deaths is dropped by GSI, the monotonic
 *       counter still reveals those deaths against the high-water mark once the block returns, so none
 *       are lost -- the counter path is deliberately NOT gated by the episode latch.</li>
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

        // Seed the deaths high-water mark from the first player-present frame's running total, so joining
        // a match already in progress (or a recording that arms mid-life) never burst-emits the
        // pre-existing death count as markers. Seeded from prev so the very first prev->curr delta (a
        // single-pair diff, or the FSM's first tag) is still captured.
        if (!state.deathsSeeded() && prev.playerPresent()) {
            state.seedDeaths(prev.deaths());
        }

        // Respawn resets the dead-episode dedupe latch so the NEXT death's falling edge can emit again. A
        // rising edge (dead / hero-absent prev -> alive+hero-present curr) marks the end of the current
        // dead episode. Reset on the RISING edge only (not on every alive frame) so the counter-leads-edge
        // case -- the deaths counter increments while alive is still true, the flip coming a tick later --
        // does not clear the latch between the two signals and double-count.
        boolean respawned =
                curr.heroPresent() && curr.alive() && (!prev.heroPresent() || !prev.alive());
        if (respawned) {
            state.resetDeathEpisode();
        }

        // Kill/assist counters: raw prev->curr diff, gated on the player block being present on BOTH frames
        // (a dropout zeroes the counters, so a returning frame would otherwise burst-emit phantom markers).
        if (prev.playerPresent() && curr.playerPresent()) {
            emitIncrements(markers, "kill", curr.kills() - prev.kills(), offset, gameClock);
            emitIncrements(markers, "assist", curr.assists() - prev.assists(), offset, gameClock);
        }

        // Death counter path (primary, authoritative). The running deaths counter is monotonic, so emit
        // every death it shows BEYOND emittedDeaths, the high-water mark of deaths already tagged. This is
        // deliberately NOT gated by the episode latch: a death is still emitted when the intervening
        // respawn window was never observed (dropped frames) or a block-dropout tick hid the death -- the
        // counter reveals it once the player block returns (Finding C). The high-water mark also means the
        // counter never re-emits a death the falling edge already tagged (it cannot re-cross that value).
        boolean deathCounterFired = false;
        if (curr.playerPresent() && state.deathsSeeded()) {
            int newDeaths = curr.deaths() - state.emittedDeaths();
            if (newDeaths > 0) {
                emitIncrements(markers, "death", newDeaths, offset, gameClock);
                state.setEmittedDeaths(curr.deaths());
                state.markDeathEmittedThisEpisode();
                deathCounterFired = true;
            }
        }

        // Falling-edge death (fallback) for when the deaths counter lags or never moves. Gated on hero
        // presence on BOTH frames (so a vanished hero block can't manufacture a phantom death) AND on the
        // per-episode latch (so it fires at most once per dead episode and never duplicates a death the
        // counter already tagged). It advances the high-water mark by one, so the counter catching up later
        // -- even after a respawn cleared the latch -- cannot re-emit the same death (Finding B, both the
        // counter-leads and the counter-lags-past-respawn cases).
        if (!deathCounterFired
                && !state.deathEmittedThisEpisode()
                && state.deathsSeeded()
                && prev.heroPresent()
                && curr.heroPresent()
                && prev.alive()
                && !curr.alive()) {
            markers.add(PendingMarker.gsi("death", offset, gameClock));
            state.setEmittedDeaths(state.emittedDeaths() + 1);
            state.markDeathEmittedThisEpisode();
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
