package dev.dotarec.tagger;

/**
 * Computes {@code markers.video_offset_s}: where a GSI event lands in the recorded .mp4.
 *
 * <p>Plan: the offset "maps a game event to a frame in the recorded .mp4" and drives the
 * scrubber dots. It is derived purely from the elapsed time between the frame's arrival and the
 * instant OBS confirmed recording (OUTPUT_STARTED) -- NEVER from {@code game_clock}, which can be
 * negative pre-horn, frozen during pauses, and is unrelated to elapsed video time. Both instants
 * are {@code System.nanoTime()} stamps (a MONOTONIC source), so an OS/NTP wall-clock step between
 * the anchor and a frame cannot clamp or shift the marker; the two must come from the SAME clock.
 *
 * <p>This is the one piece of real logic in the foundation; it is pure and side-effect free.
 */
public final class VideoOffsetCalculator {

    private VideoOffsetCalculator() {
    }

    /**
     * @param frameNanos            {@code System.nanoTime()} stamp when the GSI frame arrived
     * @param recordConfirmedNanos  {@code System.nanoTime()} stamp when OBS confirmed recording started
     * @param durationS             recording duration in seconds (upper clamp bound)
     * @return seconds into the video, clamped to [0, durationS]
     */
    public static double offsetSeconds(long frameNanos, long recordConfirmedNanos, double durationS) {
        double raw = (frameNanos - recordConfirmedNanos) / 1_000_000_000.0;
        if (raw < 0.0) {
            return 0.0;
        }
        if (raw > durationS) {
            return durationS;
        }
        return raw;
    }
}
