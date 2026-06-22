package dev.dotarec.enrich;

import dev.dotarec.bridge.EventPublisher;
import dev.dotarec.config.SettingsStore;
import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.EnrichmentUpdate;
import dev.dotarec.data.MatchSummary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Post-match enrichment: fetches the official scoreboard for a recorded row and merges result +
 * stats in place, then notifies the UI. Runs async on the bounded {@code enrichExecutor} because
 * the API lags the match by minutes; the {@link EnrichmentQueue} dispatches eligible rows and this
 * service decides the terminal state from the fetch outcome.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@link FetchResult.Ready} -> full enrich (win formula + our-player stats), state
 *       {@code enriched}, publish {@code match.enriched}. If OUR player isn't in the scoreboard the
 *       result can't be attributed -> permanent {@code failed} + {@code match.enrichFailed}.</li>
 *   <li>{@link FetchResult.NotReady} / {@link FetchResult.Missing} / {@link FetchResult.Transient}
 *       -> stay {@code pending}, bump attempts + backoff, publish nothing. On the attempt that
 *       crosses the cap -> {@code failed} + {@code match.enrichFailed} so it stops looping forever.
 *       (The classified-only enum variants carry no body, so there is nothing to partially persist;
 *       a 200-but-unparsed response still re-fetches cleanly once OpenDota finishes parsing.)</li>
 * </ul>
 *
 * <p>Idempotent + defensive: re-reads the row, never touches a null {@code dota_match_id}, and
 * short-circuits an already-{@code enriched} row. {@code mmr_delta} stays null (no API has it).
 */
@Service
public class Enricher {

    private static final Logger log = LoggerFactory.getLogger(Enricher.class);

    /** Attempt cap shared with the queue's eligibility filter; the crossing attempt flips to failed. */
    static final int MAX_ATTEMPTS = 10;

    private static final long BACKOFF_BASE_MS = 60_000L;
    private static final long BACKOFF_CAP_MS = 30L * 60_000L;

    private final MatchSource matchSource;
    private final MatchRepository matches;
    private final SettingsStore settings;
    private final EventPublisher events;

    /** Latched so a null-accountId config gap warns once, not on every poll. */
    private volatile boolean warnedNullAccount = false;

    public Enricher(MatchSource matchSource, MatchRepository matches, SettingsStore settings,
                    EventPublisher events) {
        this.matchSource = matchSource;
        this.matches = matches;
        this.settings = settings;
        this.events = events;
    }

    /**
     * Enriches the row identified by {@code matchRowId} (the surrogate key the UPDATE targets) using
     * {@code dotaMatchId} (the OpenDota lookup key). Defensive against duplicate/late dispatches; any
     * unexpected exception leaves the row {@code pending} (transient) and never escapes the executor.
     */
    @Async("enrichExecutor")
    public void enrich(long matchRowId, long dotaMatchId) {
        try {
            Optional<MatchSummary> found = matches.findById(matchRowId);
            if (found.isEmpty()) {
                return; // row vanished (e.g. deleted) -- nothing to do
            }
            MatchSummary row = found.get();
            // NEVER touch a row with a null dota match id (manual/clip). The queue filters these,
            // but enrich is independently safe.
            if (row.dotaMatchId() == null) {
                return;
            }
            // Idempotent: a duplicate dispatch for an already-enriched row is a harmless no-op.
            if ("enriched".equals(row.enrichmentState())) {
                return;
            }

            FetchResult result = matchSource.fetch(dotaMatchId);
            int attempts = matches.enrichAttempts(matchRowId);

            if (result instanceof FetchResult.Ready ready) {
                fullEnrich(matchRowId, dotaMatchId, ready.match(), attempts);
            } else {
                // NotReady / Missing / Transient all retry (state stays 'pending' with backoff),
                // or fail permanently on the attempt that crosses the cap.
                retryOrFail(matchRowId, attempts);
            }
        } catch (RuntimeException e) {
            // Leave state 'pending' (transient); the queue will retry. Never let it escape the
            // async method (the executor would just swallow it anyway).
            log.warn("Unexpected error enriching match row {} (dota {}): {}",
                    matchRowId, dotaMatchId, e.toString());
        }
    }

