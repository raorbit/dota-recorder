package dev.dotarec.tagger;

/**
 * Computes {@code markers.video_offset_s}: where a GSI event lands in the recorded .mp4.
 *
 * <p>Plan: the offset "maps a game event to a frame in the recorded .mp4" and drives the
 * scrubber dots. It is derived purely from wall-clock arrival relative to the instant OBS
 * confirmed recording (OUTPUT_STARTED) -- NEVER from {@code game_clock}, which can be negative
 * pre-horn, frozen during pauses, and is unrelated to elapsed video time.
 *
 * <p>This is the one piece of real logic in the foundation; it is pure and side-effect free.
 */
public final class VideoOffsetCalculator {

    private VideoOffsetCalculator() {
    }

    /**
     * @param frameWallMs           local wall-clock millis the GSI frame arrived
     * @param recordConfirmedWallMs local wall-clock millis OBS confirmed recording started
     * @param durationS             recording duration in seconds (upper clamp bound)
     * @return seconds into the video, clamped to [0, durationS]
     */
    public static double offsetSeconds(long frameWallMs, long recordConfirmedWallMs, double durationS) {
        double raw = (frameWallMs - recordConfirmedWallMs) / 1000.0;
        if (raw < 0.0) {
            return 0.0;
        }
        if (raw > durationS) {
            return durationS;
        }
        return raw;
    }
}
