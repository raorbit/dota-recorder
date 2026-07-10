package dev.dotarec.data;

import dev.dotarec.data.MatchRepository.NewMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the retention sweep's atomic clip-row claim, the clip-side counterpart of
 * {@link MatchRepositorySweepClaimTest}: {@link ClipRepository#claimForSweep} answers — in one
 * guarded UPDATE, so the starred re-check cannot be a read-then-write race — whether the row is
 * still non-starred and still points at the snapshotted video, while mutating NOTHING. Unlike the
 * match claim (whose nulled paths are the swept row's end state), a claimed clip row is deleted
 * after the unlink, so a path-nulling claim would open a crash window: a hard kill between the
 * committed null and the unlink would leave a pathless row whose .mp4 still exists — invisible to
 * the stored-bytes budget and never swept again.
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
    void claimForSweepAcceptsANonStarredClipAndMutatesNothing() {
        long id = insert("C:\\vods\\clips\\old.mp4", "C:\\vods\\clips\\old.jpg", 100L, false);

        assertThat(clips.claimForSweep(id, "C:\\vods\\clips\\old.mp4")).isTrue();

        // Rowcount-only guard: the row is untouched, so a hard kill between the claim and the
        // unlink leaves a fully intact row that still counts in (and is swept from) the budget.
        ClipRow claimed = clips.findById(id).orElseThrow();
        assertThat(claimed.videoPath()).isEqualTo("C:\\vods\\clips\\old.mp4");
        assertThat(claimed.thumbPath()).isEqualTo("C:\\vods\\clips\\old.jpg");
        assertThat(claimed.fileSizeBytes()).isEqualTo(100L);
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

    private long insert(String videoPath, String thumbPath, Long fileSizeBytes, boolean starred) {
        long id = clips.insert(parentMatchId, "manual", null, 0.0, 10.0, null,
                videoPath, thumbPath, fileSizeBytes, "ready", null, 1_000L);
        if (starred) {
            clips.setStarred(id, true);
        }
        return id;
    }
}
