package dev.dotarec.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link ClipRepository} against a real, migrated SQLite DB ({@link TestDb}) — no mocks, so the
 * compare-and-set status transitions run against the actual schema. Focuses on
 * {@link ClipRepository#updateStatusIfGenerating}, the terminal-write CAS that stops a slow render's
 * original worker from resurrecting/overwriting a row that was already re-pended and re-claimed.
 */
class ClipRepositoryTest {

    private DataSource ds;
    private ClipRepository clips;
    private MatchRepository matches;
    private long parentMatchId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        ds = TestDb.migrated(dir);
        clips = new ClipRepository(ds);
        matches = new MatchRepository(ds);
        parentMatchId = matches.insert(new MatchRepository.NewMatch(
                null, "match", "enriched", "puck",
                null, null, null, null, null, null, null,
                null, null, null, null, null, 1800,
                null, "video.mp4", null, null, false, System.currentTimeMillis(), null));
    }

    @Test
    void updateStatusIfGenerating_onGeneratingRow_writesReady() {
        long clipId = insertClip("generating");

        int updated = clips.updateStatusIfGenerating(clipId, "ready", "out.mp4", 64L, "thumb.jpg", null);

        assertThat(updated).isEqualTo(1);
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("ready");
        assertThat(row.videoPath()).isEqualTo("out.mp4");
        assertThat(row.fileSizeBytes()).isEqualTo(64L);
        assertThat(row.thumbPath()).isEqualTo("thumb.jpg");
    }

    @Test
    void updateStatusIfGenerating_onPendingRow_isNoOp() {
        // A row already re-pended (and possibly re-claimed by a second worker) must not be overwritten
        // by the original worker's terminal write — the CAS on status='generating' fails.
        long clipId = insertClip("pending");

        int readyUpdated =
                clips.updateStatusIfGenerating(clipId, "ready", "out.mp4", 64L, "thumb.jpg", null);
        int failedUpdated =
                clips.updateStatusIfGenerating(clipId, "failed", null, null, null, "boom");

        assertThat(readyUpdated).isZero();
        assertThat(failedUpdated).isZero();
        // The row is untouched: still pending, no outputs, no error.
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("pending");
        assertThat(row.videoPath()).isNull();
        assertThat(row.fileSizeBytes()).isNull();
        assertThat(row.thumbPath()).isNull();
        assertThat(row.error()).isNull();
    }

    @Test
    void updateStatusIfGenerating_onGeneratingRow_writesFailed() {
        long clipId = insertClip("generating");

        int updated = clips.updateStatusIfGenerating(clipId, "failed", null, null, null, "ffmpeg blew up");

        assertThat(updated).isEqualTo(1);
        ClipRow row = clips.findById(clipId).orElseThrow();
        assertThat(row.status()).isEqualTo("failed");
        assertThat(row.error()).isEqualTo("ffmpeg blew up");
    }

    private long insertClip(String status) {
        return clips.insert(parentMatchId, "manual", null, 30.0, 45.0, null,
                null, null, null, status, null, System.currentTimeMillis());
    }
}
