package dev.dotarec.tagger;

/**
 * A timeline marker the {@link EventTagger} has detected but not yet persisted. The FSM buffers
 * these on the in-flight {@code RecordingSession} and writes them to the {@code markers} table at
 * finalize (one DB round trip per match instead of one per ~10Hz frame).
 *
 * <p>{@code videoOffsetS} is already resolved against the OBS record-confirmed anchor via
 * {@code VideoOffsetCalculator}; {@code gameClock} is carried as a display label only and is never
 * used for offset math. {@code source} is always {@code "gsi"} for live-tagged markers (the replay
 * pass writes {@code "replay"} markers later).
 *
 * @param type         marker kind ({@code kill}, {@code death}, {@code assist})
 * @param videoOffsetS seconds into the recorded .mp4
 * @param gameClock    in-game clock seconds at the event (display label; nullable)
 * @param label        optional human-readable label, or null
 * @param source       {@code gsi} (live) or {@code replay} (enriched)
 */
public record PendingMarker(
        String type,
        double videoOffsetS,
        Integer gameClock,
        String label,
        String source) {

    /** Convenience factory for a live GSI marker with no label. */
    public static PendingMarker gsi(String type, double videoOffsetS, Integer gameClock) {
        return new PendingMarker(type, videoOffsetS, gameClock, null, "gsi");
    }
}
