package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the retention sweep's atomic clip-row operations, mirroring
 * {@link MatchRepositorySweepClaimTest}: {@link ClipRepository#claimForSweep} nulls the file columns
 * only on a still-non-starred row that still points at the snapshotted video (the starred re-check
 * and the prune are one UPDATE), and {@link ClipRepository#restoreVideoPath} undoes a claim whose
 * file unlink failed.
 */
class ClipRepositorySweepClaimTest {

    private ClipRepository clips;
    private long parentMatchId;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        clips = new ClipRepository(ds);
        parentMatchId = new MatchRepository(ds).insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, null, null, null, false, 1_000L, null));
    }

    @Test
    void claimForSweepPrunesANonStarredClipExactlyOnce() {
        long id = insert("C:\\vods\\clips\\old.mp4", "C:\\vods\\clips\\old.jpg", 100L, false);

        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\old.mp4")).isTrue();

        ClipRow claimed = clips.findById(id).orElseThrow();
        assertThat(claimed.videoPath()).isNull();
        assertThat(claimed.thumbPath()).isNull();
        assertThat(claimed.fileSizeBytes()).isNull();
        // A second claim finds no matching video_path: the row was already claimed.
        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\old.mp4")).isFalse();
    }

    @Test
    void claimForSweepRefusesAStarredClip() {
        long id = insert("C:\\vods\\clips\\starred.mp4", null, 100L, true);

        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\starred.mp4")).isFalse();
        assertThat(clips.findById(id).orElseThrow().videoPath())
                .isEqualTo("C:\\vods\\clips\\starred.mp4");
    }

    @Test
    void claimForSweepRefusesARepointedClip() {
        long id = insert("C:\\vods\\clips\\old.mp4", null, 100L, false);
        // The archiver moved the file since the sweeper's snapshot.
        clips.updateVideoPath(id, "D:\\archive\\clips\\old.mp4", null);

        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\old.mp4")).isFalse();
        assertThat(clips.findById(id).orElseThrow().videoPath())
                .isEqualTo("D:\\archive\\clips\\old.mp4");
    }

    @Test
    void restoreVideoPathUndoesAClaim() {
        long id = insert("C:\\vods\\clips\\locked.mp4", "C:\\vods\\clips\\locked.jpg", 100L, false);
        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\locked.mp4")).isTrue();

        // The file unlink failed: the sweeper puts the snapshotted columns back.
        assertThat(clips.restoreVideoPath(
                id, "C:\\vods\\clips\\locked.mp4", "C:\\vods\\clips\\locked.jpg", 100L))
                .isEqualTo(1);

        ClipRow restored = clips.findById(id).orElseThrow();
        assertThat(restored.videoPath()).isEqualTo("C:\\vods\\clips\\locked.mp4");
        assertThat(restored.thumbPath()).isEqualTo("C:\\vods\\clips\\locked.jpg");
        assertThat(restored.fileSizeBytes()).isEqualTo(100L);
    }

    private long insert(String videoPath, String thumbPath, Long fileSizeBytes, boolean starred) {
        long id = clips.insert(parentMatchId, "manual", null, 0.0, 10.0, null,
                videoPath, thumbPath, fileSizeBytes, "ready", null, 1_000L);
        if (starred) {
            clips.setStarred(id, true);
        }
        return id;
    }
}
