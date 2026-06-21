package dev.dotarec.enrich;

/**
 * Outcome of a single {@link MatchSource#fetch(long)} call. The discriminant drives the queue's
 * retry-vs-permanent-fail decision: only {@link Ready} carries a body to merge; the three
 * non-ready variants all mean "leave the row {@code pending} and retry" (the enricher applies
 * backoff and flips to {@code failed} only when the attempt cap is crossed).
 */
public sealed interface FetchResult {

    /** 200 with a fully parsed body (duration + stats present). The only path that enriches. */
    record Ready(OpenDotaMatch match) implements FetchResult {
    }

    /** Convenience factory for a {@link Ready} result around a parsed body. */
    static FetchResult ready(OpenDotaMatch match) {
        return new Ready(match);
    }

    /** 200 but unparsed by OpenDota yet (duration/stats null). Retry; a partial update is safe. */
    enum NotReady implements FetchResult {
        INSTANCE
    }

    /** 404 -- OpenDota hasn't ingested the match yet (backfill lag). Retry up to the cap. */
    enum Missing implements FetchResult {
        INSTANCE
    }

    /** Network/timeout/5xx/IOException or a transient JSON hiccup on a 200 body. Retry. */
    enum Transient implements FetchResult {
        INSTANCE
    }
}
