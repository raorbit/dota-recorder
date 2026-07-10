package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the retention sweep's atomic row operations: {@link MatchRepository#claimForSweep} prunes
 * only a still-non-starred row that still points at the snapshotted video (the starred re-check and
 * the prune are one UPDATE), {@link MatchRepository#reconcileMissingVideo} prunes regardless of
 * starred but only while the row still points at the vanished path, and
 * {@link MatchRepository#restoreVideoPath} undoes a claim whose file unlink failed.
 */
class MatchRepositorySweepClaimTest {

    private MatchRepository repo;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        DataSource ds = TestDb.migrated(dir);
        repo = new MatchRepository(ds);
    }

    @Test
    void claimForSweepPrunesANonStarredRowExactlyOnce() {
        long id = insert("C:\\vods\\old.mp4", "C:\\vods\\old.jpg", 100L, false);

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4")).isTrue();

        MatchSummary claimed = repo.findById(id).orElseThrow();
        assertThat(claimed.videoPath()).isNull();
        assertThat(claimed.thumbPath()).isNull();
        assertThat(claimed.fileSizeBytes()).isNull();
        // A second claim finds no matching video_path: the row was already pruned.
        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4")).isFalse();
    }

    @Test
    void claimForSweepRefusesAStarredRow() {
        long id = insert("C:\\vods\\starred.mp4", null, 100L, true);

        assertThat(repo.claimForSweep(id, "C:\\vods\\starred.mp4")).isFalse();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("C:\\vods\\starred.mp4");
    }

    @Test
    void claimForSweepRefusesARepointedRow() {
        long id = insert("C:\\vods\\old.mp4", null, 100L, false);
        // The archiver moved the file since the sweeper's snapshot.
        repo.updateVideoPath(id, "D:\\archive\\old.mp4", null);

        assertThat(repo.claimForSweep(id, "C:\\vods\\old.mp4")).isFalse();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("D:\\archive\\old.mp4");
    }

    @Test
    void reconcileMissingVideoPrunesStarredRowsToo() {
        long id = insert("C:\\vods\\gone.mp4", "C:\\vods\\gone.jpg", 100L, true);

        assertThat(repo.reconcileMissingVideo(id, "C:\\vods\\gone.mp4")).isEqualTo(1);

        MatchSummary reconciled = repo.findById(id).orElseThrow();
        assertThat(reconciled.videoPath()).isNull();
        assertThat(reconciled.thumbPath()).isNull();
        assertThat(reconciled.fileSizeBytes()).isNull();
        assertThat(reconciled.starred()).isTrue();
    }

    @Test
    void reconcileMissingVideoDoesNotClobberARepointedRow() {
        long id = insert("C:\\vods\\gone.mp4", null, 100L, false);
        repo.updateVideoPath(id, "D:\\archive\\moved.mp4", null);

        assertThat(repo.reconcileMissingVideo(id, "C:\\vods\\gone.mp4")).isZero();
        assertThat(repo.findById(id).orElseThrow().videoPath()).isEqualTo("D:\\archive\\moved.mp4");
    }

    @Test
    void restoreVideoPathUndoesAClaim() {
        long id = insert("C:\\vods\\locked.mp4", "C:\\vods\\locked.jpg", 100L, false);
        assertThat(repo.claimForSweep(id, "C:\\vods\\locked.mp4")).isTrue();

        // The file unlink failed: the sweeper puts the snapshotted columns back.
        assertThat(repo.restoreVideoPath(id, "C:\\vods\\locked.mp4", "C:\\vods\\locked.jpg", 100L))
                .isEqualTo(1);

        MatchSummary restored = repo.findById(id).orElseThrow();
        assertThat(restored.videoPath()).isEqualTo("C:\\vods\\locked.mp4");
        assertThat(restored.thumbPath()).isEqualTo("C:\\vods\\locked.jpg");
        assertThat(restored.fileSizeBytes()).isEqualTo(100L);
    }

    private long insert(String videoPath, String thumbPath, Long fileSizeBytes, boolean starred) {
        return repo.insert(new NewMatch(
                null, "match", "enriched", "puck",
                1, 2, 3, 400, 500, 10000, 120,
                "win", 7, 22, null, null, 1800,
                1_000L, videoPath, thumbPath, fileSizeBytes, starred, 1_000L, null));
    }
}
