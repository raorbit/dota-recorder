package dev.dotarec.enrich;

/**
 * The testability seam between the enricher and the network. The implementation
 * ({@link OpenDotaClient}) owns transport (HTTP, status mapping, JSON parse); the {@link Enricher}
 * owns mapping the parsed body into a row. Tests feed a fake {@code MatchSource} returning canned
 * {@link FetchResult}s so the enricher's state machine is exercised with no socket.
 */
public interface MatchSource {

    /**
     * Fetches and classifies the official match record for {@code dotaMatchId}. Never throws --
     * transport/parse failures are returned as {@link FetchResult.Transient} so the queue can retry.
     */
    FetchResult fetch(long dotaMatchId);
}
