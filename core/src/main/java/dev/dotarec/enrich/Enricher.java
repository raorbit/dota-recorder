package dev.dotarec.enrich;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Post-match enrichment: merges the official match record into a recorded row.
 *
 * <p>Plan (Enricher): after the game, poll the API by Dota match_id for the canonical scoreboard
 * (result, duration, all 10 players, heroes/items/net worth, rank tier, mode), optionally parse
 * the replay for exact event timings, and update the {@code matches}/{@code markers} rows in
 * place. Runs async because the API + replay lag the match by minutes. MMR delta is in no API --
 * it is inferred or user-entered.
 *
 * <p>TODO(plan): implement keyed on dotaMatchId via {@code OpenDotaClient} + (later) Clarity.
 */
@Service
public class Enricher {

    private final OpenDotaClient client;

    public Enricher(OpenDotaClient client) {
        this.client = client;
    }

    /** TODO(plan: Enricher): fetch details for dotaMatchId, merge, update row. */
    @Async
    public void enrich(long dotaMatchId) {
        // No-op for v0.1 foundation.
    }
}
