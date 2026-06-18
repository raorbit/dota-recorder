package dev.dotarec.retention;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Enforces the disk budget for recorded VODs.
 *
 * <p>Plan-derived retention policy (modeled on Warcraft Recorder): keep total video usage under a
 * ~50GB cap by deleting oldest-first, but never delete starred recordings. When a video file is
 * removed the {@code matches} row is kept with a null {@code video_path} so the metadata/markers
 * survive as a browsable record without a playable clip.
 *
 * <p>TODO(plan): implement the sweep -- sum video sizes, while over cap delete oldest non-starred
 * .mp4 (+ thumbnail) and null its {@code video_path}.
 */
@Component
public class RetentionSweeper {

    /** Soft disk cap for recorded video, in bytes (~50GB). */
    private static final long CAP_BYTES = 50L * 1024 * 1024 * 1024;

    /** TODO(plan): periodic sweep; oldest non-starred first; keep row with null video_path. */
    @Scheduled(fixedDelay = 3_600_000L)
    public void sweep() {
        // No-op for v0.1 foundation.
    }
}