    private void fullEnrich(long matchRowId, long dotaMatchId, OpenDotaMatch match, int attempts) {
        Long accountId = settings.get().accountId;
        if (accountId == null) {
            // Config gap (first run before the user set their account id): HOLD in pending rather
            // than fail, so enrichment resumes once configured. Warn once.
            if (!warnedNullAccount) {
                warnedNullAccount = true;
                log.warn("accountId is not set in settings -- holding matches in 'pending' until "
                        + "configured; enrichment cannot attribute result/stats without it.");
            }
            retryOrFail(matchRowId, attempts);
            return;
        }

        OpenDotaMatch.Player me = ourPlayer(match, accountId);
        if (me == null) {
            // Our account isn't in the scoreboard (smurf/anonymized/id mismatch). The win/stats
            // can't be attributed -> permanent fail, but the row stays visible in Unsorted.
            failPermanently(matchRowId);
            return;
        }

        // A Ready body only guarantees a non-null duration; the winner or our player_slot can still
        // be null on an early/partial parse. Don't fabricate a win/loss from a null (it would read as
        // a confident "Dire" and get marked terminal forever) -- HOLD in pending and retry until the
        // fields populate, or fail at the cap.
        if (match.radiant_win() == null || me.player_slot() == null) {
            retryOrFail(matchRowId, attempts);
            return;
        }

        boolean win = Boolean.TRUE.equals(match.radiant_win()) == isRadiant(me);
        EnrichmentUpdate update = new EnrichmentUpdate(
                win ? "win" : "loss",
                match.lobby_type(),
                match.game_mode(),
                me.gold_per_min(),
                me.xp_per_min(),
                me.net_worth(),
                me.last_hits(),
                me.rank_tier(),
                match.duration(),
                playedAtMs(match.start_time()),
                "enriched",
                attempts, // no further attempts needed; leave the counter as-is
                null);
        matches.applyEnrichment(matchRowId, update);
        events.publish("match.enriched", Map.of("id", matchRowId, "dotaMatchId", dotaMatchId));
        log.info("Enriched match row {} (dota {}) -> {}", matchRowId, dotaMatchId, update.result());
    }

    /**
     * NotReady/Missing/Transient/null-account/partial-body: bump attempts + backoff, or fail on the
     * cap-crossing attempt. Routes through the narrow {@link MatchRepository#applyRetry} so it only
     * touches the retry bookkeeping and can never blank recorder-owned columns (duration/played_at).
     */
    private void retryOrFail(long matchRowId, int attempts) {
        int nextAttempts = attempts + 1;
        if (nextAttempts >= MAX_ATTEMPTS) {
            failPermanently(matchRowId);
            return;
        }
        matches.applyRetry(matchRowId, "pending", 1, backoffNextAfterMs(nextAttempts));
    }

    private void failPermanently(long matchRowId) {
        // Terminal 'failed'; the +1 keeps the counter monotonic without a read-then-write (the exact
        // value no longer matters once the state leaves 'pending'). Recorder columns are preserved.
        matches.applyRetry(matchRowId, "failed", 1, null);
        events.publish("match.enrichFailed", Map.of("id", matchRowId));
        log.info("Enrichment permanently failed for match row {}", matchRowId);
    }

    /**
     * Our scoreboard row, matched by account id. Assumes exactly one row matches (always true for a
     * real 10-player match); first match wins otherwise.
     */
    private OpenDotaMatch.Player ourPlayer(OpenDotaMatch match, long accountId) {
        if (match.players() == null) {
            return null;
        }
        return match.players().stream()
                .filter(p -> p != null && Objects.equals(p.account_id(), accountId))
                .findFirst()
                .orElse(null);
    }

    /** Radiant = player_slot 0-127, Dire = 128+. */
    private static boolean isRadiant(OpenDotaMatch.Player p) {
        return p.player_slot() != null && p.player_slot() < 128;
    }

    private static Long playedAtMs(Long startTimeSeconds) {
        return startTimeSeconds == null ? null : startTimeSeconds * 1000L;
    }

    /** {@code now + min(60s * 2^attempts, 30min)}. */
    private static long backoffNextAfterMs(int attempts) {
        long delay = BACKOFF_BASE_MS;
        for (int i = 0; i < attempts && delay < BACKOFF_CAP_MS; i++) {
            delay = Math.min(delay * 2, BACKOFF_CAP_MS);
        }
        return System.currentTimeMillis() + Math.min(delay, BACKOFF_CAP_MS);
    }
}
