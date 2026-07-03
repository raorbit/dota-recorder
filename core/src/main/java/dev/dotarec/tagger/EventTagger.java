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
 *       INDEPENDENTLY against its own HIGH-WATER MARK and a marker is emitted per increment, because a
 *       single ~10Hz tick can carry several at once (e.g. you trade a kill, an assist, and your own
 *       death in the same frame). A positive delta of N emits N markers of that type (rare, but a
 *       dropped frame can batch two kills into one tick). Each counter path is gated on the player block
 *       being present on {@code curr}; the mark is only seeded from a player-present frame, so a
 *       heartbeat / reconnect that drops the player block (counters default to 0) never burst-emits the
 *       returning [player absent: 0/0/0] -&gt; [player back: non-zero KDA] pair as phantom markers, and a
 *       kill/assist that landed on a single-frame block-dropout tick is still tagged once the monotonic
 *       counter passes the mark when the block returns.</li>
 *   <li>A death is detected from the {@code deaths} counter delta (primary) OR the {@code hero.alive}
 *       true-&gt;false FALLING EDGE (fallback when the counter lagged), but a single death emits a
 *       single marker even when those two signals straddle ADJACENT ticks. The falling edge is gated
 *       on the hero block being present on BOTH frames: on load / hero-select the block is absent
 *       ({@code heroPresent=false} -&gt; {@code alive=false}), and without that guard the absence would
 *       read as a phantom death.</li>
 * </ul>
 *
 * <p>Detection is NOT a pure per-tick prev-&gt;curr diff: it carries a small {@link TaggerState}
 * across ticks (owned by the {@code RecordingSession}, written only under the FSM's synchronized
 * {@code onFrame}). It keeps a HIGH-WATER MARK of already-tagged kills/deaths/assists, each seeded to
 * the running total on the first player-present frame, plus a per-dead-episode dedupe latch, so death
 * detection survives desync modes the raw diff misses:
 * <ul>
 *   <li><b>Counter/alive straddle:</b> the deaths increment and the alive true-&gt;false
 *       edge describe one death but can land on adjacent ticks. The counter path emits deaths beyond
 *       the high-water mark; the falling edge is a fallback gated by the latch that also advances the
 *       high-water mark, so the counter catching up later -- even after a respawn cleared the latch --
 *       never re-emits it. Exactly one marker per death whichever signal leads and however far apart.</li>
 *   <li><b>Block dropout / unobserved respawn:</b> if the player/hero block vanishes on the
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

        // Seed the deaths/kills/assists high-water marks from the first player-present frame's running
        // totals, so joining a match already in progress (or a recording that arms mid-life) never
        // burst-emits the pre-existing counts as markers. Seeded from prev so the very first prev->curr
        // delta (a single-pair diff, or the FSM's first tag) is still captured.
        if (prev.playerPresent()) {
            state.deaths().seedIfUnseeded(prev.deaths());
            state.kills().seedIfUnseeded(prev.kills());
            state.assists().seedIfUnseeded(prev.assists());
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

        // Kill/assist counters share one emit-beyond-the-mark rule (deaths extends it below with the
        // falling-edge fallback and the episode latch).
        if (curr.playerPresent()) {
            emitBeyondMark(markers, "kill", state.kills(), curr.kills(), offset, gameClock);
            emitBeyondMark(markers, "assist", state.assists(), curr.assists(), offset, gameClock);
        }

        // Death counter path (primary, authoritative). The running deaths counter is monotonic, so emit
        // every death it shows BEYOND emittedDeaths, the high-water mark of deaths already tagged. This is
        // deliberately NOT gated by the episode latch: a death is still emitted when the intervening
        // respawn window was never observed (dropped frames) or a block-dropout tick hid the death -- the
        // counter reveals it once the player block returns. The high-water mark also means the
        // counter never re-emits a death the falling edge already tagged (it cannot re-cross that value).
        boolean deathCounterFired = false;
        if (curr.playerPresent() && state.deaths().seeded()) {
            int newDeaths = curr.deaths() - state.deaths().value();
            if (newDeaths > 0) {
                emitIncrements(markers, "death", newDeaths, offset, gameClock);
                state.deaths().set(curr.deaths());
                state.markDeathEmittedThisEpisode();
                deathCounterFired = true;
            }
        }

        // Falling-edge death (fallback) for when the deaths counter lags or never moves. Gated on hero
        // presence on BOTH frames (so a vanished hero block can't manufacture a phantom death) AND on the
        // per-episode latch (so it fires at most once per dead episode and never duplicates a death the
        // counter already tagged). It advances the high-water mark by one, so the counter catching up later
        // -- even after a respawn cleared the latch -- cannot re-emit the same death (covers both the
        // counter-leads and the counter-lags-past-respawn cases).
        if (!deathCounterFired
                && !state.deathEmittedThisEpisode()
                && state.deaths().seeded()
                && prev.heroPresent()
                && curr.heroPresent()
                && prev.alive()
                && !curr.alive()) {
            markers.add(PendingMarker.gsi("death", offset, gameClock));
            state.deaths().set(state.deaths().value() + 1);
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

    /**
     * Emits one marker per increment of a monotonic counter BEYOND its high-water mark, then advances
     * the mark. Callers gate on the player block being present on {@code curr}; an unseeded mark also
     * suppresses the returning-from-dropout burst (marks are only seeded from a player-present frame).
     * The monotonic counter reveals a kill/assist that landed on a single-frame block-dropout tick once
     * the block returns -- a raw prev-&gt;curr diff dropped it.
     */
    private void emitBeyondMark(List<PendingMarker> markers, String type,
            TaggerState.HighWaterCounter mark, int total, double offset, Integer gameClock) {
        if (!mark.seeded()) {
            return;
        }
        int fresh = total - mark.value();
        if (fresh > 0) {
            emitIncrements(markers, type, fresh, offset, gameClock);
            mark.set(total);
        }
    }

    private void emitIncrements(
            List<PendingMarker> markers, String type, int delta, double offset, Integer gameClock) {
        for (int i = 0; i < delta; i++) {
            markers.add(PendingMarker.gsi(type, offset, gameClock));
        }
    }
}
