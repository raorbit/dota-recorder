package dev.dotarec.enrich;

import dev.dotarec.data.MatchRepository;
import dev.dotarec.data.MatchRepository.PendingMatch;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poller that dispatches eligible {@code pending} rows to the {@link Enricher}.
 *
 * <p>Every 60s it asks the repository for pending match rows that have a Dota match id, are under
 * the attempt cap, and whose backoff window has elapsed, then fire-and-forgets each onto the
 * bounded {@code enrichExecutor} (the {@code @Async} dispatch returns immediately). 60s is well
 * inside OpenDota's rate limits and under its post-match backfill lag.
 *
 * <p>Division of labour: the queue is a dumb eligibility filter (it only reads the retry columns
 * in its WHERE); the enricher owns the terminal-state decision (cap crossing -> {@code failed}),
 * since it knows <em>why</em> a fetch didn't succeed. The repository's {@code LIMIT} keeps a
 * backlog from flooding the pool in one tick; the enricher's idempotent {@code enriched}
 * short-circuit makes a late straggler harmless.
 */
@Component
public class EnrichmentQueue {

    private static final Logger log = LoggerFactory.getLogger(EnrichmentQueue.class);

    /** Stop re-polling a match after this many attempts; the crossing attempt flips it to failed. */
    static final int MAX_ATTEMPTS = 10;

    private final MatchRepository matches;
    private final Enricher enricher;

    public EnrichmentQueue(MatchRepository matches, Enricher enricher) {
        this.matches = matches;
        this.enricher = enricher;
    }

    /** Polls and dispatches eligible pending rows. Cadence mirrors {@code RetentionSweeper}. */
    @Scheduled(fixedDelay = 60_000L)
    public void sweep() {
        List<PendingMatch> pending = matches.findPendingEnrichment(MAX_ATTEMPTS, System.currentTimeMillis());
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Dispatching {} pending matches for enrichment", pending.size());
        for (PendingMatch m : pending) {
            // @Async -> returns immediately, runs on enrichExecutor. The WHERE already excluded
            // null dota_match_id, and the enricher re-asserts it.
            enricher.enrich(m.id(), m.dotaMatchId());
        }
    }
}
